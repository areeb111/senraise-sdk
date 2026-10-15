package com.esite.senraise.examples

import android.content.Context
import com.esite.senraise.PrintFailure
import com.esite.senraise.PrintResult
import com.esite.senraise.SenraisePrinter

/**
 * **6 — What to do when it does not print.**
 *
 * Printing never throws out of this SDK, and that is deliberate: a till that
 * crashes because the paper ran out is worse than one that prints nothing. Which
 * means every caller has a result to deal with, and the useful question is what
 * the operator should be told.
 *
 * The one rule worth building the rest of the app around: **a print is not the
 * record.** Save the sale first, print second, and keep a reprint route open. A
 * receipt that failed to print is an inconvenience; a sale that was not recorded
 * because the printer jammed is money lost.
 */
fun operatorMessage(result: PrintResult): String? = when (result) {
    is PrintResult.Success -> null

    is PrintResult.Failed -> when (result.reason) {
        // A device fact, not a fault. Hide the Print button rather than
        // apologising for it, and offer the platform print dialog or a share
        // sheet as the fallback route.
        PrintFailure.UNAVAILABLE ->
            "This device has no built-in printer."

        // The vendor package is installed but disabled, which on a locked-down
        // terminal is an MDM decision rather than something the shop can fix.
        PrintFailure.BIND_REFUSED ->
            "The printer service is disabled on this device. Ask your administrator."

        // Nothing was written, so a retry is genuinely worth offering — a cold
        // service that lost the first bind usually answers the second.
        PrintFailure.TIMED_OUT ->
            "The printer did not respond. Try again."

        // The call reached the service and failed: paper, cover, head
        // temperature, or a service that died mid-print. This is the one the
        // operator can usually fix at the counter.
        PrintFailure.IO ->
            "Printing failed. Check the paper and the cover, then reprint."
    }
}

/**
 * Save first, print second, and tell the user which half worked.
 *
 * Note the retry: only [PrintFailure.TIMED_OUT] gets one automatically, because
 * it is the only failure that guarantees nothing reached the paper. Retrying an
 * IO failure can print the receipt twice — the service may have printed most of
 * it before dying — and a duplicate voucher is worse than a missing one.
 */
suspend fun printAfterSaving(
    context: Context,
    saveSale: suspend () -> Unit,
    printReceipt: suspend (SenraisePrinter) -> PrintResult,
): String {
    saveSale()

    val printer = SenraisePrinter(context)
    var result = printReceipt(printer)
    if (result is PrintResult.Failed && result.reason == PrintFailure.TIMED_OUT) {
        result = printReceipt(printer)
    }

    return operatorMessage(result)
        ?.let { "Sale saved. $it" }
        ?: "Sale saved and printed."
}
