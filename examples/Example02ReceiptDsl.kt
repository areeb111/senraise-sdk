package com.esite.senraise.examples

import android.content.Context
import com.esite.senraise.PaperWidth
import com.esite.senraise.PrintResult
import com.esite.senraise.SenraisePrinter
import com.esite.senraise.receipt.ReceiptRenderer
import com.esite.senraise.receipt.TextStyle
import com.esite.senraise.receipt.printReceipt
import com.esite.senraise.receipt.receipt

/**
 * **2 — A receipt, start to finish.**
 *
 * The path almost every app wants: describe the receipt, hand it over, get a
 * result. `printReceipt` lays it out on `Dispatchers.Default`, prints on
 * `Dispatchers.IO` and recycles the page bitmap, so this is safe to call
 * directly from a `viewModelScope`.
 *
 * Note what the description does *not* contain: no dot positions, no font sizes,
 * no `====` separators. Sizes are styles and widths are fractions, so the same
 * receipt renders at 384 dots for 58mm paper and 576 for 80mm without being
 * rewritten.
 */
suspend fun printSale(
    context: Context,
    // Shared, because it holds the loaded faces.
    renderer: ReceiptRenderer = ReceiptRenderer(),
): PrintResult {
    val printer = SenraisePrinter(context)

    val doc = receipt {
        title("مقهى الرشيد")
        heading("شارع الرشيد، بغداد")
        small("٠٧٧٠ ١٢٣ ٤٥٦٧")
        rule()

        row("قهوة عربية", "٣٠٠٠")
        row("شاي بالنعناع", "١٥٠٠")
        row("كعك بالتمر", "٢٠٠٠")

        rule()
        // A heavier style is how a total reads as a total. There is no bold flag
        // and no separator row, because on a one-bit head a size-and-weight step
        // survives the paper and a line of equals signs just eats it.
        row("الإجمالي", "٦٥٠٠", TextStyle.HEADING)

        gap(1.5f)
        small("رقم الوصل")
        // Never wraps: it shrinks to fit, giving up letter tracking before size.
        code("8830-1012-6346")
        // Payload is exactly the code, no URL around it — what is scanned and
        // what is printed underneath stay the same string.
        qr("8830-1012-6346")
        small("شكراً لزيارتكم")
    }

    // rtl mirrors the rows and sets the paragraph direction. The Arabic itself
    // is shaped by Android regardless — that is the whole reason this receipt is
    // rasterised instead of sent as text.
    return printer.printReceipt(doc, PaperWidth.MM58, rtl = true, renderer = renderer)
}
