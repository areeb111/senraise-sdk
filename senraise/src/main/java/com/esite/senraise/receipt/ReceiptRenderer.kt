package com.esite.senraise.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import com.esite.senraise.PaperWidth

/**
 * The faces a receipt is drawn with.
 *
 * Defaults are the platform families, which is enough for Latin and gets Arabic
 * shaped correctly through the system fallback. Bundle a real family and pass it
 * here when the receipt carries a brand, or when the terminals in the field have
 * a thin or incomplete Arabic fallback — that is the usual cause of a receipt
 * that prints beautifully on one unit and as boxes on another.
 *
 * Note what is *not* here: no synthetic bold. `isFakeBoldText` smears an already
 * thick glyph on a one-bit head instead of thickening it cleanly, and it is
 * worst exactly where weight matters most — small Arabic. Real faces only.
 */
class ReceiptFonts @JvmOverloads constructor(
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL),
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL),
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD),
) {
    companion object {
        /**
         * Loads faces from `src/main/assets`, falling back to the platform
         * family for any that will not load — a missing font should cost a
         * receipt its brand, not its print.
         */
        @JvmStatic
        @JvmOverloads
        fun fromAssets(
            context: Context,
            regular: String,
            medium: String = regular,
            bold: String = regular,
        ): ReceiptFonts {
            fun load(path: String, fallback: Typeface): Typeface =
                runCatching { Typeface.createFromAsset(context.assets, path) }
                    .getOrDefault(fallback)
            val default = ReceiptFonts()
            return ReceiptFonts(
                regular = load(regular, default.regular),
                medium = load(medium, default.medium),
                bold = load(bold, default.bold),
            )
        }
    }
}

/**
 * Draws a [Receipt] into a bitmap the print head can take as-is.
 *
 * This exists because the vendor's `printText` cannot shape Arabic: it renders a
 * string codepoint by codepoint, so the letters come out unjoined, in visual
 * order, and frequently as tofu. Rasterising the receipt on Android instead
 * means the platform does the shaping and bidi, and the printer only ever sees
 * dots. It is also how a receipt gets a logo, a QR code and real typography.
 *
 * Everything here is chosen for a one-bit thermal head:
 *
 *  - **Hierarchy comes from size and weight**, not from rows of `====`.
 *  - **Rules are solid and at least 3 dots.** A hairline prints as a dashed
 *    ghost on one printer and as nothing on another.
 *  - **No greys.** Grey can only be expressed as a dither pattern, and dithered
 *    small text turns to mud. A muted line is muted by being smaller.
 *  - **A real margin.** Nothing runs to the paper edge, where feed skew and the
 *    head's own edge fade eat it.
 *  - **Wrap, then ellipsise.** A long name must never run off the edge.
 *
 * Thread-safe and cheap to keep around; [render] holds no state between calls.
 * It is CPU work though, so call it off the main thread — `Dispatchers.Default`
 * is the right place, with the print itself on `Dispatchers.IO`.
 */
class ReceiptRenderer @JvmOverloads constructor(
    private val fonts: ReceiptFonts = ReceiptFonts(),
) {

    /** Renders for a standard paper width. */
    @JvmOverloads
    fun render(receipt: Receipt, paper: PaperWidth, rtl: Boolean = false): Bitmap =
        render(receipt, paper.dots, rtl)

    /**
     * Renders at an exact width in printer dots.
     *
     * [rtl] decides layout direction, not script: pass true for an Arabic or
     * Hebrew receipt so [TextAlign.START] means the right edge and rows mirror.
     * The returned bitmap is exactly [widthDots] wide and as tall as the content
     * needs — pure black on pure white, no alpha, nothing for the head's
     * threshold to guess about.
     */
    fun render(receipt: Receipt, widthDots: Int, rtl: Boolean = false): Bitmap {
        require(widthDots > 0) { "widthDots must be positive" }
        val page = Page(widthDots, rtl)
        val items = receipt.blocks.map { page.measure(it) }
        val padding = page.unit * PAGE_PADDING_UNITS
        val height = (padding * 2 + items.sumOf { it.height.toDouble() }.toFloat())
            .toInt()
            .coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthDots, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = padding
        for (item in items) {
            item.draw(canvas, y)
            y += item.height
        }
        return bitmap
    }

    /** A measured block: how tall it is, and how to put it on the canvas. */
    private class Item(val height: Float, val draw: (Canvas, Float) -> Unit)

    /**
     * One page's metrics and paints.
     *
     * Measuring and drawing are the same pass here — each block is measured into
     * an [Item] that closes over its own already-built layout — so a block can
     * never be drawn against a size that was computed differently.
     */
    private inner class Page(private val widthDots: Int, private val rtl: Boolean) {

        private val margin = (widthDots * MARGIN_RATIO).toInt()
        private val content = (widthDots - margin * 2).coerceAtLeast(1)

        /** One typographic step, scaled to the paper rather than to the screen. */
        val unit = widthDots / 48f

        private val rulePaint = Paint().apply { color = Color.BLACK }

        fun measure(block: ReceiptBlock): Item = when (block) {
            is ReceiptBlock.Text -> text(block)
            is ReceiptBlock.Row -> row(block)
            is ReceiptBlock.Code -> code(block)
            is ReceiptBlock.Rule -> rule(block)
            is ReceiptBlock.Gap -> Item(unit * block.units) { _, _ -> }
            is ReceiptBlock.Image -> image(block)
            is ReceiptBlock.Qr -> qr(block)
        }

        private fun text(block: ReceiptBlock.Text): Item {
            val paint = paintFor(block.style)
            val layout = layout(block.text, paint, alignmentOf(block.align), block.maxLines)
            val trailing = unit * TEXT_TRAILING_UNITS
            return Item(layout.height + trailing) { canvas, y ->
                val saved = canvas.save()
                canvas.translate(margin.toFloat(), y)
                layout.draw(canvas)
                canvas.restoreToCount(saved)
            }
        }

        private fun row(block: ReceiptBlock.Row): Item {
            val paint = paintFor(block.style)
            // The value keeps its ends and loses its middle: a truncated amount
            // or reference that still shows its first and last digits is far
            // easier to query than one that trails off into an ellipsis.
            val value = TextUtils.ellipsize(
                block.value, paint, content * VALUE_WIDTH_SHARE, TextUtils.TruncateAt.MIDDLE,
            ).toString()
            val valueWidth = paint.measureText(value)
            val labelRoom = (content - valueWidth - unit).coerceAtLeast(unit)
            val label = TextUtils.ellipsize(
                block.label, paint, labelRoom, TextUtils.TruncateAt.END,
            ).toString()
            val trailing = unit * ROW_TRAILING_UNITS
            return Item(paint.lineHeight() + trailing) { canvas, y ->
                val baseline = y - paint.ascent()
                val labelWidth = paint.measureText(label)
                if (rtl) {
                    canvas.drawText(label, widthDots - margin - labelWidth, baseline, paint)
                    canvas.drawText(value, margin.toFloat(), baseline, paint)
                } else {
                    canvas.drawText(label, margin.toFloat(), baseline, paint)
                    canvas.drawText(value, widthDots - margin - valueWidth, baseline, paint)
                }
            }
        }

        private fun code(block: ReceiptBlock.Code): Item {
            val paint = fitCode(block.text)
            val trailing = unit * CODE_TRAILING_UNITS
            return Item(paint.lineHeight() + trailing) { canvas, y ->
                canvas.drawText(
                    block.text,
                    (widthDots - paint.measureText(block.text)) / 2f,
                    y - paint.ascent(),
                    paint,
                )
            }
        }

        /**
         * The hero line, fitted to exactly one line.
         *
         * Tracking is traded down to a floor before size is touched, because
         * tracking is what keeps 0/O, 1/7 and 5/S apart on paper — and a code
         * read aloud down a phone is read character by character.
         */
        private fun fitCode(text: String): TextPaint {
            var size = unit * CODE_SIZE_UNITS
            var tracking = CODE_TRACKING_MAX
            val minimum = unit * CODE_SIZE_MIN_UNITS
            val paint = paint(size, fonts.bold)
            while (size > minimum) {
                paint.textSize = size
                paint.letterSpacing = tracking
                if (paint.measureText(text) <= content) return paint
                if (tracking > CODE_TRACKING_MIN) tracking -= 0.02f else size -= unit * 0.15f
            }
            // Out of room: a genuinely over-long code gets the floor size and no
            // tracking, and still stays on one line.
            paint.textSize = minimum
            paint.letterSpacing = 0f
            return paint
        }

        private fun rule(block: ReceiptBlock.Rule): Item {
            val weight = block.weightDots.coerceAtLeast(MIN_RULE_DOTS).toFloat()
            val air = unit * RULE_AIR_UNITS
            return Item(air * 2 + weight) { canvas, y ->
                canvas.drawRect(
                    margin.toFloat(),
                    y + air,
                    (widthDots - margin).toFloat(),
                    y + air + weight,
                    rulePaint,
                )
            }
        }

        private fun image(block: ReceiptBlock.Image): Item {
            val target = (widthDots * block.widthRatio).toInt().coerceIn(1, content)
            val scale = target.toFloat() / block.bitmap.width
            val height = (block.bitmap.height * scale).toInt().coerceAtLeast(1)
            val trailing = unit * IMAGE_TRAILING_UNITS
            return Item(height + trailing) { canvas, y ->
                val scaled = Bitmap.createScaledBitmap(block.bitmap, target, height, true)
                canvas.drawBitmap(scaled, (widthDots - target) / 2f, y, null)
                if (scaled !== block.bitmap) scaled.recycle()
            }
        }

        private fun qr(block: ReceiptBlock.Qr): Item {
            val target = (widthDots * block.widthRatio).toInt().coerceIn(1, content)
            val symbol = QrCode.render(block.payload, target)
                ?: return Item(0f) { _, _ -> }
            val trailing = unit * QR_TRAILING_UNITS
            return Item(symbol.side + trailing) { canvas, y ->
                // Blitted as already-decided dots: there is no antialiasing worth
                // preserving in a QR, and a half-covered module is one a scanner
                // may read either way.
                val pixels = IntArray(symbol.side * symbol.side)
                for (row in 0 until symbol.side) {
                    val offset = row * symbol.side
                    for (column in 0 until symbol.side) {
                        pixels[offset + column] =
                            if (symbol.isBlack(column, row)) Color.BLACK else Color.WHITE
                    }
                }
                val bitmap = Bitmap.createBitmap(
                    pixels, symbol.side, symbol.side, Bitmap.Config.ARGB_8888,
                )
                canvas.drawBitmap(bitmap, (widthDots - symbol.side) / 2f, y, null)
                bitmap.recycle()
            }
        }

        private fun paintFor(style: TextStyle): TextPaint = when (style) {
            TextStyle.TITLE -> paint(unit * 4.6f, fonts.bold)
            TextStyle.HEADING -> paint(unit * 3.3f, fonts.medium)
            TextStyle.BODY -> paint(unit * 3.0f, fonts.medium)
            TextStyle.SMALL -> paint(unit * 2.6f, fonts.regular)
        }

        private fun paint(size: Float, face: Typeface) = TextPaint().apply {
            isAntiAlias = true
            textSize = size
            typeface = face
            color = Color.BLACK
        }

        private fun alignmentOf(align: TextAlign): Layout.Alignment = when (align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }

        private fun layout(
            text: String,
            paint: TextPaint,
            alignment: Layout.Alignment,
            maxLines: Int,
        ): StaticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, content)
            .setAlignment(alignment)
            // The direction is the receipt's, not the string's: a mixed line
            // ("Order #123" inside an Arabic receipt) must sit the way the rest
            // of the page sits, and ALIGN_NORMAL means "start" only once the
            // paragraph direction is known.
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.08f)
            // Wrap first, ellipsise second.
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private companion object {

        /** Never print to the paper edge: feed skew and edge fade eat it. */
        const val MARGIN_RATIO = 0.055f

        /** Air above the first block and below the last. */
        const val PAGE_PADDING_UNITS = 2f

        const val TEXT_TRAILING_UNITS = 0.45f
        const val ROW_TRAILING_UNITS = 0.40f
        const val CODE_TRAILING_UNITS = 0.60f
        const val IMAGE_TRAILING_UNITS = 0.80f
        const val QR_TRAILING_UNITS = 0.80f
        const val RULE_AIR_UNITS = 1.1f

        /** Below 3 dots a rule prints as a dashed ghost, or not at all. */
        const val MIN_RULE_DOTS = 3

        const val CODE_SIZE_UNITS = 6.4f
        const val CODE_SIZE_MIN_UNITS = 3.2f
        const val CODE_TRACKING_MAX = 0.14f
        const val CODE_TRACKING_MIN = 0.06f

        /** A row's value may take this much of the line before it is trimmed. */
        const val VALUE_WIDTH_SHARE = 0.5f

        fun Paint.lineHeight(): Float = fontMetrics.descent - fontMetrics.ascent
    }
}
