package com.example.tokenprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** A Bluetooth device seen while scanning, with the printer types its name points to. */
data class FoundPrinter(
    val name: String,
    val address: String,
    val rssi: Int,
    val types: List<PrinterType> = emptyList(),
    /** Seen through Classic Bluetooth discovery or the paired list rather than a BLE advertisement. */
    val classic: Boolean = false,
    val paired: Boolean = false,
    /** Classic device class says "imaging" (printers and scanners). */
    val imaging: Boolean = false,
    val manufacturerData: List<ByteArray> = emptyList(),
) {
    val known: Boolean get() = types.isNotEmpty()
    val tested: Boolean get() = name.trim().uppercase() in PrinterCatalog.TESTED_NAMES
}

enum class Link { Disconnected, Connecting, Connected }

/** What the printer last reported about itself. Fields a printer doesn't report keep their defaults. */
data class PrinterInfo(
    val battery: Int?,
    val paperOut: Boolean,
    val charging: Boolean,
    val hot: Boolean,
    val lowVoltage: Boolean,
    val density: Int?,
    /** True when [battery] is a percentage rather than an undocumented level. */
    val batteryPercent: Boolean = false,
) {
    /** The most pressing problem, if any, in a few words. */
    val warning: String?
        get() = when {
            paperOut -> "No paper"
            hot -> "Too hot"
            lowVoltage -> "Low battery"
            else -> null
        }

    val batteryText: String
        get() = when {
            battery == null -> "not reported"
            batteryPercent -> "$battery%"
            battery in 0..4 -> "level $battery of 4"
            else -> "$battery%"
        }

    companion object {
        fun unknown() = PrinterInfo(null, paperOut = false, charging = false, hot = false, lowVoltage = false, density = null)
    }
}

/**
 * Finds printers (BLE scan plus Classic discovery and the paired list), works out what kind each one is, and
 * connects with the right driver. Printer families and model names come from [PrinterCatalog].
 */
@SuppressLint("MissingPermission")
class PrinterClient(private val ctx: Context, private val scope: CoroutineScope) {

    val link = MutableStateFlow(Link.Disconnected)
    val status = MutableStateFlow("Not connected")
    val found = MutableStateFlow<List<FoundPrinter>>(emptyList())
    val scanning = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())
    val info = MutableStateFlow<PrinterInfo?>(null)

    /** The connected printer's type, and whether it was recognised for sure (by name) or guessed / picked by hand. */
    val detected = MutableStateFlow<Detected?>(null)
    val connectedName = MutableStateFlow<String?>(null)

    val catalog: PrinterCatalog by lazy { PrinterCatalog.get(ctx) }

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private var conn: PrinterLink? = null
    private var driver: PrinterDriver? = null
    private val printLock = Mutex()

    // ---------------------------------------------------------------- scanning

    private fun upsert(f: FoundPrinter) {
        val old = found.value.firstOrNull { it.address == f.address }
        val merged = if (old == null) f else f.copy(
            name = f.name.ifBlank { old.name },
            rssi = if (f.rssi != 0) f.rssi else old.rssi,
            types = f.types.ifEmpty { old.types },
            classic = f.classic && old.classic,
            paired = f.paired || old.paired,
            imaging = f.imaging || old.imaging,
            manufacturerData = f.manufacturerData.ifEmpty { old.manufacturerData },
        )
        found.value = (found.value.filter { it.address != f.address } + merged)
            .sortedWith(compareByDescending<FoundPrinter> { it.known }.thenByDescending { it.rssi })
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (name.isBlank()) return
            val mfr = mutableListOf<ByteArray>()
            result.scanRecord?.manufacturerSpecificData?.let { sa ->
                for (i in 0 until sa.size()) {
                    val id = sa.keyAt(i)
                    val data = sa.valueAt(i) ?: ByteArray(0)
                    mfr += byteArrayOf((id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte()) + data
                }
            }
            val types = catalog.detect(name, result.device.address, mfr)
            upsert(FoundPrinter(name, result.device.address, result.rssi, types, manufacturerData = mfr))
        }

        override fun onScanFailed(errorCode: Int) {
            status.value = "Scan failed ($errorCode)"
            scanning.value = false
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_FOUND) return
            @Suppress("DEPRECATION")
            val dev: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
            val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: dev.name ?: return
            if (name.isBlank()) return
            val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().let { if (it == Short.MIN_VALUE.toInt()) -100 else it }
            val imaging = dev.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING
            val types = catalog.detect(name, dev.address, emptyList())
            if (types.isEmpty() && !imaging) return
            upsert(FoundPrinter(name, dev.address, rssi, types, classic = true, paired = dev.bondState == BluetoothDevice.BOND_BONDED, imaging = imaging))
        }
    }
    private var receiverRegistered = false

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
        // Paired printers first: Classic-only printers (many Phomemo and receipt printers) often don't advertise.
        try {
            a.bondedDevices?.forEach { dev ->
                val name = dev.name ?: return@forEach
                val imaging = dev.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING
                val types = catalog.detect(name, dev.address, emptyList())
                if (types.isNotEmpty() || imaging) {
                    upsert(FoundPrinter(name, dev.address, -100, types, classic = true, paired = true, imaging = imaging))
                }
            }
        } catch (_: Exception) {
        }
        try {
            scanner.startScan(scanCb)
        } catch (e: Exception) {
            log("BLE scan failed: ${e.message}")
        }
        try {
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(ctx, classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            a.startDiscovery()
        } catch (e: Exception) {
            log("Classic discovery failed: ${e.message}")
        }
        scope.launch {
            delay(10_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(classicReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        scanning.value = false
        if (link.value == Link.Disconnected) status.value = "Not connected"
    }

    // -------------------------------------------------------------- connection

    /**
     * Connects to [address]. [forced] picks the printer type by hand ("Try as…"); otherwise the name decides, and when
     * the name is unknown or ambiguous the printer's Bluetooth services decide.
     */
    suspend fun connect(address: String, name: String, forced: PrinterType?, classicHint: Boolean = false, mfr: List<ByteArray> = emptyList()) {
        disconnect()
        stopScan()
        val a = adapter ?: throw IOException("No Bluetooth adapter")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        link.value = Link.Connecting
        status.value = "Connecting…"
        try {
            val cands = forced?.let { listOf(it) } ?: catalog.detect(name, address, mfr)
            var last: Exception? = null
            repeat(2) { attempt ->
                try {
                    connectOnce(a, address, name, cands, forced != null, classicHint)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    closeLink()
                    if (e is UnknownPrinterException) throw e
                    if (attempt == 0) delay(500)
                }
            }
            throw last ?: IOException("Connect failed")
        } catch (e: Exception) {
            closeLink()
            link.value = Link.Disconnected
            status.value = "Connect failed"
            throw e
        }
    }

    class UnknownPrinterException(msg: String) : IOException(msg)

    private suspend fun connectOnce(a: BluetoothAdapter, address: String, name: String, cands: List<PrinterType>, forced: Boolean, classicHint: Boolean) {
        val first = cands.firstOrNull()
        val classicOnly = classicHint && (first == null || first.family == PrinterFamily.PHOMEMO || first.family == PrinterFamily.ESCPOS)
        var type: PrinterType? = null
        var sure = forced || cands.size == 1
        var l: PrinterLink? = null

        if (!classicOnly) {
            val ble = BleLink(ctx, a, address, { log(it) }, { notify(it) }, { lost() })
            try {
                ble.open()
                type = if (forced) first else pickByServices(cands, ble)
                if (type != null && !forced && cands.size != 1) sure = false
                if (type != null && servicesFit(type, ble)) l = ble else ble.close()
            } catch (e: CancellationException) {
                ble.close()
                throw e
            } catch (e: Exception) {
                ble.close()
                log("BLE: ${e.message}")
                if (first == null || !(first.family == PrinterFamily.PHOMEMO || first.family == PrinterFamily.ESCPOS)) throw e
            }
        }
        val lk: PrinterLink = l ?: run {
            // Classic Bluetooth: Phomemo and receipt printers.
            val t = type ?: first
            if (t == null && !classicHint) {
                throw UnknownPrinterException("Token Printer doesn't recognise this printer. Use \"Try as…\" to pick its type.")
            }
            if (t != null && t.family != PrinterFamily.PHOMEMO && t.family != PrinterFamily.ESCPOS) {
                throw IOException("Couldn't find the ${t.family.label} service on this printer")
            }
            val spp = SppLink(a, address, { log(it) }, { notify(it) }, { lost() })
            spp.open()
            type = t ?: catalog.byKey(PrinterCatalog.ESCPOS_58)
            if (t == null) sure = false
            spp
        }
        val chosen = type ?: throw UnknownPrinterException("Token Printer doesn't recognise this printer. Use \"Try as…\" to pick its type.")
        val d = PrinterDriver.create(chosen, address, scope, info) { log(it) }
        d.link = lk
        try {
            if (lk is BleLink) lk.setUp(d.gattPlan, chosen.mtu)
        } catch (e: Exception) {
            lk.close()
            throw e
        }
        conn = lk
        driver = d
        detected.value = Detected(chosen, sure)
        connectedName.value = name
        link.value = Link.Connected
        status.value = "Connected to $name"
        log("connected as ${chosen.name} (${chosen.family.label}, ${chosen.widthDots} dots)")
        scope.launch {
            try {
                d.onConnected()
            } catch (e: Exception) {
                log("setup: ${e.message}")
            }
        }
    }

    /** Whether the connected device has the GATT service the type needs. */
    private fun servicesFit(t: PrinterType, l: BleLink): Boolean {
        val plan = GattPlan.forFamily(t.family)
        if (plan.services.any { l.has(it) } || plan.writes.any { l.has(it) }) return true
        return t.family == PrinterFamily.ESCPOS && l.services.isNotEmpty()
    }

    /**
     * Picks between candidate types using the services the printer offers. With no candidates (unknown name) this is
     * the probe: AE30+AE03 = MXW01-style, FFE6 = Funny Print, AE30 = classic cat printer, FF00 = Phomemo,
     * serial-style services = receipt printer.
     */
    private fun pickByServices(cands: List<PrinterType>, l: BleLink): PrinterType? {
        val hasAe03 = l.has("ae03")
        if (cands.isNotEmpty()) {
            if (hasAe03) cands.firstOrNull { it.family == PrinterFamily.V5X }?.let { return it }
            if (l.has("ffe6")) cands.firstOrNull { it.family == PrinterFamily.FUNNY_LX }?.let { return it }
            val fitting = cands.filter { servicesFit(it, l) && (hasAe03 || it.family != PrinterFamily.V5X) }
            return fitting.firstOrNull() ?: cands.first()
        }
        return when {
            l.has("ae30") && hasAe03 -> catalog.byKey("v5x")
            l.has("ffe6") -> catalog.byKey("funny_lx_d")
            l.has("ae30") || l.has("ae3a") || l.has("ae00") || l.has("ae80") || l.has("ab00") -> catalog.byKey("gb01")
            l.has("ff00") -> catalog.byKey("phomemo_m02")
            l.has("18f0") || l.has("49535343-fe7d-4ae5-8fa9-9fafd205e455") || l.has("ffe0") -> catalog.byKey(PrinterCatalog.ESCPOS_58)
            else -> null
        }
    }

    private fun notify(b: ByteArray) {
        log("← ${hexShort(b)}")
        driver?.onNotify(b)
    }

    private fun lost() {
        link.value = Link.Disconnected
        status.value = "Printer disconnected"
        info.value = null
    }

    fun refreshStatus() {
        scope.launch {
            try {
                if (link.value == Link.Connected && !printLock.isLocked) driver?.refreshStatus()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeLink() {
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        conn = null
        driver = null
    }

    fun disconnect() {
        closeLink()
        info.value = null
        detected.value = null
        connectedName.value = null
        link.value = Link.Disconnected
        status.value = "Not connected"
    }

    // ------------------------------------------------------------------ printing

    suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        printLock.withLock {
            val d = driver
            if (link.value != Link.Connected || d == null) throw IOException("Printer not connected")
            if (job.width != d.type.widthDots) {
                throw IOException("The image is ${job.width} dots wide but this printer takes ${d.type.widthDots}. Try again.")
            }
            d.print(job, onProgress)
        }
    }

    // ----------------------------------------------------------------- helpers

    fun log(s: String) {
        log.value = (log.value + s).takeLast(60)
    }

    private fun hexShort(b: ByteArray) =
        b.take(24).joinToString(" ") { "%02X".format(it) } + if (b.size > 24) " …" else ""
}
