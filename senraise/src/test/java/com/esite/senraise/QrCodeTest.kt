package com.esite.senraise

import com.esite.senraise.receipt.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR encoder is pure arithmetic on a module grid — no Android — so the
 * properties that decide whether a symbol scans off thermal paper can be
 * asserted off-device.
 */
class QrCodeTest {

    @Test
    fun `module size is a whole number of dots`() {
        val symbol = requireNotNull(QrCode.render("8830-1012-6346-7129", targetDots = 245))
        assertEquals(symbol.modules * symbol.moduleDots, symbol.side)
    }

    @Test
    fun `symbol fits inside the hint`() {
        val symbol = requireNotNull(QrCode.render("8830-1012-6346-7129", targetDots = 245))
        assertTrue("side ${symbol.side} exceeded the 245 dot hint", symbol.side <= 245)
    }

    @Test
    fun `modules never fall below the dots the paper can hold`() {
        // An absurdly small hint: legibility wins over the hint, because a
        // symbol too small to scan is worth nothing at any size.
        val symbol = requireNotNull(QrCode.render("8830-1012-6346-7129", targetDots = 10))
        assertEquals(QrCode.MIN_MODULE_DOTS, symbol.moduleDots)
    }

    @Test
    fun `quiet zone is white on every edge`() {
        val symbol = requireNotNull(QrCode.render("ORDER-42", targetDots = 200))
        val zone = QrCode.QUIET_ZONE_MODULES * symbol.moduleDots
        for (i in 0 until symbol.side) {
            assertTrue("top edge at $i is black", !symbol.isBlack(i, 0))
            assertTrue("left edge at $i is black", !symbol.isBlack(0, i))
            assertTrue("inside top quiet zone", !symbol.isBlack(i, zone - 1))
            assertTrue("inside left quiet zone", !symbol.isBlack(zone - 1, i))
        }
    }

    @Test
    fun `blank payloads are refused rather than printed empty`() {
        assertNull(QrCode.render("", targetDots = 200))
        assertNull(QrCode.render("   ", targetDots = 200))
    }
}
