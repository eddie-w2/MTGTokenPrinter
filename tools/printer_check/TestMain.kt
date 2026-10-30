package com.example.tokenprinter

import android.content.Context
import kotlinx.coroutines.TestScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

fun testRaster(w: Int, h: Int): ByteArray {
    val rb = w / 8
    val b = ByteArray(rb * h)
    for (y in 0 until h) for (x in 0 until w) {
        val black = ((x / 7 + y / 5) % 3 == 0) || (x < 20 && y < 10) || (y == h - 1)
        if (black) b[y * rb + (x shr 3)] = (b[y * rb + (x shr 3)].toInt() or (0x80 shr (x and 7))).toByte()
    }
    return b
}

fun main(args: Array<String>) {
    val cat = PrinterCatalog.get(Context(File(args[0])))
    println("types: ${cat.types.size}")
    val mfr59 = listOf(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x59))
    for ((n, m) in listOf("X6" to emptyList(), "X6 Printer" to emptyList(), "MX06" to emptyList(), "MX06" to mfr59, "GB01" to emptyList(),
        "gb03-1234" to emptyList(), "MXW01" to emptyList(), "LX-D02" to emptyList(), "AH-M2" to emptyList(), "M02 Pro" to emptyList(),
        "Mr.in_M02S" to emptyList(), "T02" to emptyList(), "YT01" to emptyList(), "Phone" to emptyList(), "M110" to emptyList())) {
        println("detect '$n' mfr=${m.size}: " + cat.detect(n, "AA:BB:CC:DD:EE:FF", m).joinToString { "${it.key}/${it.family}/${it.widthDots}" })
    }
    val info = MutableStateFlow<PrinterInfo?>(null)
    val out = File(args[1]); out.mkdirs()
    fun job(w: Int, h: Int, dark: Int, trim: Boolean = false) = RasterJob(testRaster(w, h), w, dark, false, trim)
    for (key in listOf("gb01", "x6", "mx02", "dl_x2pro", "seznikneo")) {
        val t = cat.byKey(key)!!
        for (dark in 1..3) {
            val bytes = when (t.family) {
                PrinterFamily.TINY -> TinyDriver(t, "", TestScope, info) {}.build(job(t.widthDots, 30, dark))
                PrinterFamily.V5G -> V5gDriver(t, "", TestScope, info) {}.build(job(t.widthDots, 30, dark))
                else -> error("x")
            }
            File(out, "$key-$dark.hex").writeText(bytes.joinToString("") { "%02x".format(it) })
        }
    }
    for (key in listOf("phomemo_m02", "phomemo_m02s")) {
        val t = cat.byKey(key)!!
        val bytes = PhomemoDriver(t, "", TestScope, info) {}.build(job(t.widthDots, 30, 2))
        File(out, "$key-2.hex").writeText(bytes.joinToString("") { "%02x".format(it) })
    }
    val ch = ByteArray(10) { (it * 17 + 3).toByte() }
    File(out, "sign.hex").writeText(V5xDriver.signResponse(ch, 1730290000123L).joinToString("") { "%02x".format(it) })
}
