package com.esite.senraise.receipt

import android.graphics.Bitmap

/**
 * What a receipt says, with no opinion about how wide the paper is.
 *
 * A description, not a layout: sizes are styles rather than points and widths
 * are fractions rather than dots, so the same receipt renders at 384 dots for
 * 58mm paper and 576 for 80mm without being rewritten or cropped. Build one with
 * the [receipt] DSL.
 */
class Receipt(val blocks: List<ReceiptBlock>)

/** One element of a [Receipt], in print order. */
sealed interface ReceiptBlock {

    /** A run of text, wrapped to the content width and then ellipsised. */
    data class Text(
        val text: String,
        val style: TextStyle = TextStyle.BODY,
        val align: TextAlign = TextAlign.START,
        val maxLines: Int = 3,
    ) : ReceiptBlock

    /**
     * A label and a value on one line, pushed to opposite edges — an item and
     * its price, a total, a line of small print. Mirrors under RTL.
     */
    data class Row(
        val label: String,
        val value: String,
        val style: TextStyle = TextStyle.BODY,
    ) : ReceiptBlock

    /**
     * A short string printed as large as will fit on one line: a voucher code,
     * an order number, the thing a customer reads down a phone.
     *
     * Never wraps. A wrapped code is the worst failure a receipt can have —
     * "8830-1012-63 / 46-7129" reads as two numbers and gets transcribed wrong —
     * so the renderer shrinks it to fit instead, giving up letter tracking before
     * it gives up size, because tracking is what keeps 0/O, 1/7 and 5/S apart.
     */
    data class Code(val text: String) : ReceiptBlock

    /**
     * A horizontal rule.
     *
     * Three dots by default and solid, not a hairline: thermal heads render a
     * 1-dot line as a dashed ghost on one printer and as nothing at all on
     * another.
     */
    data class Rule(val weightDots: Int = 3) : ReceiptBlock

    /** Vertical space, in typographic units (one unit is 1/48 of the paper width). */
    data class Gap(val units: Float = 1f) : ReceiptBlock

    /**
     * A logo or any other bitmap, centred and scaled to [widthRatio] of the
     * paper width. Binarise it yourself if its edges matter; the renderer draws
     * it as given and the head cuts the greys.
     */
    data class Image(
        val bitmap: Bitmap,
        val widthRatio: Float = 0.45f,
    ) : ReceiptBlock

    /** A QR code, centred, sized to about [widthRatio] of the paper width. */
    data class Qr(
        val payload: String,
        val widthRatio: Float = 0.5f,
    ) : ReceiptBlock
}

/**
 * The four sizes a receipt gets.
 *
 * Four, not a scale: at 384 dots through a one-bit threshold a two-point size
 * change is not a hierarchy, it is a rounding error. Weight carries as much of
 * the difference as size does, and there are no greys anywhere — a muted line is
 * muted by being smaller, because grey can only be expressed as a dither pattern
 * and dithered small text turns to mud.
 */
enum class TextStyle {
    /** The shop's name at the top. */
    TITLE,

    /** A section heading, or the second line under the title. */
    HEADING,

    /** Everything else. */
    BODY,

    /** The small print: terms, a timestamp, a device id. */
    SMALL,
}

/** [START] and [END] follow the text direction and mirror under RTL. */
enum class TextAlign { START, CENTER, END }

/**
 * Builds a [Receipt].
 *
 * ```kotlin
 * val doc = receipt {
 *     title("مقهى الرشيد")
 *     heading("شارع الرشيد، بغداد")
 *     rule()
 *     row("قهوة عربية", "3,000")
 *     row("كعك", "1,500")
 *     rule()
 *     row("الإجمالي", "4,500", TextStyle.HEADING)
 *     gap(1.5f)
 *     code("8830-1012-6346")
 *     qr("8830-1012-6346")
 *     small("شكراً لزيارتكم")
 * }
 * ```
 */
@ReceiptDsl
class ReceiptBuilder internal constructor() {

    private val blocks = mutableListOf<ReceiptBlock>()

    fun title(text: String, align: TextAlign = TextAlign.CENTER) =
        text(text, TextStyle.TITLE, align, maxLines = 2)

    fun heading(text: String, align: TextAlign = TextAlign.CENTER) =
        text(text, TextStyle.HEADING, align)

    fun body(text: String, align: TextAlign = TextAlign.START) =
        text(text, TextStyle.BODY, align)

    fun small(text: String, align: TextAlign = TextAlign.CENTER) =
        text(text, TextStyle.SMALL, align)

    fun text(
        text: String,
        style: TextStyle = TextStyle.BODY,
        align: TextAlign = TextAlign.START,
        maxLines: Int = 3,
    ) {
        if (text.isBlank()) return
        blocks += ReceiptBlock.Text(text, style, align, maxLines)
    }

    fun row(label: String, value: String, style: TextStyle = TextStyle.BODY) {
        blocks += ReceiptBlock.Row(label, value, style)
    }

    fun code(text: String) {
        if (text.isBlank()) return
        blocks += ReceiptBlock.Code(text)
    }

    fun rule(weightDots: Int = 3) {
        blocks += ReceiptBlock.Rule(weightDots)
    }

    fun gap(units: Float = 1f) {
        blocks += ReceiptBlock.Gap(units)
    }

    fun image(bitmap: Bitmap, widthRatio: Float = 0.45f) {
        blocks += ReceiptBlock.Image(bitmap, widthRatio)
    }

    fun qr(payload: String, widthRatio: Float = 0.5f) {
        if (payload.isBlank()) return
        blocks += ReceiptBlock.Qr(payload, widthRatio)
    }

    /** Adds a block the DSL has no shorthand for. */
    operator fun ReceiptBlock.unaryPlus() {
        blocks += this
    }

    internal fun build() = Receipt(blocks.toList())
}

@DslMarker
annotation class ReceiptDsl

/** Entry point for the [ReceiptBuilder] DSL. */
fun receipt(block: ReceiptBuilder.() -> Unit): Receipt =
    ReceiptBuilder().apply(block).build()
