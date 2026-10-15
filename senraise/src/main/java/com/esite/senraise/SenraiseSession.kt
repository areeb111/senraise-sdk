package com.esite.senraise

import android.graphics.Bitmap
import recieptservice.com.recieptservice.PrinterInterface

/**
 * A bound printer, for the duration of one print.
 *
 * Handed to the block passed to [SenraisePrinter.print]. Every call is
 * **synchronous**: the vendor interface returns when the work is done rather
 * than calling back, so the methods here block until the head has caught up.
 * That is why the block runs on `Dispatchers.IO` and why the session must not be
 * stashed somewhere and used later — once the block returns, the SDK unbinds and
 * the binder is dead.
 *
 * Commands are stateful in print order, as on any ESC/POS head: [align],
 * [textSize] and [bold] affect everything printed after them, and nothing resets
 * between prints because the service is shared with whatever else on the device
 * also prints.
 */
interface SenraiseSession {

    /** The vendor service's version string, or null if it refused to say. */
    val serviceVersion: String?

    /** The raw AIDL binder, for a vendor call this SDK does not wrap. */
    val raw: PrinterInterface

    /** Applies to text, barcodes and QR codes printed after this call. */
    fun align(alignment: Alignment)

    /** Text size in the service's own units — roughly pixels at 203dpi. */
    fun textSize(size: Float)

    fun bold(bold: Boolean)

    /**
     * Prints text through the service's own font.
     *
     * Fast and cheap, and the right call for a plain Latin line. **Not** the way
     * to print Arabic: the vendor font renders a string codepoint by codepoint,
     * so Arabic comes out unjoined, in visual order, and often as tofu. Render
     * it with [com.esite.senraise.receipt.ReceiptRenderer] and print the result
     * as a [bitmap] instead — Android shapes the text and the head only ever
     * sees dots.
     */
    fun text(text: String)

    /**
     * Prints a bitmap at the head's native resolution.
     *
     * One source pixel is one dot, so the bitmap's width should be the paper's
     * width in dots ([PaperWidth.dots]); anything wider is clipped at the right
     * edge, anything narrower leaves the rest of the line blank. The service
     * binarises internally — there is no threshold to set here — which is why
     * the renderer in this SDK draws pure black on pure white and never a grey.
     *
     * A bitmap crosses the Binder boundary whole, and the buffer is about 1MB
     * for the whole transaction. At 384 dots that is roughly 650 lines of
     * ARGB_8888 before `TransactionTooLargeException`; a long receipt should be
     * printed in a few calls rather than one tall image.
     */
    fun bitmap(bitmap: Bitmap)

    /**
     * A QR code drawn by the service.
     *
     * [moduleDots] is the size of one module in printer dots — below 3 a module
     * lands on too few dots to survive the paper, and 5–6 is what a phone camera
     * reads comfortably across a counter.
     */
    fun qrCode(
        payload: String,
        moduleDots: Int = DEFAULT_QR_MODULE_DOTS,
        errorLevel: QrErrorLevel = QrErrorLevel.M,
    )

    /**
     * A 1D barcode drawn by the service.
     *
     * [heightDots] is the bar height; [width] is the narrow-bar multiplier, 2 or
     * 3 on 58mm paper. See [Symbology] on payload validation, which the service
     * does not do.
     */
    fun barcode(
        data: String,
        symbology: Symbology,
        heightDots: Int = DEFAULT_BARCODE_HEIGHT_DOTS,
        width: Int = DEFAULT_BARCODE_WIDTH,
    )

    /**
     * One row of columns, laid out by the service.
     *
     * [weights] are relative column widths and [alignments] the per-column
     * alignment; all three lists must be the same length. Latin only, for the
     * reason given on [text].
     */
    fun table(
        cells: List<String>,
        weights: List<Int>,
        alignments: List<Alignment>,
    )

    /**
     * Feeds [lines] blank lines.
     *
     * Always needed at the end of a receipt: the last printed line sits under the
     * head, not past the tear-off strip, so without a feed the tear cuts through
     * whatever printed last — usually the QR code. Three lines clears it.
     */
    fun feed(lines: Int = 1)

    /**
     * Writes raw ESC/POS bytes straight through to the head.
     *
     * The escape hatch for a command the vendor interface does not expose. The
     * service passes the bytes on without interpreting them, so this is also the
     * one call that can leave the printer's parser in a state the next caller
     * does not expect — end whatever you send with a reset (`ESC @`).
     */
    fun escPos(data: ByteArray)

    companion object {
        const val DEFAULT_QR_MODULE_DOTS = 6
        const val DEFAULT_BARCODE_HEIGHT_DOTS = 80
        const val DEFAULT_BARCODE_WIDTH = 2
    }
}

/** The one implementation: a thin, validating adapter over the vendor binder. */
internal class BoundSession(override val raw: PrinterInterface) : SenraiseSession {

    override val serviceVersion: String?
        get() = runCatching { raw.serviceVersion }.getOrNull()

    override fun align(alignment: Alignment) = raw.setAlignment(alignment.code)

    override fun textSize(size: Float) = raw.setTextSize(size)

    override fun bold(bold: Boolean) = raw.setTextBold(bold)

    override fun text(text: String) = raw.printText(text)

    override fun bitmap(bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "bitmap is recycled" }
        raw.printBitmap(bitmap)
    }

    override fun qrCode(payload: String, moduleDots: Int, errorLevel: QrErrorLevel) {
        require(payload.isNotEmpty()) { "QR payload is empty" }
        require(moduleDots > 0) { "moduleDots must be positive" }
        raw.printQRCode(payload, moduleDots, errorLevel.code)
    }

    override fun barcode(data: String, symbology: Symbology, heightDots: Int, width: Int) {
        require(data.isNotEmpty()) { "barcode payload is empty" }
        raw.printBarCode(data, symbology.code, heightDots, width)
    }

    override fun table(cells: List<String>, weights: List<Int>, alignments: List<Alignment>) {
        require(cells.size == weights.size && cells.size == alignments.size) {
            "cells, weights and alignments must be the same length: " +
                "${cells.size}, ${weights.size}, ${alignments.size}"
        }
        if (cells.isEmpty()) return
        raw.printTableText(
            cells.toTypedArray(),
            weights.toIntArray(),
            IntArray(alignments.size) { alignments[it].code },
        )
    }

    override fun feed(lines: Int) {
        if (lines <= 0) return
        raw.nextLine(lines)
    }

    override fun escPos(data: ByteArray) {
        if (data.isEmpty()) return
        raw.printEpson(data)
    }
}
