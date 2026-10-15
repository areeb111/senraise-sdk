package com.esite.senraise.receipt

import com.esite.senraise.PaperWidth
import com.esite.senraise.PrintResult
import com.esite.senraise.SenraisePrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders [receipt] and prints it: the one call most apps need.
 *
 * Layout runs on `Dispatchers.Default` and the print on `Dispatchers.IO`, so
 * this is safe to call straight from a `viewModelScope`. The page bitmap is
 * recycled before returning — it can be a megabyte on 80mm paper and there is
 * nothing left to do with it.
 *
 * Pass a [renderer] to reuse one that has your bundled fonts; the default
 * builds a throwaway with the platform faces.
 */
suspend fun SenraisePrinter.printReceipt(
    receipt: Receipt,
    paper: PaperWidth = PaperWidth.MM58,
    rtl: Boolean = false,
    renderer: ReceiptRenderer = ReceiptRenderer(),
    feedLines: Int = SenraisePrinter.DEFAULT_FEED_LINES,
): PrintResult {
    val bitmap = withContext(Dispatchers.Default) { renderer.render(receipt, paper, rtl) }
    return try {
        printBitmap(bitmap, feedLines)
    } finally {
        bitmap.recycle()
    }
}
