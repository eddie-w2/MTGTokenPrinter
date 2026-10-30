// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors
// Portions ported from TiMini-Print (https://github.com/Dejniel/TiMini-Print),
// Copyright 2026 Daniel Banecki, licensed under the Apache License 2.0. See THIRD_PARTY_NOTICES.md.

package com.example.tokenprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Where a write goes: the normal command channel, or the bulk image channel some printers have (MXW01: AE03). */
enum class Pipe { Main, Bulk }

/** How fast to push bytes at a printer. */
data class Pace(val chunkCap: Int, val delayMs: Long, val flowControlled: Boolean = false, val reserve: Int = 0)

/** A connection to a printer, over BLE (GATT) or Classic Bluetooth (SPP). */
interface PrinterLink {
    val isBle: Boolean
    val mtu: Int

    /** Set by the driver when the printer asks to pause (its buffer is full) and cleared when it asks to resume. */
    var paused: Boolean

    /** The 4-hex-digit short ids (e.g. "ae30") of the GATT services found; empty for Classic. */
    val services: Set<String>
    fun has(shortUuid: String): Boolean
    val connected: Boolean

    suspend fun send(data: ByteArray, pace: Pace, pipe: Pipe = Pipe.Main, onChunk: ((Int) -> Unit)? = null)
    fun close()
}

private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
private fun shortId(u: UUID): String {
    val s = u.toString().lowercase()
    return if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX)) s.substring(4, 8) else s
}

/** Which GATT service and characteristics a printer family uses (TiMini-Print devices/bluetooth_profiles.py). */
class GattPlan(
    val services: List<String>,
    val writes: List<String>,
    /** Characteristic to subscribe to for replies; null means every notify characteristic in the chosen service. */
    val notify: List<String> = emptyList(),
    val bulk: String? = null,
) {
    companion object {
        val CAT_SERVICES = listOf("ae30", "ae3a", "ae00", "ae80", "ff00", "ab00")
        val CAT_WRITES = listOf("ae01", "ae3b", "ae81", "ff02", "ab01")

        fun forFamily(f: PrinterFamily): GattPlan = when (f) {
            PrinterFamily.FUNNY_LX -> GattPlan(listOf("ffe6"), listOf("ffe1"), listOf("ffe2"))
            PrinterFamily.TINY, PrinterFamily.V5G -> GattPlan(CAT_SERVICES, CAT_WRITES)
            PrinterFamily.V5X -> GattPlan(listOf("ae30"), listOf("ae01"), listOf("ae02"), bulk = "ae03")
            PrinterFamily.PHOMEMO -> GattPlan(listOf("ff00") + CAT_SERVICES, listOf("ff02") + CAT_WRITES)
            PrinterFamily.ESCPOS -> GattPlan(
                listOf("18f0", "49535343-fe7d-4ae5-8fa9-9fafd205e455", "ffe0", "ff00", "e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                listOf("2af1", "49535343-8841-43f4-a8d4-ecbe34729bb3", "ffe1", "ff02", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            )
        }
    }
}

/** BLE link. Characteristic choice follows TiMini-Print's resolver: known UUIDs first, then any writable one. */
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val log: (String) -> Unit,
    private val onNotify: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
) : PrinterLink {
    override val isBle = true
    override var mtu = 23
        private set
    @Volatile override var paused = false
    override var services: Set<String> = emptySet()
        private set
    @Volatile override var connected = false
        private set

    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var bulk: BluetoothGattCharacteristic? = null
    private var allServices = emptyList<android.bluetooth.BluetoothGattService>()

    @Volatile private var onConnected: CompletableDeferred<Unit>? = null
    @Volatile private var onServices: CompletableDeferred<Unit>? = null
    @Volatile private var onMtu: CompletableDeferred<Unit>? = null
    @Volatile private var onDesc: CompletableDeferred<Unit>? = null
    @Volatile private var closing = false

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                onConnected?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
                val wasConnected = connected
                connected = false
                val ex = IOException("Disconnected (status $st)")
                onConnected?.completeExceptionally(ex)
                onServices?.completeExceptionally(ex)
                onMtu?.completeExceptionally(ex)
                onDesc?.completeExceptionally(ex)
                if (wasConnected && !closing) onDisconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            if (st == BluetoothGatt.GATT_SUCCESS) onServices?.complete(Unit)
            else onServices?.completeExceptionally(IOException("Service discovery failed ($st)"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuSize: Int, st: Int) {
            if (st == BluetoothGatt.GATT_SUCCESS) mtu = mtuSize
            onMtu?.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            onDesc?.complete(Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(value.copyOf())
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onNotify((c.value ?: return).copyOf())
        }
    }

    /** Connects and lists the services; [setUp] then picks the characteristics for a family. */
    suspend fun open() {
        val dev = adapter.getRemoteDevice(address)
        val cd = CompletableDeferred<Unit>().also { onConnected = it }
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        withTimeout(15_000) { cd.await() }
        val g = gatt ?: throw IOException("Connection closed")
        val sd = CompletableDeferred<Unit>().also { onServices = it }
        if (!g.discoverServices()) throw IOException("Couldn't start service discovery")
        withTimeout(10_000) { sd.await() }
        allServices = g.services ?: emptyList()
        services = allServices.map { shortId(it.uuid) }.toSet()
        log("services: " + services.joinToString { it.take(8) })
    }

    override fun has(shortUuid: String): Boolean =
        shortUuid.lowercase() in services || allServices.any { s -> s.characteristics.any { shortId(it.uuid) == shortUuid.lowercase() } }

    private fun writable(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private fun notifiable(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    /** Picks the write / notify characteristics for [plan], asks for a bigger MTU, and turns notifications on. */
    suspend fun setUp(plan: GattPlan, mtuRequest: Int?) {
        val g = gatt ?: throw IOException("Not connected")
        data class Cand(val c: BluetoothGattCharacteristic, val score: Int)
        val cands = allServices.flatMap { s ->
            val sId = shortId(s.uuid)
            val sPref = plan.services.indexOf(sId).let { if (it < 0) 0 else 100 - it }
            s.characteristics.filter { writable(it) }.map { c ->
                val cId = shortId(c.uuid)
                val cPref = plan.writes.indexOf(cId).let { if (it < 0) 0 else 1000 - it }
                val wnr = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) 1 else 0
                Cand(c, cPref * 1000 + sPref * 2 + wnr)
            }
        }.filter { plan.bulk == null || shortId(it.c.uuid) != plan.bulk }
        val w = cands.maxByOrNull { it.score }?.c ?: throw IOException(
            "No writable characteristic. Services: " + services.joinToString { it.take(8) }
        )
        write = w
        val service = w.service
        bulk = plan.bulk?.let { b -> service.characteristics.firstOrNull { shortId(it.uuid) == b } }
        log("write ${shortId(w.uuid).take(8)} in ${shortId(service.uuid).take(8)}" + (bulk?.let { " · bulk ${shortId(it.uuid)}" } ?: ""))

        val wanted = mtuRequest ?: 247
        if (wanted > 23) {
            val md = CompletableDeferred<Unit>().also { onMtu = it }
            if (g.requestMtu(wanted)) withTimeoutOrNull(3_000) { md.await() }
        }
        log("MTU $mtu")

        val notifies = LinkedHashSet<BluetoothGattCharacteristic>()
        plan.notify.forEach { id -> allServices.forEach { s -> s.characteristics.filter { shortId(it.uuid) == id && notifiable(it) }.forEach { notifies += it } } }
        service.characteristics.filter { notifiable(it) }.forEach { notifies += it }
        for (n in notifies) enableNotify(g, n)
    }

    private suspend fun enableNotify(g: BluetoothGatt, n: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(n, true)
        val d = n.getDescriptor(CCCD) ?: return
        val dd = CompletableDeferred<Unit>().also { onDesc = it }
        val value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }
        if (started) withTimeoutOrNull(3_000) { dd.await() }
    }

    private fun rawWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, part: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = part
                g.writeCharacteristic(c)
            }
        }
    }

    override suspend fun send(data: ByteArray, pace: Pace, pipe: Pipe, onChunk: ((Int) -> Unit)?) {
        val g = gatt ?: throw IOException("Not connected")
        val c = (if (pipe == Pipe.Bulk) bulk else null) ?: write ?: throw IOException("Not connected")
        val chunk = maxOf(20, minOf(pace.chunkCap, mtu - 3 - pace.reserve))
        var off = 0
        while (off < data.size) {
            if (pace.flowControlled && paused) {
                val since = SystemClock.elapsedRealtime()
                while (paused) {
                    if (!connected) throw IOException("Printer disconnected")
                    if (SystemClock.elapsedRealtime() - since > 60_000) throw IOException("Printer stayed paused (paper out or too hot?)")
                    delay(20)
                }
            }
            val part = data.copyOfRange(off, minOf(data.size, off + chunk))
            var tries = 0
            while (!rawWrite(g, c, part)) {
                if (!connected) throw IOException("Printer disconnected")
                if (++tries > 400) throw IOException("Bluetooth write failed")
                delay(5)
            }
            off += part.size
            onChunk?.invoke(off)
            if (pace.delayMs > 0) delay(pace.delayMs)
        }
    }

    override fun close() {
        closing = true
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        connected = false
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** Classic Bluetooth serial (SPP / RFCOMM) link, for printers that don't offer a usable BLE service. */
@SuppressLint("MissingPermission")
class SppLink(
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val log: (String) -> Unit,
    private val onNotify: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
) : PrinterLink {
    override val isBle = false
    override val mtu = 512
    @Volatile override var paused = false
    override val services: Set<String> = emptySet()
    override fun has(shortUuid: String) = false
    @Volatile override var connected = false
        private set

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile private var closing = false

    suspend fun open() = withContext(Dispatchers.IO) {
        adapter.cancelDiscovery()
        val dev = adapter.getRemoteDevice(address)
        val bonded = dev.bondState == BluetoothDevice.BOND_BONDED
        val attempts = if (bonded) listOf(true, false) else listOf(false, true)
        var last: Exception? = null
        for (secure in attempts) {
            try {
                val s = if (secure) dev.createRfcommSocketToServiceRecord(SPP) else dev.createInsecureRfcommSocketToServiceRecord(SPP)
                s.connect()
                socket = s
                out = s.outputStream
                connected = true
                log("SPP connected (" + (if (secure) "paired" else "unpaired") + ")")
                startReader(s.inputStream)
                return@withContext
            } catch (e: Exception) {
                last = e
                log("SPP ${if (secure) "paired" else "unpaired"} connect failed: ${e.message}")
            }
        }
        throw IOException("Classic Bluetooth connect failed: ${last?.message}")
    }

    private fun startReader(input: InputStream) {
        Thread {
            val buf = ByteArray(256)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onNotify(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            val was = connected
            connected = false
            if (was && !closing) onDisconnect()
        }.apply {
            isDaemon = true
            name = "spp-reader"
        }.start()
    }

    override suspend fun send(data: ByteArray, pace: Pace, pipe: Pipe, onChunk: ((Int) -> Unit)?) = withContext(Dispatchers.IO) {
        val o = out ?: throw IOException("Not connected")
        val chunk = maxOf(20, pace.chunkCap)
        var off = 0
        while (off < data.size) {
            while (pace.flowControlled && paused) {
                if (!connected) throw IOException("Printer disconnected")
                delay(20)
            }
            val n = minOf(chunk, data.size - off)
            o.write(data, off, n)
            o.flush()
            off += n
            onChunk?.invoke(off)
            if (pace.delayMs > 0) delay(pace.delayMs)
        }
    }

    override fun close() {
        closing = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        connected = false
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
