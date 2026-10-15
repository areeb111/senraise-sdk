package com.esite.senraise.examples

import android.content.Context
import com.esite.senraise.SenraisePrinter

/**
 * **1 — Is there a printer here?**
 *
 * Ask before showing a Print button. The check is a package lookup, so it costs
 * nothing, never throws, and is honest on a device that is not a Senraise
 * terminal — which is most devices, including every emulator you will develop
 * against.
 *
 * It answers false in one case that is not a hardware fact: an app that strips
 * the `<queries>` manifest entry cannot see the vendor package at all on Android
 * 11+. Depending on this SDK merges that entry in for you, so if this returns
 * false on a terminal you know has a printer, check the merged manifest before
 * suspecting the hardware.
 */
suspend fun showPrintButton(context: Context): Boolean {
    val printer = SenraisePrinter(context)
    return printer.isAvailable()
}

/** For a diagnostics screen: the service's own version, or null if absent. */
suspend fun printerDiagnostics(context: Context): String {
    val printer = SenraisePrinter(context)
    if (!printer.isAvailable()) return "No Senraise printer service installed"
    return "Senraise service version: ${printer.serviceVersion() ?: "not reported"}"
}
