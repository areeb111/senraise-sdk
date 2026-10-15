package com.esite.senraise.examples

import android.content.Context
import android.graphics.Bitmap
import com.esite.senraise.PaperWidth
import com.esite.senraise.PrintResult
import com.esite.senraise.SenraisePrinter
import com.esite.senraise.receipt.ReceiptRenderer
import com.esite.senraise.receipt.receipt

/**
 * **3 — Printing a bitmap you drew yourself.**
 *
 * The receipt DSL is a convenience, not a gate. If the layout is already drawn —
 * by a Compose or View hierarchy captured to a bitmap, by a PDF page rasterised
 * elsewhere, by your own Canvas code — hand the bitmap straight over.
 *
 * Two rules. The width must be the paper's width in dots ([PaperWidth.dots]):
 * one source pixel is one dot, so anything wider is clipped at the right edge
 * and anything narrower leaves the rest of the line blank. And a very tall
 * bitmap must be split: the whole image crosses the Binder boundary in one
 * transaction, against a buffer of about 1MB, which at 384 dots is roughly 650
 * rows of ARGB_8888 before `TransactionTooLargeException`.
 */
suspend fun printPreRendered(context: Context, page: Bitmap): PrintResult {
    val printer = SenraisePrinter(context)
    return printer.printBitmap(page, feedLines = 3)
}

/**
 * A long receipt, printed in slices inside one bound session.
 *
 * Binding once and printing several bitmaps keeps the paper continuous — there
 * is no feed between slices, so the seam is invisible — and avoids paying the
 * bind cost per slice.
 */
suspend fun printLongReceipt(context: Context, page: Bitmap): PrintResult {
    val printer = SenraisePrinter(context)
    val slices = page.sliceRows(maxRows = 512)
    return try {
        printer.print {
            slices.forEach { bitmap(it) }
            feed(3)
        }
    } finally {
        slices.forEach { it.recycle() }
    }
}

/** Horizontal cuts only: every slice keeps the full paper width. */
private fun Bitmap.sliceRows(maxRows: Int): List<Bitmap> {
    if (height <= maxRows) return listOf(this)
    val slices = mutableListOf<Bitmap>()
    var top = 0
    while (top < height) {
        val rows = minOf(maxRows, height - top)
        slices += Bitmap.createBitmap(this, 0, top, width, rows)
        top += rows
    }
    return slices
}

/**
 * The renderer on its own, with no printing.
 *
 * Useful for a print preview — the bitmap it returns is exactly what the head
 * will put on paper, so a preview built from it cannot drift from the output.
 */
fun renderPreview(renderer: ReceiptRenderer = ReceiptRenderer()): Bitmap {
    val doc = receipt {
        title("Preview")
        row("Item", "1,000")
        rule()
        code("PREVIEW-1")
    }
    return renderer.render(doc, PaperWidth.MM58, rtl = false)
}
