package com.esite.senraise.examples

import android.content.Context
import com.esite.senraise.PaperWidth
import com.esite.senraise.PrintResult
import com.esite.senraise.SenraisePrinter
import com.esite.senraise.escpos.EscPosRaster
import com.esite.senraise.receipt.ReceiptRenderer
import com.esite.senraise.receipt.receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **5 — Raw ESC/POS, and the raw binder.**
 *
 * `printBitmap` already does what the raster path below does, in one call. Reach
 * for this only when you actually need the bytes:
 *
 *  * the same output has to go to a Bluetooth or serial printer elsewhere in the
 *    app and must be byte-identical;
 *  * a command the vendor interface does not expose — a cutter, a cash drawer
 *    kick, a density setting;
 *  * a whole receipt assembled in one buffer and sent in one transaction.
 *
 * Raw bytes are the one path that can strand the printer's parser in a state the
 * next caller does not expect, so bracket them with a reset.
 */
suspend fun printRasterBytes(
    context: Context,
    renderer: ReceiptRenderer = ReceiptRenderer(),
): PrintResult {
    val printer = SenraisePrinter(context)

    val doc = receipt {
        title("ESC/POS raster")
        body("Rendered on Android, sent as GS v 0.")
        rule()
        row("Threshold", EscPosRaster.INK_THRESHOLD.toString())
    }

    val bytes = withContext(Dispatchers.Default) {
        val page = renderer.render(doc, PaperWidth.MM58, rtl = false)
        try {
            // GS v 0 — raster mode, not the older ESC * column mode: the printer
            // handles the line feeds, which is faster and immune to the drifting
            // vertical gaps column mode produces when a line is sent a dot-row
            // short.
            EscPosRaster.raster(page)
        } finally {
            page.recycle()
        }
    }

    return printer.print {
        escPos(EscPosRaster.RESET)
        escPos(bytes)
        feed(3)
    }
}

/**
 * A cash-drawer kick: a command with no wrapper on this interface at all.
 *
 * `ESC p m t1 t2` — pin 2, 100ms on, 200ms off, which is what most drawers
 * wired to a Senraise terminal expect.
 */
suspend fun kickCashDrawer(context: Context): PrintResult {
    val printer = SenraisePrinter(context)
    return printer.print {
        escPos(byteArrayOf(0x1B, 0x70, 0x00, 0x32, 0x64))
    }
}

/**
 * The binder itself, for a vendor build whose service has a method this SDK does
 * not know about.
 *
 * [com.esite.senraise.SenraiseSession.raw] is the generated AIDL interface, so
 * everything the vendor exposes is reachable — the wrappers are a convenience
 * over it, never a restriction. A method missing on an older service throws and
 * arrives as `PrintFailure.IO` rather than crashing the till.
 */
suspend fun printThroughRawBinder(context: Context): PrintResult {
    val printer = SenraisePrinter(context)
    return printer.print {
        raw.setAlignment(1)
        raw.printText("via the raw binder\n")
        raw.nextLine(3)
    }
}
