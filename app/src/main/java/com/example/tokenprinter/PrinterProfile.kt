package com.example.tokenprinter

/**
 * Everything the image pipeline needs to know about a printer model.
 * Add a new entry to [Profiles.all] for another printer; the raster, previews and test page follow it.
 * (The Bluetooth protocol itself is still the YTETCN one, see [protocol].)
 */
data class PrinterProfile(
    val id: String,
    val label: String,
    /** Dots across the paper. Must be a multiple of 8. */
    val widthDots: Int,
    /** Raster rows the printer wants per data packet; the image height is padded to a multiple of this. */
    val rowsPerPacket: Int,
    /** Blank rows after the image so it clears the tear bar. */
    val feedRows: Int,
    val feedRowsTrimmed: Int,
    val protocol: String,
) {
    val rowBytes: Int get() = widthDots / 8
}

object Profiles {
    val LX_D02 = PrinterProfile(
        id = "lx-d02",
        label = "YTETCN / Funny Print (LX-D02)",
        widthDots = 384,
        rowsPerPacket = 2,
        feedRows = 80,
        feedRowsTrimmed = 16,
        protocol = "ytetcn",
    )

    val all = listOf(LX_D02)

    fun byId(id: String?): PrinterProfile = all.firstOrNull { it.id == id } ?: LX_D02
}
