// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors
// Portions ported from TiMini-Print (https://github.com/Dejniel/TiMini-Print),
// Copyright 2026 Daniel Banecki, licensed under the Apache License 2.0. See THIRD_PARTY_NOTICES.md.

package com.example.tokenprinter

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * One print: a 1-bit raster, [width] dots across, packed 8 dots per byte, most significant bit first (unless the
 * user turned on "Reverse bit order"), 1 = black.
 */
class RasterJob(
    val bits: ByteArray,
    val width: Int,
    /** 1 = light, 2 = normal, 3 = dark. */
    val darkness: Int,
    val handshake: Boolean,
    /** "Remove white space": skip the extra paper feed after the image where the protocol has one. */
    val trim: Boolean,
) {
    val rowBytes: Int get() = width / 8
    val height: Int get() = bits.size / rowBytes

    fun row(y: Int): ByteArray = bits.copyOfRange(y * rowBytes, (y + 1) * rowBytes)

    /** The raster with each byte's bits reversed (least significant bit = leftmost dot), as cat printers want it. */
    fun lsbBits(): ByteArray = ByteArray(bits.size) { REVERSED[bits[it].toInt() and 0xFF] }

    companion object {
        val REVERSED = ByteArray(256) { i ->
            var v = i
            var r = 0
            repeat(8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            r.toByte()
        }
    }
}

/** Builds the bytes a printer family understands and runs the conversation with it. */
abstract class PrinterDriver(
    val type: PrinterType,
    protected val address: String,
    protected val scope: CoroutineScope,
    protected val info: MutableStateFlow<PrinterInfo?>,
    protected val log: (String) -> Unit,
) {
    lateinit var link: PrinterLink
    protected val rx = Channel<ByteArray>(Channel.UNLIMITED)

    open val gattPlan: GattPlan get() = GattPlan.forFamily(type.family)

    /** Called right after the link is up. */
    open suspend fun onConnected() {}

    /** Every notification from the printer, on a Bluetooth thread. Keep it quick. */
    open fun onNotify(b: ByteArray) {
        rx.trySend(b)
    }

    abstract suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit)

    open suspend fun refreshStatus() {}

    protected fun drain() {
        while (rx.tryReceive().isSuccess) { /* stale replies */ }
    }

    protected suspend fun waitFor(ms: Long, match: (ByteArray) -> Boolean): ByteArray? = withTimeoutOrNull(ms) {
        var m: ByteArray
        do {
            m = rx.receive()
        } while (!match(m))
        m
    }

    protected fun hex(b: ByteArray, from: Int = 0, to: Int = b.size) =
        (from until minOf(to, b.size)).joinToString(" ") { "%02X".format(b[it]) }

    companion object {
        fun create(
            type: PrinterType,
            address: String,
            scope: CoroutineScope,
            info: MutableStateFlow<PrinterInfo?>,
            log: (String) -> Unit,
        ): PrinterDriver = when (type.family) {
            PrinterFamily.FUNNY_LX -> FunnyLxDriver(type, address, scope, info, log)
            PrinterFamily.TINY -> TinyDriver(type, address, scope, info, log)
            PrinterFamily.V5G -> V5gDriver(type, address, scope, info, log)
            PrinterFamily.V5X -> V5xDriver(type, address, scope, info, log)
            PrinterFamily.PHOMEMO -> PhomemoDriver(type, address, scope, info, log)
            PrinterFamily.ESCPOS -> EscPosDriver(type, address, scope, info, log)
        }
    }
}

// ============================================================ shared framing

/** CRC-8 (polynomial 0x07, start 0), as used by the 51 78 / 22 21 command frames. */
object Crc8 {
    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    fun of(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) crc = table[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        return crc
    }
}

/** Frames like `51 78 <cmd> 00 <len lo> <len hi> <payload> <crc8> FF` (TiMini-Print protocol/packet.py make_packet). */
object Frames {
    val CAT = byteArrayOf(0x51, 0x78)
    val V5X = byteArrayOf(0x22, 0x21)

    fun make(prefix: ByteArray, cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(prefix.size + 4 + payload.size + 2)
        prefix.copyInto(out)
        var i = prefix.size
        out[i++] = cmd.toByte()
        out[i++] = 0
        out[i++] = (payload.size and 0xFF).toByte()
        out[i++] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, i)
        i += payload.size
        out[i++] = Crc8.of(payload).toByte()
        out[i] = 0xFF.toByte()
        return out
    }

    fun cat(cmd: Int, vararg payload: Int) = make(CAT, cmd, ByteArray(payload.size) { payload[it].toByte() })

    /** Splits a notification stream into whole frames with a valid checksum; returns (opcode, payload) pairs. */
    class Decoder(private val prefix: ByteArray) {
        private val buf = ByteArrayOutputStream()

        fun feed(data: ByteArray): List<Pair<Int, ByteArray>> {
            buf.write(data)
            var b = buf.toByteArray()
            val out = mutableListOf<Pair<Int, ByteArray>>()
            var start = 0
            while (true) {
                val m = indexOf(b, prefix, start)
                if (m < 0) {
                    start = b.size
                    break
                }
                val header = prefix.size + 4
                if (b.size - m < header) {
                    start = m
                    break
                }
                val len = (b[m + prefix.size + 2].toInt() and 0xFF) or ((b[m + prefix.size + 3].toInt() and 0xFF) shl 8)
                val total = header + len + 2
                if (b.size - m < total) {
                    start = m
                    break
                }
                val payload = b.copyOfRange(m + header, m + header + len)
                if ((b[m + total - 1].toInt() and 0xFF) == 0xFF && (b[m + total - 2].toInt() and 0xFF) == Crc8.of(payload)) {
                    out += (b[m + prefix.size].toInt() and 0xFF) to payload
                    start = m + total
                } else {
                    start = m + 1
                }
            }
            buf.reset()
            if (start < b.size) buf.write(b, start, b.size - start)
            if (buf.size() > 4096) buf.reset()
            return out
        }

        private fun indexOf(b: ByteArray, p: ByteArray, from: Int): Int {
            var i = from
            while (i <= b.size - p.size) {
                var ok = true
                for (j in p.indices) if (b[i + j] != p[j]) {
                    ok = false
                    break
                }
                if (ok) return i
                i++
            }
            return -1
        }
    }
}

/** Status byte used by cat printers in their A3 reply: bit 0 paper out, bit 2 too hot, bit 3 low battery. */
private fun catStatus(s: Int, old: PrinterInfo?): PrinterInfo = (old ?: PrinterInfo.unknown()).copy(
    paperOut = s and 0x01 != 0,
    hot = s and 0x04 != 0,
    lowVoltage = s and 0x08 != 0,
)

// ============================================================ Funny Print / LX-D

/**
 * YTETCN / Funny Print family (LX-D01..D09, Dolewa). Verified on an LX-D02.
 *  - service FFE6, write FFE1 (write without response), notify FFE2
 *  - 12-byte commands starting 0x5A, 100-byte data packets starting 0x55, app-check handshake (5A 0A / 5A 0B)
 */
class FunnyLxDriver(type: PrinterType, address: String, scope: CoroutineScope, info: MutableStateFlow<PrinterInfo?>, log: (String) -> Unit) :
    PrinterDriver(type, address, scope, info, log) {

    private val cmdPace = Pace(512, 0)

    override fun onNotify(b: ByteArray) {
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
        super.onNotify(b)
    }

    override suspend fun onConnected() {
        delay(400)
        refreshStatus()
    }

    /** The hardware-info request also makes it send a status packet. */
    override suspend fun refreshStatus() {
        link.send(cmd(0x01), cmdPace)
    }

    private suspend fun waitOp(vararg ops: Int, ms: Long) = waitFor(ms) { it.size >= 2 && (it[1].toInt() and 0xFF) in ops }

    private suspend fun runHandshake() {
        val macs = linkedSetOf(address.replace(":", "").uppercase())
        link.send(cmd(0x01), cmdPace)
        val reply = waitOp(0x01, 0x02, ms = 1000)
        if (reply != null && reply.size >= 8) {
            macs.add(hex(reply, 1, 7).replace(" ", ""))
            macs.add(hex(reply, 2, 8).replace(" ", ""))
        }
        for (mac in macs) {
            val rnd = IntArray(10) { Random.nextInt(1, 255) }
            val (low, high) = challenge(rnd, mac)
            link.send(cmd(0x0A, *rnd), cmdPace)
            val r = waitOp(0x0A, ms = 1500)
            if (r == null) {
                log("handshake: no reply, continuing without it")
                return
            }
            if (hex(r, 2, 12).replace(" ", "") != low) {
                log("handshake: key mismatch for $mac")
                continue
            }
            link.send(cmd(0x0B, *hexToBytes(high).map { it.toInt() and 0xFF }.toIntArray()), cmdPace)
            val ok = waitOp(0x0B, ms = 1500)
            log(if (ok != null && ok.size > 2 && ok[2].toInt() == 1) "handshake OK" else "handshake: app check rejected")
            return
        }
        log("handshake: no matching key, trying to print anyway")
    }

    override suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        drain()
        if (job.handshake) runHandshake()

        link.send(cmd(0x0C, (job.darkness - 1).coerceIn(0, 2)), cmdPace)
        delay(50)

        val padded = job.bits.copyOf(((job.bits.size + PKT_DATA - 1) / PKT_DATA) * PKT_DATA)
        val total = padded.size / PKT_DATA
        // Funny Print: byte 4 = 0 starts a print, 1 ends/cancels it. Byte 5 (copies) is 0 for this printer family.
        link.send(cmd(0x04, total shr 8, total and 0xFF, 0, 0), cmdPace)
        waitOp(0x04, ms = 300)

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
            if (!link.connected) throw IOException("Printer disconnected")
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
                link.send(p, cmdPace)
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
                link.send(cmd(0x04, total shr 8, total and 0xFF, 1, 0), cmdPace)
            } catch (_: IOException) {
            }
        }
        log("print finished")
    }

    companion object {
        private const val PKT_DATA = 96
        private const val PKT_SIZE = 100
        private const val PKT_DELAY_MS = 8L

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
    }
}

// ============================================================ classic cat printers (51 78)

/**
 * Classic "cat printers" (GB01..GB05, GT01, X5/X6/X7, Tiny Print / iPrint apps).
 * Job layout from TiMini-Print protocol/_builders.py (_build_job_model_from_raster_set) and families/tiny/core.py.
 */
class TinyDriver(type: PrinterType, address: String, scope: CoroutineScope, info: MutableStateFlow<PrinterInfo?>, log: (String) -> Unit) :
    PrinterDriver(type, address, scope, info, log) {

    private val decoder = Frames.Decoder(Frames.CAT)

    // TiMini-Print devices/bluetooth_profiles.py: 512-byte cap, default 50 ms between writes, flow controlled.
    private val pace = Pace(512, 50, flowControlled = true)

    override fun onNotify(b: ByteArray) {
        for ((op, payload) in decoder.feed(b)) {
            when (op) {
                0xAE -> if (payload.size == 1) {
                    if (payload[0].toInt() == 0x10) link.paused = true
                    if (payload[0].toInt() == 0x00) link.paused = false
                }
                0xA3 -> if (payload.isNotEmpty()) info.value = catStatus(payload[0].toInt() and 0xFF, info.value)
            }
        }
        super.onNotify(b)
    }

    override suspend fun onConnected() {
        refreshStatus()
    }

    override suspend fun refreshStatus() {
        link.send(Frames.cat(0xA3, 0x00), Pace(512, 0))
    }

    private fun paperFeedCmd(): ByteArray =
        if (type.dpi == 300) Frames.cat(0xA1, 0x48, 0x00) else Frames.cat(0xA1, 0x30, 0x00)

    /** Builds the whole job; public so it can be checked against TiMini-Print's output. */
    fun build(job: RasterJob): ByteArray {
        val out = ByteArrayOutputStream()
        val speed = type.speed ?: 30
        val blackening = type.blackeningFor(job.darkness)
        val energy = type.energyFor(job.darkness)
        val lsb = job.lsbBits()
        out.write(Frames.cat(0xA4, 0x30 + blackening.coerceIn(1, 5)))
        if (energy > 0) out.write(Frames.cat(0xAF, energy and 0xFF, (energy shr 8) and 0xFF))
        out.write(Frames.cat(0xBE, 0x00))
        out.write(Frames.cat(0xBD, speed and 0xFF))
        writeLines(out, job, lsb, speed)
        if (type.variant == "line_eight") {
            val dots = if (job.trim) 0 else maxOf(0, type.postFeed) * (if (type.dpi == 300) 72 else 48)
            out.write(Frames.cat(0xA1, dots and 0xFF, (dots shr 8) and 0xFF, 0x11))
        } else {
            out.write(Frames.cat(0xBD, FEED_PADDING))
            if (!job.trim) repeat(maxOf(0, type.postFeed)) { out.write(paperFeedCmd()) }
            out.write(Frames.cat(0xBD, FEED_PADDING))
        }
        out.write(Frames.cat(0xA3, 0x00))
        return out.toByteArray()
    }

    private fun writeLines(out: ByteArrayOutputStream, job: RasterJob, lsb: ByteArray, speed: Int) {
        val rb = job.rowBytes
        for (y in 0 until job.height) {
            val raw = lsb.copyOfRange(y * rb, (y + 1) * rb)
            val rle = if (type.rle) rleLine(job, y) else null
            if (rle != null && rle.size <= rb) out.write(Frames.make(Frames.CAT, 0xBF, rle))
            else out.write(Frames.make(Frames.CAT, 0xA2, raw))
            if ((y + 1) % 200 == 0) out.write(Frames.cat(0xBD, speed and 0xFF))
        }
    }

    /** Run-length line: each byte is (color << 7) | count, count up to 127 (TiMini-Print protocol/encoding.py). */
    private fun rleLine(job: RasterJob, y: Int): ByteArray {
        val w = job.width
        val base = y * job.rowBytes
        fun px(x: Int) = (job.bits[base + (x shr 3)].toInt() shr (7 - (x and 7))) and 1
        val out = ByteArrayOutputStream()
        fun run(color: Int, count: Int) {
            var c = count
            while (c > 127) {
                out.write((color shl 7) or 127)
                c -= 127
            }
            if (c > 0) out.write((color shl 7) or c)
        }
        var prev = px(0)
        var count = 1
        var hasBlack = prev == 1
        for (x in 1 until w) {
            val p = px(x)
            if (p == 1) hasBlack = true
            if (p == prev) count++ else {
                run(prev, count)
                prev = p
                count = 1
            }
        }
        if (hasBlack) run(prev, count)
        if (out.size() == 0) run(prev, count)
        return out.toByteArray()
    }

    override suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        drain()
        link.paused = false
        val data = build(job)
        log("sending ${data.size} bytes")
        link.send(data, pace) { sent -> onProgress(sent, data.size) }
        // Give the printer time to finish feeding before anything else is sent.
        delay(500)
    }

    companion object {
        private const val FEED_PADDING = 12
    }
}

// ============================================================ newer cat printers (V5G: MX05/MX06/MX10/YT01…)

/** Fun Print / iBleem "V5G" printers. Job layout from TiMini-Print protocol/families/v5g.py. */
class V5gDriver(type: PrinterType, address: String, scope: CoroutineScope, info: MutableStateFlow<PrinterInfo?>, log: (String) -> Unit) :
    PrinterDriver(type, address, scope, info, log) {

    private val decoder = Frames.Decoder(Frames.CAT)
    private val pace = Pace(56 * 8, 30, flowControlled = true, reserve = 5)

    override fun onNotify(b: ByteArray) {
        for ((op, payload) in decoder.feed(b)) {
            when (op) {
                0xAE -> if (payload.size == 1) {
                    if (payload[0].toInt() == 0x10) link.paused = true
                    if (payload[0].toInt() == 0x00) link.paused = false
                }
                0xA3 -> if (payload.isNotEmpty()) info.value = catStatus(payload[0].toInt() and 0xFF, info.value)
            }
        }
        super.onNotify(b)
    }

    override suspend fun onConnected() {
        link.send(hexBytes("5178A30001000000FF"), Pace(512, 0))
        delay(150)
        link.send(hexBytes("5178D30001000000FF"), Pace(512, 0))
    }

    override suspend fun refreshStatus() {
        link.send(hexBytes("5178A30001000000FF"), Pace(512, 0))
    }

    fun build(job: RasterJob): ByteArray {
        val out = ByteArrayOutputStream()
        val lsb = job.lsbBits()
        type.densityFor(job.darkness)?.let { d -> out.write(Frames.cat(0xF2, 0x01, d.coerceIn(1, 200))) }
        out.write(Frames.cat(0xA3, 0x00))
        out.write(Frames.cat(0xA4, 0x30 + type.blackeningFor(job.darkness).coerceIn(1, 5)))
        out.write(Frames.make(Frames.CAT, 0xA6, hexBytes("AA551738445F5F5F44382C")))
        val energy = type.energyFor(job.darkness)
        if (energy > 0) out.write(Frames.cat(0xAF, energy and 0xFF, (energy shr 8) and 0xFF))
        out.write(Frames.cat(0xBE, 0x00))
        out.write(Frames.cat(0xBD, 0x0A))
        val rb = job.rowBytes
        for (y in 0 until job.height) out.write(Frames.make(Frames.CAT, 0xA2, lsb.copyOfRange(y * rb, (y + 1) * rb)))
        out.write(Frames.cat(0xBD, 0x19))
        if (!job.trim) {
            if (type.key in BLANK_LINE_FEED) {
                // MX05/MX06/MX08/MX09/MX10 misbehave on the paper-feed command; feed with blank lines instead
                // (the workaround NaitLee/Cat-Printer uses for these models, printer_lib/models.py problem_feeding).
                val blank = Frames.make(Frames.CAT, 0xA2, ByteArray(rb))
                repeat(maxOf(0, type.postFeed) * (if (type.dpi == 300) 72 else 48)) { out.write(blank) }
            } else {
                repeat(maxOf(0, type.postFeed)) {
                    out.write(if (type.dpi == 300) Frames.cat(0xA1, 0x48, 0x00) else Frames.cat(0xA1, 0x30, 0x00))
                }
            }
        }
        out.write(Frames.make(Frames.CAT, 0xA6, hexBytes("AA55170000000000000017")))
        out.write(Frames.cat(0xA3, 0x00))
        out.write(Frames.cat(0xA3, 0x00))
        return out.toByteArray()
    }

    override suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        drain()
        link.paused = false
        val data = build(job)
        log("sending ${data.size} bytes")
        link.send(data, pace) { sent -> onProgress(sent, data.size) }
        delay(500)
        info.value?.let { if (it.paperOut) throw IOException("Printer is out of paper") }
    }

    companion object {
        /** Catalog models (MX05/MX06 share "mx05") that need blank lines instead of the feed command. */
        private val BLANK_LINE_FEED = setOf("mx05", "mx08", "mx09", "mx10")
    }
}

// ============================================================ MXW01 generation (22 21 frames, AE03 bulk)

/**
 * MXW01-style printers (also X1, X2, C17, KERUI, JK01, BH03 and MX/BQ units that advertise the "59" marker).
 * From TiMini-Print protocol/families/v5x.py and printing/runtime/v5x.py, including the B3 signing reply.
 */
class V5xDriver(type: PrinterType, address: String, scope: CoroutineScope, info: MutableStateFlow<PrinterInfo?>, log: (String) -> Unit) :
    PrinterDriver(type, address, scope, info, log) {

    private val control = Pace(512, 0)
    private val bulkPace = Pace(180, 30, flowControlled = true, reserve = 5)
    @Volatile private var temperature = 0
    @Volatile private var headType = "gaoya"
    @Volatile private var taskState = 0
    @Volatile private var lastStatusAt = 0L
    private var lastDensity: Int? = null

    private fun payloadOf(b: ByteArray): ByteArray? {
        if (b.size < 6 || b[0] != Frames.V5X[0] || b[1] != Frames.V5X[1]) return null
        val len = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8)
        if (b.size < 6 + len) return null
        return b.copyOfRange(6, 6 + len)
    }

    override fun onNotify(b: ByteArray) {
        val h = hex(b).replace(" ", "")
        if (h in PAUSE) link.paused = true
        else if (h in RESUME) link.paused = false
        else if (b.size >= 3 && b[0] == Frames.V5X[0] && b[1] == Frames.V5X[1]) {
            val raw = payloadOf(b)
            when (b[2].toInt() and 0xFF) {
                0xA1 -> if (raw != null && raw.size >= 8) {
                    taskState = raw[0].toInt() and 0xFF
                    temperature = raw[4].toInt() and 0xFF
                    lastStatusAt = SystemClock.elapsedRealtime()
                    val err = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
                    info.value = (info.value ?: PrinterInfo.unknown()).copy(
                        battery = raw[3].toInt() and 0xFF,
                        hot = temperature >= 70,
                    )
                    if (err != 0) log("printer error ${"%02X %02X".format(raw[6], raw[7])}")
                }
                0xA6 -> scope.launch { send(hexBytes("2221A70000000000")) }
                0xB2 -> scope.launch {
                    delay(700)
                    send(hexBytes("2221A300020000000000"))
                }
                0xB3 -> if (raw != null && raw.size >= 10) {
                    val reply = signResponse(raw.copyOfRange(0, 10), System.currentTimeMillis())
                    scope.launch { send(reply) }
                    log("signing reply sent")
                }
                0xB0 -> if (raw != null && raw.isNotEmpty()) headType = when (raw[0].toInt() and 0xFF) {
                    0x01 -> "gaoya"
                    0xFF -> "weishibie"
                    else -> "diya"
                }
                0xB1 -> if (raw != null && raw.isNotEmpty()) {
                    val fw = String(raw, Charsets.US_ASCII).trimEnd('\u0000')
                    if (fw.isNotEmpty()) {
                        headType = when (fw.last()) {
                            '2' -> "gaoya"
                            '1' -> "diya"
                            else -> "weishibie"
                        }
                        log("firmware $fw")
                    }
                }
            }
        }
        super.onNotify(b)
    }

    private suspend fun send(b: ByteArray) {
        try {
            link.send(b, control)
        } catch (e: Exception) {
            log("send failed: ${e.message}")
        }
    }

    override suspend fun onConnected() {
        delay(200)
        link.send(hexBytes("2221B10001000000FF"), control)
        waitFor(400) { it.size >= 3 && it[0] == Frames.V5X[0] && (it[2].toInt() and 0xFF) == 0xB1 }
    }

    private fun densityByte(darkness: Int, coverage: Double): Int {
        val user = DOT_DENSITY[type.blackeningFor(darkness) - 1]
        if (temperature <= 60) return user
        val band = when {
            coverage <= 0.4 -> 0
            coverage < 0.5 -> 1
            coverage < 0.7 -> 2
            else -> 3
        }
        val values = if (headType == "gaoya") {
            if (temperature < 65) intArrayOf(48, 15, 15, 10) else if (temperature < 70) intArrayOf(36, 9, 5, 5) else intArrayOf(22, 5, 3, 3)
        } else {
            if (temperature <= 65) intArrayOf(60, 50, 50, 30) else if (temperature <= 70) intArrayOf(50, 40, 40, 20) else intArrayOf(40, 30, 30, 10)
        }
        return minOf(user, values[band]).coerceAtLeast(0)
    }

    override suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        drain()
        link.paused = false
        val raster = job.lsbBits()
        val black = raster.sumOf { Integer.bitCount(it.toInt() and 0xFF) }
        val coverage = black.toDouble() / (raster.size * 8).coerceAtLeast(1)
        val density = densityByte(job.darkness, coverage)
        var densityChanged = false
        if (density != lastDensity) {
            link.send(Frames.make(Frames.V5X, 0xA2, byteArrayOf(density.toByte())), control)
            lastDensity = density
            densityChanged = true
        }
        val startDelay = if (headType == "gaoya" && coverage > 0.4) 200L else if (densityChanged) 60L else 0L
        if (startDelay > 0) delay(startDelay)

        // A9 declares four payload bytes and ends with a literal zero footer, not CRC/FF.
        val h = job.height
        val start = hexBytes("2221A9000400") + byteArrayOf((h and 0xFF).toByte(), ((h shr 8) and 0xFF).toByte(), 0x30, 0x00, 0x00, 0x00)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (true) {
            drain()
            link.send(start, control)
            val reply = waitFor(3_000) { it.size >= 3 && it[0] == Frames.V5X[0] && (it[2].toInt() and 0xFF) == 0xA9 }
                ?: throw IOException("Printer didn't answer the print request")
            val raw = payloadOf(reply)
            val status = if (raw != null && raw.isNotEmpty()) raw[0].toInt() and 0xFF
            else if (reply.size == 6) reply[4].toInt() and 0xFF else -1
            if (status == 0x01) {
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("Printer stayed busy")
                delay(400)
                continue
            }
            if (status != 0x00) throw IOException("Printer refused the print (status ${"%02X".format(status)})")
            break
        }
        log("sending ${raster.size} bytes of image data")
        link.send(raster, bulkPace, Pipe.Bulk) { sent -> onProgress(sent, raster.size) }
        link.send(hexBytes("2221AD000100000000"), control)

        // The printer keeps printing for a few seconds after the last byte; wait until it reports idle or goes quiet.
        val limit = SystemClock.elapsedRealtime() + 60_000
        lastStatusAt = SystemClock.elapsedRealtime()
        delay(300)
        while (SystemClock.elapsedRealtime() < limit) {
            if (taskState == 0 && SystemClock.elapsedRealtime() - lastStatusAt < 1_000) break
            if (SystemClock.elapsedRealtime() - lastStatusAt > 3_000) break
            delay(100)
        }
    }

    companion object {
        private val DOT_DENSITY = intArrayOf(0x58, 0x5A, 0x5D, 0x5F, 0x62)
        private val PAUSE = setOf("AA01", "5178AE0101001070FF", "2221A800010020E0FF", "2221AE0101001070FF", "2221AE0001000000")
        private val RESUME = setOf("AA00", "5178AE0101000000FF", "2221A80001003090FF", "2221AE0101000000FF", "2221AE0001001000")

        /** HMAC-SHA256 answer to the printer's B3 challenge (TiMini-Print families/v5x.py build_sign_response). */
        fun signResponse(challenge: ByteArray, timestampMs: Long): ByteArray {
            val tail = (timestampMs % 10000).toInt()
            val t0 = tail / 100
            val t1 = tail % 100
            val mask = intArrayOf(0xA9, t1, 0xD3, 0x03, 0x78, 0xB6, 0x15, t0, 0xEA, 0x82)
            val message = challenge.indices.joinToString("") { "%02X".format((challenge[it].toInt() xor mask[it]) and 0xFF) }
                .toByteArray(Charsets.US_ASCII)
            val key = "93%02xe8ae5e93d79683dcaf9e%02x3ede35".format(t0, t1).toByteArray(Charsets.US_ASCII)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val d = mac.doFinal(message)
            return hexBytes("2221B3002200") +
                d.copyOfRange(0, 1) + byteArrayOf(t1.toByte()) + d.copyOfRange(1, 29) + byteArrayOf(t0.toByte()) + d.copyOfRange(29, 32) +
                hexBytes("00FF")
        }
    }
}

// ============================================================ Phomemo (M02, M02S, M02 Pro, M02X, T02 and clones)

/**
 * Phomemo "compact" printers. Job from TiMini-Print protocol/families/phomemo_esc/compact.py; status replies
 * (1A-prefixed) from printing/runtime/phomemo.py.
 */
class PhomemoDriver(type: PrinterType, address: String, scope: CoroutineScope, info: MutableStateFlow<PrinterInfo?>, log: (String) -> Unit) :
    PrinterDriver(type, address, scope, info, log) {

    @Volatile private var finished = false
    @Volatile private var fault: String? = null
    private val pending = ByteArrayOutputStream()

    private val pace = Pace(128, 20)

    override fun onNotify(b: ByteArray) {
        synchronized(pending) {
            pending.write(b)
            val buf = pending.toByteArray()
            var i = 0
            while (i < buf.size) {
                if (buf[i].toInt() != 0x1A) {
                    i++
                    continue
                }
                if (i + 2 >= buf.size) break
                val op = buf[i + 1].toInt() and 0xFF
                val size = SIZES[op]
                if (size == null) {
                    i += 2
                    continue
                }
                if (i + 1 + size > buf.size) break
                handle(op, buf[i + 2].toInt() and 0xFF)
                i += 1 + size
            }
            pending.reset()
            if (i < buf.size) pending.write(buf, i, buf.size - i)
        }
        super.onNotify(b)
    }

    private fun handle(op: Int, v: Int) {
        val old = info.value ?: PrinterInfo.unknown()
        when (op) {
            0x03 -> if (v == 0xA8 || v == 0xA9) info.value = old.copy(hot = v == 0xA9)
            0x04 -> info.value = old.copy(
                battery = when (v) { 0xA1 -> 10; 0xA2 -> 5; 0xA3 -> 3; else -> v },
                batteryPercent = true,
                lowVoltage = v in 0xA1..0xA3,
            )
            0x05 -> if (v == 0x99) fault = "the cover is open"
            0x06 -> {
                info.value = old.copy(paperOut = v == 0x88)
                if (v == 0x88) fault = "it's out of paper"
            }
            0x35 -> info.value = old.copy(charging = v == 0x02)
            0x0F -> if (v == 0x0C) finished = true else fault = "the print failed (%02X)".format(v)
        }
    }

    override suspend fun onConnected() {
        refreshStatus()
    }

    override suspend fun refreshStatus() {
        for (q in listOf(0x11, 0x0E, 0x12)) {
            link.send(byteArrayOf(0x1F, 0x11, q.toByte()), Pace(128, 0))
            delay(100)
        }
    }

    private val contentWidth: Int get() = if (type.variant == "m02s" || type.variant == "m02_pro") 576 else 384

    fun build(job: RasterJob): ByteArray {
        val out = ByteArrayOutputStream()
        val level = (type.densityFor(job.darkness) ?: 2).coerceIn(1, 4)
        val (density, coefficient) = DENSITY_LEVELS[level - 1]
        out.write(byteArrayOf(0x1B, 0x40))
        out.write(byteArrayOf(0x1F, 0x11, 0x02, density.toByte()))
        out.write(byteArrayOf(0x1F, 0x11, 0x37, coefficient.toByte()))
        out.write(byteArrayOf(0x1F, 0x11, 0x0B))
        out.write(byteArrayOf(0x1F, 0x11, 0x35, 0x00))
        // Four blank lines on top (top_padding = 4), then the image as one GS v 0 block.
        val rb = job.rowBytes
        val h = job.height + 4
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (rb and 0xFF).toByte(), (rb shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte()))
        out.write(ByteArray(rb * 4))
        out.write(job.bits)
        if (!job.trim) {
            out.write(byteArrayOf(0x1B, 0x64, 0x02))
            out.write(byteArrayOf(0x1B, 0x64, 0x02))
        }
        return out.toByteArray()
    }

    override suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        if (job.width > contentWidth) throw IOException("This printer takes at most $contentWidth dots across")
        fault = null
        finished = false
        info.value?.let { if (it.paperOut) throw IOException("Printer is out of paper") }
        val data = build(job)
        log("sending ${data.size} bytes")
        link.send(data, pace) { sent -> onProgress(sent, data.size) }
        // Phomemo estimates the print time from the length (compact.py completion_pitch_mm).
        val pitch = if (type.dpi >= 300) 0.0847 else 0.125
        val waitMs = (job.height * pitch * 0.110 * 1000).toLong().coerceIn(500, 30_000)
        val until = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < until && !finished) {
            fault?.let { throw IOException("Printer reported $it") }
            delay(100)
        }
        fault?.let { throw IOException("Printer reported $it") }
    }

    companion object {
        private val DENSITY_LEVELS = listOf(1 to 100, 2 to 100, 4 to 100, 4 to 150)
        private val SIZES = mapOf(
            0x03 to 2, 0x04 to 2, 0x05 to 2, 0x06 to 2, 0x07 to 4, 0x08 to 16,
            0x09 to 2, 0x0B to 2, 0x0E to 2, 0x0F to 2, 0x16 to 2, 0x17 to 2,
            0x1D to 2, 0x20 to 2, 0x35 to 2, 0x3B to 6,
        )
    }
}

// ============================================================ generic ESC/POS receipt printers

/** Plain ESC/POS raster printing (GS v 0), for 58 mm and 80 mm receipt printers. Token Printer's own code. */
class EscPosDriver(type: PrinterType, address: String, scope: CoroutineScope, info: MutableStateFlow<PrinterInfo?>, log: (String) -> Unit) :
    PrinterDriver(type, address, scope, info, log) {

    private val pace: Pace get() = if (link.isBle) Pace(100, 20) else Pace(512, 10)

    fun build(job: RasterJob): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40))
        val rb = job.rowBytes
        var y = 0
        while (y < job.height) {
            val n = minOf(BLOCK_ROWS, job.height - y)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (rb and 0xFF).toByte(), (rb shr 8).toByte(), (n and 0xFF).toByte(), (n shr 8).toByte()))
            out.write(job.bits, y * rb, n * rb)
            y += n
        }
        out.write(byteArrayOf(0x1B, 0x64, (if (job.trim) 1 else 4).toByte()))
        return out.toByteArray()
    }

    override suspend fun print(job: RasterJob, onProgress: (Int, Int) -> Unit) {
        val data = build(job)
        log("sending ${data.size} bytes")
        link.send(data, pace) { sent -> onProgress(sent, data.size) }
        delay(800)
    }

    companion object {
        private const val BLOCK_ROWS = 128
    }
}

internal fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
