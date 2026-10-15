package com.esite.senraise.sample

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.esite.senraise.Alignment
import com.esite.senraise.PaperWidth
import com.esite.senraise.PrintResult
import com.esite.senraise.SenraisePrinter
import com.esite.senraise.escpos.EscPosRaster
import com.esite.senraise.receipt.ReceiptRenderer
import com.esite.senraise.receipt.TextStyle
import com.esite.senraise.receipt.printReceipt
import com.esite.senraise.receipt.receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every way the SDK can be used, on one screen.
 *
 * The UI is built in code on purpose: a sample whose value is in five printer
 * calls should not come with a layout, a theme and a view-binding to read
 * through first. Each button is one self-contained path — read the one you need.
 *
 * Run it on a Senraise terminal. On anything else the first button reports the
 * service as absent, which is itself the behaviour worth seeing: the SDK reports
 * unavailable rather than crashing on a device that has no printer.
 */
class MainActivity : ComponentActivity() {

    // Cheap to construct, holds no binder between prints, so one for the
    // process is right. In a real app this is a @Singleton.
    private val printer by lazy { SenraisePrinter(this) }

    // Holds the loaded faces, so it is worth keeping rather than rebuilding per
    // receipt. Pass ReceiptFonts.fromAssets(...) here to brand the paper.
    private val renderer by lazy { ReceiptRenderer() }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        report("Ready. Tap “Check availability” first.")
    }

    // ---------------------------------------------------------------- paths

    /** Is there a printer here at all? Cheap, never throws. */
    private fun checkAvailability() {
        report("Checking…")
        lifecycleScope.launch {
            val available = printer.isAvailable()
            val version = if (available) printer.serviceVersion() else null
            report(
                if (available) {
                    "Senraise service found.\nVersion: ${version ?: "not reported"}"
                } else {
                    "No Senraise service on this device.\n" +
                        "Either this is not a Senraise terminal, or the vendor " +
                        "package has been removed."
                },
            )
        }
    }

    /** The common case: build a receipt, render it, print it. */
    private fun printReceiptRtl() = printing("Printing Arabic receipt…") {
        val doc = receipt {
            title("مقهى الرشيد")
            heading("شارع الرشيد، بغداد")
            small("٠٧٧٠ ١٢٣ ٤٥٦٧")
            rule()
            row("قهوة عربية", "٣٠٠٠")
            row("شاي بالنعناع", "١٥٠٠")
            row("كعك بالتمر", "٢٠٠٠")
            rule()
            row("الإجمالي", "٦٥٠٠", TextStyle.HEADING)
            gap(1.5f)
            small("رقم الوصل")
            code("8830-1012-6346")
            qr("8830-1012-6346")
            small("شكراً لزيارتكم")
        }
        // rtl = true mirrors the rows and sets the paragraph direction; the
        // Arabic itself is shaped by Android either way.
        printer.printReceipt(doc, PaperWidth.MM58, rtl = true, renderer = renderer)
    }

    /** The same path, left to right, for a Latin receipt. */
    private fun printReceiptLtr() = printing("Printing Latin receipt…") {
        val doc = receipt {
            title("Rashid Coffee")
            heading("Rashid St, Baghdad")
            rule()
            row("Arabic coffee", "3,000")
            row("Mint tea", "1,500")
            rule()
            row("Total", "4,500", TextStyle.HEADING)
            gap(1.5f)
            code("8830-1012-6346")
            qr("8830-1012-6346")
            small("Thank you")
        }
        printer.printReceipt(doc, PaperWidth.MM58, rtl = false, renderer = renderer)
    }

    /**
     * The vendor's own text, QR and table calls, with no rasterising.
     *
     * Fast and fine for Latin. Note what the paper shows if you add an Arabic
     * string here: unjoined letters in visual order, which is exactly why the
     * renderer above exists.
     */
    private fun printNative() = printing("Printing with the vendor font…") {
        printer.print {
            align(Alignment.CENTER)
            textSize(32f)
            bold(true)
            text("RASHID COFFEE\n")
            bold(false)
            textSize(24f)
            text("Rashid St, Baghdad\n")
            feed(1)

            align(Alignment.LEFT)
            table(
                cells = listOf("Item", "Qty", "Price"),
                weights = listOf(3, 1, 2),
                alignments = listOf(Alignment.LEFT, Alignment.CENTER, Alignment.RIGHT),
            )
            table(
                cells = listOf("Arabic coffee", "1", "3,000"),
                weights = listOf(3, 1, 2),
                alignments = listOf(Alignment.LEFT, Alignment.CENTER, Alignment.RIGHT),
            )
            feed(1)

            align(Alignment.CENTER)
            qrCode("8830-1012-6346", moduleDots = 6)
            feed(3)
        }
    }

    /**
     * Raw ESC/POS: render on Android, convert to a raster command, push the
     * bytes through.
     *
     * The long way round — `printBitmap` does the same job in one call — but it
     * is the path to take when the same bytes also have to go to a Bluetooth or
     * serial printer, or when a command the AIDL does not expose is needed.
     */
    private fun printEscPos() = printing("Printing raw ESC/POS…") {
        val doc = receipt {
            title("ESC/POS raster")
            body("Rendered by the SDK, sent as GS v 0 bytes.")
            rule()
            row("Mode", "GS v 0")
            row("Threshold", EscPosRaster.INK_THRESHOLD.toString())
        }
        val bytes = withContext(Dispatchers.Default) {
            val bitmap = renderer.render(doc, PaperWidth.MM58, rtl = false)
            try {
                EscPosRaster.raster(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        printer.print {
            // Whatever the last caller left set, cleared — raw bytes are the one
            // path that can strand the printer's parser mid-state.
            escPos(EscPosRaster.RESET)
            escPos(bytes)
            feed(3)
        }
    }

    // ------------------------------------------------------------ plumbing

    /** Runs a printing path and reports whatever came back. */
    private fun printing(message: String, block: suspend () -> PrintResult) {
        report(message)
        lifecycleScope.launch {
            // No dispatcher here: every SDK call moves its own work off the main
            // thread, so a lifecycleScope launch is the whole of the threading.
            report(describe(block()))
        }
    }

    private fun describe(result: PrintResult): String = when (result) {
        is PrintResult.Success -> "Printed."
        is PrintResult.Failed -> buildString {
            append("Failed: ").append(result.reason)
            result.cause?.let { append("\n").append(it::class.java.simpleName).append(": ").append(it.message) }
        }
    }

    private fun report(text: String) {
        status.text = text
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        column.addView(
            TextView(this).apply {
                text = "Senraise POS Printer SDK"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        status = TextView(this).apply {
            setPadding(0, PADDING, 0, PADDING)
            setTextColor(Color.DKGRAY)
            textSize = 15f
        }
        column.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            column.addView(
                Button(this).apply {
                    text = label
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { onClick() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }

        button("Check availability") { checkAvailability() }
        button("Print receipt — Arabic (RTL)") { printReceiptRtl() }
        button("Print receipt — Latin (LTR)") { printReceiptLtr() }
        button("Print with the vendor font + QR") { printNative() }
        button("Print raw ESC/POS raster") { printEscPos() }

        return ScrollView(this).apply { addView(column) }
    }

    private companion object {
        const val PADDING = 32
    }
}
