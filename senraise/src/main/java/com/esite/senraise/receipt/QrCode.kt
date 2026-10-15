package com.esite.senraise.receipt

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR symbol encoded for a one-bit thermal head.
 *
 * Two properties matter for print rather than for screen, and neither is what a
 * UI QR library optimises for:
 *
 *  - **Every module is a whole number of printer dots.** A QR scaled by a
 *    fractional factor gets modules 4 dots wide in one column and 5 in the next;
 *    the decoder's grid sampler reads the drift as distortion and the symbol
 *    fails at exactly the angles a handheld scan is held at. So the matrix is
 *    scaled by an *integer* and the drawn size follows from that, rather than
 *    the other way round.
 *  - **The output is pure black and white.** There is no antialiasing worth
 *    preserving in a QR — a half-covered module is not a design intent, it is an
 *    error — so this binarises here instead of leaving a grey for something
 *    downstream to guess about.
 *
 * Error correction is M: the level that keeps a short payload inside a small
 * version, and a smaller matrix at a fixed printed size means physically larger
 * modules. On thermal paper that is worth more than extra redundancy, because
 * the failure mode is modules too small for the head to resolve, not scattered
 * damage.
 */
object QrCode {

    /** Modules of white around the symbol. Four is the spec's minimum. */
    const val QUIET_ZONE_MODULES = 4

    /** Below this a module lands on too few dots to survive the paper. */
    const val MIN_MODULE_DOTS = 3

    /**
     * A square symbol of dots, quiet zone included, or null if [payload] cannot
     * be encoded.
     *
     * [targetDots] is a *hint*: the result is the largest whole-dot multiple of
     * the matrix that fits inside it, so the returned [Symbol.side] is usually a
     * little under what was asked for. It is never under [MIN_MODULE_DOTS] per
     * module, even if that overshoots the hint — an unscannable symbol that fits
     * is worth less than a slightly wide one.
     */
    fun render(payload: String, targetDots: Int): Symbol? = runCatching {
        if (payload.isBlank()) return@runCatching null
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            1,
            1,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // The quiet zone is added here in whole modules, so the encoder
                // is asked for the bare symbol.
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            ),
        )
        val modules = matrix.width
        val withQuiet = modules + QUIET_ZONE_MODULES * 2
        val scale = (targetDots / withQuiet).coerceAtLeast(MIN_MODULE_DOTS)
        val side = withQuiet * scale
        val black = BooleanArray(side * side)
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (!matrix.get(x, y)) continue
                val originX = (x + QUIET_ZONE_MODULES) * scale
                val originY = (y + QUIET_ZONE_MODULES) * scale
                for (dy in 0 until scale) {
                    val row = (originY + dy) * side
                    for (dx in 0 until scale) black[row + originX + dx] = true
                }
            }
        }
        Symbol(side = side, moduleDots = scale, modules = withQuiet, black = black)
    }.getOrNull()

    /**
     * A rendered symbol, in dots.
     *
     * Deliberately not an Android `Bitmap`: this is pure arithmetic on a module
     * grid, so it stays unit-testable off-device and the drawing code blits it.
     */
    class Symbol internal constructor(
        val side: Int,
        val moduleDots: Int,
        val modules: Int,
        private val black: BooleanArray,
    ) {
        fun isBlack(x: Int, y: Int): Boolean = black[y * side + x]

        override fun equals(other: Any?): Boolean =
            this === other || (other is Symbol && side == other.side && black.contentEquals(other.black))

        override fun hashCode(): Int = 31 * side + black.contentHashCode()
    }
}
