package com.esite.senraise

/**
 * Paper width in printer dots.
 *
 * A thermal head is addressed in dots, not millimetres: 203dpi over 58mm of
 * printable width is 384 dots, over 80mm it is 576. Everything the renderer
 * lays out is scaled from this number, so a receipt designed for one width
 * reflows for the other instead of being cropped.
 */
enum class PaperWidth(val dots: Int) {
    MM58(384),
    MM80(576),
    ;

    companion object {
        /** The nearest known width to [dots], for a value read back from storage. */
        fun ofDots(dots: Int): PaperWidth = entries.minBy { kotlin.math.abs(it.dots - dots) }
    }
}

/**
 * Horizontal alignment for the service's own text and barcode calls.
 *
 * Irrelevant to [SenraiseSession.bitmap] — a bitmap carries its own layout and
 * is always printed from the left edge of the head.
 */
enum class Alignment(val code: Int) {
    LEFT(0),
    CENTER(1),
    RIGHT(2),
}

/**
 * Error correction for [SenraiseSession.qrCode].
 *
 * On thermal paper the usual failure is modules too small for the head to
 * resolve, not scattered damage, so a lower level that keeps the symbol in a
 * smaller version — and therefore gives physically larger modules at the same
 * printed size — often scans better than a higher one. [M] is the sane default.
 */
enum class QrErrorLevel(val code: Int) {
    L(0),
    M(1),
    Q(2),
    H(3),
}

/**
 * Barcode symbologies accepted by [SenraiseSession.barcode].
 *
 * These are the `GS k` indices from the ESC/POS command set, which is the
 * mapping the vendor's own demo passes to `printBarCode`. The service does not
 * validate the payload: a string that does not fit the symbology's character set
 * or check-digit rules prints as a blank or a truncated symbol rather than
 * reporting an error, so validate before calling.
 */
enum class Symbology(val code: Int) {
    UPC_A(0),
    UPC_E(1),
    EAN13(2),
    EAN8(3),
    CODE39(4),
    ITF(5),
    CODABAR(6),
    CODE93(7),
    CODE128(8),
}
