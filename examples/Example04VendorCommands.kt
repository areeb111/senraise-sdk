package com.esite.senraise.examples

import android.content.Context
import com.esite.senraise.Alignment
import com.esite.senraise.PrintResult
import com.esite.senraise.QrErrorLevel
import com.esite.senraise.SenraisePrinter
import com.esite.senraise.Symbology

/**
 * **4 — The vendor's own text, table, barcode and QR calls.**
 *
 * No rasterising: the service's built-in font does the work. Faster, a fraction
 * of the memory, and the right choice for a Latin-only ticket or a kitchen chit.
 *
 * **It cannot print Arabic.** The vendor font renders a string codepoint by
 * codepoint, so Arabic arrives unjoined, in visual order, and often as tofu.
 * There is no flag for it — use the renderer and print a bitmap instead.
 *
 * Commands are stateful in print order, as on any ESC/POS head: alignment, size
 * and weight apply to everything after them. Nothing resets between prints,
 * because the service is shared with whatever else on the device also prints, so
 * set what you depend on rather than inheriting it.
 */
suspend fun printKitchenChit(context: Context, orderNumber: String): PrintResult {
    val printer = SenraisePrinter(context)
    return printer.print {
        align(Alignment.CENTER)
        textSize(40f)
        bold(true)
        text("ORDER $orderNumber\n")

        bold(false)
        textSize(24f)
        align(Alignment.LEFT)
        feed(1)

        // Columns are relative weights, so they reflow with the paper width.
        val weights = listOf(4, 1, 2)
        val alignments = listOf(Alignment.LEFT, Alignment.CENTER, Alignment.RIGHT)
        table(listOf("Item", "Qty", "Price"), weights, alignments)
        table(listOf("Arabic coffee", "1", "3,000"), weights, alignments)
        table(listOf("Mint tea", "2", "3,000"), weights, alignments)

        feed(1)
        align(Alignment.CENTER)

        // The service does not validate payloads: a string that does not fit the
        // symbology prints blank or truncated rather than failing, so validate
        // first. CODE128 takes the widest character set.
        barcode(orderNumber, Symbology.CODE128, heightDots = 80, width = 2)
        feed(1)

        // moduleDots is the module size in printer dots. Below 3 a module lands
        // on too few dots to survive the paper; 5–6 reads comfortably across a
        // counter. A lower error level keeps the symbol in a smaller version,
        // which means physically larger modules at the same printed size — on
        // thermal paper that beats extra redundancy.
        qrCode(orderNumber, moduleDots = 6, errorLevel = QrErrorLevel.M)

        // Always feed at the end: the last printed line sits under the head, not
        // past the tear-off strip, so without this the tear cuts the QR in half.
        feed(3)
    }
}
