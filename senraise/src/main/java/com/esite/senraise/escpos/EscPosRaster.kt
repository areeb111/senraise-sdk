package com.esite.senraise.escpos

import android.graphics.Bitmap

/**
 * Turns a bitmap into the raster command an ESC/POS head understands.
 *
 * Only needed for [com.esite.senraise.SenraiseSession.escPos] — printing through
 * the vendor's `printBitmap` is simpler and should be the default. This is here
 * for the cases where it is not enough: a command the AIDL does not expose,
 * byte-identical output shared with a Bluetooth or serial printer elsewhere in
 * the app, or a receipt built up in one buffer and sent in one transaction.
 *
 * The command is `GS v 0` — the modern raster mode — not the older `ESC *`
 * column mode. `GS v 0` takes the whole image in one go and the printer handles
 * the line feeds, which is both faster and immune to the drifting vertical gaps
 * that column mode produces when a line is sent a dot-row short.
 */
object EscPosRaster {

    /**
     * Luma below this fires a dot.
     *
     * The single biggest lever on how heavy the print looks, and it is
     * deliberately high. Text is drawn antialiased, so every glyph is ringed by
     * partially covered grey pixels; cutting at a mid grey (128–160) throws that
     * ring away and each letter loses its outer pixel — the "thin and broken"
     * look that comes off the paper. At 200 a pixel need only be about a fifth
     * covered to fire a dot, so glyphs keep their drawn weight. That is a
     * deliberate dilation, not an accident.
     *
     * It is not pushed higher: past roughly 220 the ring around *counters* — the
     * holes in ه, م, ق, o, e — starts firing too, and small Arabic fills in and
     * turns to mud.
     */
    const val INK_THRESHOLD = 200

    /** `ESC @`: clears every mode the last caller left set. */
    val RESET: ByteArray = byteArrayOf(0x1B, 0x40)

    /** `GS v 0` has a 16-bit width in bytes, so this is the hard ceiling. */
    private const val MAX_WIDTH_BYTES = 0xFFFF

    /**
     * One `GS v 0` command carrying the whole bitmap.
     *
     * [bitmap] should already be the paper's width in dots; this does not scale.
     * Transparent pixels count as white, so a bitmap drawn without a background
     * prints as its ink and nothing else.
     */
    @JvmOverloads
    fun raster(bitmap: Bitmap, threshold: Int = INK_THRESHOLD): ByteArray {
        require(!bitmap.isRecycled) { "bitmap is recycled" }
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8
        require(widthBytes <= MAX_WIDTH_BYTES) { "bitmap is too wide: $width dots" }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte(),
        )
        val out = ByteArray(header.size + widthBytes * height)
        header.copyInto(out)

        var index = header.size
        for (y in 0 until height) {
            val row = y * width
            for (byteX in 0 until widthBytes) {
                var bits = 0
                for (bit in 0 until 8) {
                    val x = byteX * 8 + bit
                    // The last byte of a row is padded with white when the width
                    // is not a multiple of 8 — printing those bits black would
                    // put a black bar down the edge of every receipt.
                    if (x >= width) continue
                    if (isInk(pixels[row + x], threshold)) bits = bits or (0x80 shr bit)
                }
                out[index++] = bits.toByte()
            }
        }
        return out
    }

    /**
     * Whether a pixel should fire a dot.
     *
     * Rec. 601 luma, not a plain average: it is the eye's own weighting, and it
     * is what keeps thin Arabic strokes alive through the drop to one bit.
     */
    private fun isInk(pixel: Int, threshold: Int): Boolean {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 128) return false
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < threshold
    }
}
