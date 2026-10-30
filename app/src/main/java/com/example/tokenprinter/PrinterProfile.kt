// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

package com.example.tokenprinter

/**
 * Everything the image pipeline needs to know about a printer model. Built from the connected (or last used)
 * [PrinterType]; the raster, previews and test page follow it.
 */
data class PrinterProfile(
    val id: String,
    val label: String,
    /** Dots across the paper. Must be a multiple of 8. */
    val widthDots: Int,
    /** Raster rows the printer wants per data packet; the image height is padded to a multiple of this. */
    val rowsPerPacket: Int,
    /** Blank rows after the image so it clears the tear bar (printers that feed by themselves need none). */
    val feedRows: Int,
    val feedRowsTrimmed: Int,
    val family: PrinterFamily,
) {
    val rowBytes: Int get() = widthDots / 8
}

object Profiles {
    /** The printer Token Printer was built on, and the default until another one connects. */
    val LX_D02 = PrinterProfile(
        id = "lx-d02",
        label = "LX-D02",
        widthDots = 384,
        rowsPerPacket = 2,
        feedRows = 80,
        feedRowsTrimmed = 16,
        family = PrinterFamily.FUNNY_LX,
    )

    fun forType(t: PrinterType, shownName: String? = null): PrinterProfile = when (t.family) {
        // Same settings key as before 0.9 so saved darkness carries over.
        PrinterFamily.FUNNY_LX -> LX_D02.copy(label = shownName ?: t.name)
        else -> PrinterProfile(
            id = t.key,
            label = shownName ?: t.name,
            widthDots = t.widthDots,
            rowsPerPacket = 1,
            feedRows = 0,
            feedRowsTrimmed = 0,
            family = t.family,
        )
    }

    /** Looks up a saved profile id ("lx-d02" or a catalog model key). */
    fun byId(id: String?, catalog: PrinterCatalog): PrinterProfile {
        if (id == null || id == LX_D02.id) return LX_D02
        return catalog.byKey(id)?.let { forType(it) } ?: LX_D02
    }
}
