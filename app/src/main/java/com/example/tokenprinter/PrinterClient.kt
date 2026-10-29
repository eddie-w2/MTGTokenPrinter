package com.example.tokenprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

data class FoundPrinter(val name: String, val address: String, val rssi: Int, val likely: Boolean = false)

private val PRINTER_NAME_PREFIXES = listOf("LX-", "DL-", "ZH-", "TD-", "YT", "C31", "T02", "XQ")

enum class Link { Disconnected, Connecting, Connected }

/** What the printer last reported about itself (0x02 status packets). */
data class PrinterInfo(
    val battery: Int,
    val paperOut: Boolean,
    val charging: Boolean,
    val hot: Boolean,
    val lowVoltage: Boolean,
    val density: Int,
) {
    /** The most pressing problem, if any, in a few words. */
    val warning: String?
        get() = when {
            paperOut -> "No paper"
            hot -> "Too hot"
            lowVoltage -> "Low battery"
            else -> null
        }

    /** The battery value's scale isn't documented: small numbers are treated as a 0-4 level, the rest as a percentage. */
    val batteryText: String
        get() = if (battery in 0..4) "level $battery of 4" else "$battery%"
}

/**
 * BLE client for the YTETCN / "Funny Print" family of thermal printers.
 *
 * Protocol recovered from the Funny Print app (all of it unverified on hardware, see README):
 *  - service FFE6, write characteristic FFE1 (write without response), notify characteristic FFE2
 *  - 12-byte commands starting 0x5A, 100-byte data packets starting 0x55
 */
@SuppressLint("MissingPermission")
class PrinterClient(private val ctx: Context, private val scope: CoroutineScope) {

    val link = MutableStateFlow(Link.Disconnected)
    val status = MutableStateFlow("Not connected")
    val found = MutableStateFlow<List<FoundPrinter>>(emptyList())
    val scanning = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())
    val info = MutableStateFlow<PrinterInfo?>(null)

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var deviceAddress = ""

    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    private val printLock = Mutex()

    @Volatile private var onConnected: CompletableDeferred<Unit>? = null
    @Volatile private var onServices: CompletableDeferred<Unit>? = null
    @Volatile private var onMtu: CompletableDeferred<Unit>? = null
    @Volatile private var onDesc: CompletableDeferred<Unit>? = null

    // ---------------------------------------------------------------- scanning

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val advertisesPrinterService = result.scanRecord?.serviceUuids
                ?.any { it.toString().contains("ffe6", true) } == true
            val likely = advertisesPrinterService ||
                PRINTER_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
            val f = FoundPrinter(name, result.device.address, result.rssi, likely)
            found.value = (found.value.filter { it.address != f.address } + f)
                .sortedWith(compareByDescending<FoundPrinter> { it.likely }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            status.value = "Scan failed ($errorCode)"
            scanning.value = false
        }
    }

    fun scan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status.value = "Turn Bluetooth on first"
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            status.value = "Bluetooth scanner unavailable"
            return
        }
        if (scanning.value) return
        found.value = emptyList()
        scanning.value = true
        status.value = "Scanning…"
        scanner.startScan(scanCb)
        scope.launch {
            delay(8_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
        scanning.value = false
        if (link.value == Link.Disconnected) status.value = "Not connected"
    }

    // -------------------------------------------------------------- connection

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (g.device.address != deviceAddress) return
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                onConnected?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
                val ex = IOException("Disconnected (status $st)")
                onConnected?.completeExceptionally(ex)
                onServices?.completeExceptionally(ex)
                onMtu?.completeExceptionally(ex)
                onDesc?.completeExceptionally(ex)
                if (link.value != Link.Connecting) {
                    link.value = Link.Disconnected
                    status.value = "Printer disconnected"
                    info.value = null
                }
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
            incoming(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) incoming(c.value ?: return)
        }
    }

    private fun incoming(b: ByteArray) {
        log("← ${hex(b)}")
        if (b.size >= 8 && (b[1].toInt() and 0xFF) == 0x02) {
            // Status packet: battery, paper (1 = out), charging, hot (1 = too hot), low voltage (1 = low), density.
            info.value = PrinterInfo(
                battery = b[2].toInt() and 0xFF,
                paperOut = b[3].toInt() == 1,
                charging = b[4].toInt() == 1,
                hot = b[5].toInt() == 1,
                lowVoltage = b[6].toInt() == 1,
                density = b[7].toInt() and 0xFF,
            )
        }
        rx.trySend(b.copyOf())
    }

    /** Asks the printer to report in (the hardware-info request also makes it send a status packet). */
    fun refreshStatus() {
        scope.launch {
            try {
                if (link.value == Link.Connected && !printLock.isLocked) write(cmd(0x01))
            } catch (e: Exception) {
                // not connected any more; nothing to refresh
            }
        }
    }

    suspend fun connect(address: String) {
        disconnect()
        var last: Exception? = null
        repeat(2) {
            try {
                connectOnce(address)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                closeGatt()
                delay(500)
            }
        }
        link.value = Link.Disconnected
        status.value = "Connect failed"
        throw last ?: IOException("Connect failed")
    }

    private suspend fun connectOnce(address: String) {
        val a = adapter ?: throw IOException("No Bluetooth adapter")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        stopScan()
        link.value = Link.Connecting
        status.value = "Connecting…"
        deviceAddress = address
        val dev = a.getRemoteDevice(address)

        val cd = CompletableDeferred<Unit>().also { onConnected = it }
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        withTimeout(15_000) { cd.await() }
        val g = gatt ?: throw IOException("Connection closed")

        val sd = CompletableDeferred<Unit>().also { onServices = it }
        if (!g.discoverServices()) throw IOException("Couldn't start service discovery")
        withTimeout(10_000) { sd.await() }

        val svc = g.services.firstOrNull { it.uuid.toString().contains("ffe6", true) }
            ?: throw IOException("Printer service FFE6 not found. Services: " +
                g.services.joinToString { it.uuid.toString().substring(0, 8) })
        val w = svc.characteristics.firstOrNull { it.uuid.toString().contains("ffe1", true) }
            ?: throw IOException("Write characteristic FFE1 not found")
        val n = svc.characteristics.firstOrNull { it.uuid.toString().contains("ffe2", true) }
            ?: throw IOException("Notify characteristic FFE2 not found")
        writeChar = w
        notifyChar = n

        val md = CompletableDeferred<Unit>().also { onMtu = it }
        if (g.requestMtu(247)) withTimeoutOrNull(3_000) { md.await() }
        log("MTU $mtu")

        g.setCharacteristicNotification(n, true)
        n.getDescriptor(CCCD)?.let { d ->
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

        link.value = Link.Connected
        status.value = "Connected to ${found.value.firstOrNull { it.address == address }?.name ?: address}"
        scope.launch {
            delay(400)
            refreshStatus()
        }
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        writeChar = null
        notifyChar = null
    }

    fun disconnect() {
        closeGatt()
        info.value = null
        link.value = Link.Disconnected
        status.value = "Not connected"
    }

    // ----------------------------------------------------------------- writing

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

    private suspend fun write(data: ByteArray, quiet: Boolean = false) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        if (!quiet) log("→ ${hex(data)}")
        val chunk = maxOf(20, mtu - 3)
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(data.size, off + chunk))
            var tries = 0
            while (!rawWrite(g, c, part)) {
                if (link.value != Link.Connected) throw IOException("Printer disconnected")
                if (++tries > 400) throw IOException("Bluetooth write failed")
                delay(5)
            }
            off += part.size
        }
    }

    private suspend fun waitFor(vararg ops: Int, ms: Long): ByteArray? = withTimeoutOrNull(ms) {
        var m: ByteArray
        do {
            m = rx.receive()
        } while (m.size < 2 || (m[1].toInt() and 0xFF) !in ops)
        m
    }

    // ------------------------------------------------------------------ printing

    private suspend fun runHandshake() {
        val macs = linkedSetOf(deviceAddress.replace(":", "").uppercase())
        write(cmd(0x01))
        val info = waitFor(0x01, 0x02, ms = 1000)
        if (info != null && info.size >= 8) {
            macs.add(hex(info, 1, 7).replace(" ", ""))
            macs.add(hex(info, 2, 8).replace(" ", ""))
        }
        for (mac in macs) {
            val rnd = IntArray(10) { Random.nextInt(1, 255) }
            val (low, high) = challenge(rnd, mac)
            write(cmd(0x0A, *rnd))
            val r = waitFor(0x0A, ms = 1500)
            if (r == null) {
                log("handshake: no reply, continuing without it")
                return
            }
            if (hex(r, 2, 12).replace(" ", "") != low) {
                log("handshake: key mismatch for $mac")
                continue
            }
            write(cmd(0x0B, *hexToBytes(high).map { it.toInt() and 0xFF }.toIntArray()))
            val ok = waitFor(0x0B, ms = 1500)
            log(if (ok != null && ok.size > 2 && ok[2].toInt() == 1) "handshake OK" else "handshake: app check rejected")
            return
        }
        log("handshake: no matching key, trying to print anyway")
    }

    /** [bits] is a 48-bytes-per-row, 1-bit raster with an even number of rows. */
    suspend fun print(bits: ByteArray, density: Int, useHandshake: Boolean, onProgress: (Int, Int) -> Unit) {
        printLock.withLock {
            if (link.value != Link.Connected) throw IOException("Printer not connected")
            while (rx.tryReceive().isSuccess) { /* drop stale notifications */ }
            if (useHandshake) runHandshake()

            write(cmd(0x0C, (density - 1).coerceIn(0, 2)))
            delay(50)

            val padded = bits.copyOf(((bits.size + PKT_DATA - 1) / PKT_DATA) * PKT_DATA)
            val total = padded.size / PKT_DATA
            // Funny Print: byte 4 = 0 starts a print, 1 ends/cancels it. Byte 5 (copies) is 0 for this printer family.
            write(cmd(0x04, total shr 8, total and 0xFF, 0, 0))
            waitFor(0x04, ms = 300)

            log("sending $total data packets")
            var next = 0
            var paused = false
            var pausedSince = 0L
            var finished = false
            var sentAllAt = 0L

            fun control(m: ByteArray) {
                if (m.size < 2) return
                when (m[1].toInt() and 0xFF) {
                    0x05 -> if (m.size >= 4) {
                        // The printer asks for packet N (1-based); Funny Print resumes sending from N-1.
                        val req = ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)
                        next = (req - 1).coerceIn(0, total)
                        paused = false
                    }
                    0x08 -> {
                        paused = true
                        pausedSince = SystemClock.elapsedRealtime()
                    }
                    0x06 -> finished = true
                }
            }

            while (!finished) {
                if (link.value != Link.Connected) throw IOException("Printer disconnected")
                while (true) control(rx.tryReceive().getOrNull() ?: break)
                if (finished) break
                val now = SystemClock.elapsedRealtime()
                if (paused) {
                    if (now - pausedSince > 20_000) throw IOException("Printer stayed paused (paper out or cover open?)")
                    delay(25)
                } else if (next < total) {
                    val p = ByteArray(PKT_SIZE)
                    p[0] = 0x55
                    p[1] = (next shr 8).toByte()
                    p[2] = next.toByte()
                    System.arraycopy(padded, next * PKT_DATA, p, 3, PKT_DATA)
                    write(p, quiet = true)
                    next++
                    if (next >= total) {
                        sentAllAt = now
                        log("all $total packets sent, waiting for printer")
                    }
                    onProgress(next, total)
                    delay(PKT_DELAY_MS)
                } else {
                    if (now - sentAllAt > 30_000) {
                        log("no end-of-print message, assuming done")
                        break
                    }
                    delay(25)
                }
            }
            if (finished) {
                // Funny Print acknowledges the end of a print with the "byte 4 = 1" status command.
                try {
                    write(cmd(0x04, total shr 8, total and 0xFF, 1, 0))
                } catch (_: IOException) {
                }
            }
            log("print finished")
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun log(s: String) {
        log.value = (log.value + s).takeLast(60)
    }

    companion object {
        private const val PKT_DATA = 96
        private const val PKT_SIZE = 100
        private const val PKT_DELAY_MS = 8L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun cmd(op: Int, vararg b: Int): ByteArray {
            val a = ByteArray(12)
            a[0] = 0x5A
            a[1] = op.toByte()
            b.forEachIndexed { i, v -> a[2 + i] = v.toByte() }
            return a
        }

        fun crc16Xmodem(data: ByteArray): Int {
            var crc = 0
            for (byte in data) {
                val b = byte.toInt() and 0xFF
                for (i in 0..7) {
                    val bit = ((b shr (7 - i)) and 1) == 1
                    val c15 = ((crc shr 15) and 1) == 1
                    crc = crc shl 1
                    if (c15 xor bit) crc = crc xor 0x1021
                }
            }
            return crc and 0xFFFF
        }

        /** Returns (expectedPrinterReply, keyBytesToSend) as upper-case hex strings. */
        fun challenge(rnd: IntArray, mac: String): Pair<String, String> {
            val low = StringBuilder()
            val high = StringBuilder()
            for (r in rnd) {
                val v = crc16Xmodem(hexToBytes("%02X".format(r) + mac))
                val s = "%04X".format(v)
                high.append(s.substring(0, 2))
                low.append(s.substring(2, 4))
            }
            return low.toString() to high.toString()
        }

        fun hexToBytes(s: String) =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun hex(b: ByteArray, from: Int = 0, to: Int = b.size) =
            (from until minOf(to, b.size)).joinToString(" ") { "%02X".format(b[it]) }
    }
}
