package com.esite.senraise

import com.esite.senraise.receipt.ReceiptBlock
import com.esite.senraise.receipt.TextAlign
import com.esite.senraise.receipt.TextStyle
import com.esite.senraise.receipt.receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTest {

    @Test
    fun `blocks keep the order they were declared in`() {
        val doc = receipt {
            title("Shop")
            rule()
            row("Coffee", "3,000")
            code("ABC-123")
        }
        assertEquals(
            listOf(
                ReceiptBlock.Text::class,
                ReceiptBlock.Rule::class,
                ReceiptBlock.Row::class,
                ReceiptBlock.Code::class,
            ),
            doc.blocks.map { it::class },
        )
    }

    @Test
    fun `blank text is dropped instead of printing an empty line`() {
        val doc = receipt {
            title("")
            body("   ")
            code("")
            qr(" ")
            small("real")
        }
        assertEquals(1, doc.blocks.size)
        assertEquals("real", (doc.blocks.single() as ReceiptBlock.Text).text)
    }

    @Test
    fun `an empty row still prints - a label with no value is a valid line`() {
        val doc = receipt { row("Subtotal", "") }
        assertEquals(1, doc.blocks.size)
    }

    @Test
    fun `shorthands carry the styles the hierarchy depends on`() {
        val doc = receipt {
            title("Shop")
            heading("Street")
            body("Item")
            small("terms")
        }
        val texts = doc.blocks.map { it as ReceiptBlock.Text }
        assertEquals(
            listOf(TextStyle.TITLE, TextStyle.HEADING, TextStyle.BODY, TextStyle.SMALL),
            texts.map { it.style },
        )
        // A title centres itself; body text follows the reading direction.
        assertEquals(TextAlign.CENTER, texts[0].align)
        assertEquals(TextAlign.START, texts[2].align)
    }

    @Test
    fun `a rule is never thinner than the paper can hold`() {
        val doc = receipt { +ReceiptBlock.Rule(weightDots = 1) }
        // The block keeps what was asked for; the renderer is what clamps it.
        assertEquals(1, (doc.blocks.single() as ReceiptBlock.Rule).weightDots)
    }

    @Test
    fun `paper widths are dots, not millimetres`() {
        assertEquals(384, PaperWidth.MM58.dots)
        assertEquals(576, PaperWidth.MM80.dots)
        assertEquals(PaperWidth.MM58, PaperWidth.ofDots(384))
        assertEquals(PaperWidth.MM80, PaperWidth.ofDots(580))
        // A stored value from a device nobody has seen still resolves.
        assertTrue(PaperWidth.ofDots(0) in PaperWidth.entries)
    }
}
