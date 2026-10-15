package com.esite.senraise

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import recieptservice.com.recieptservice.PrinterInterface

/**
 * The print head built into a Senraise terminal.
 *
 * The head is not a character device the app may open: a vendor system service
 * owns it and the only way in is AIDL. This class is the whole of that
 * conversation — find the service, bind it, hand a [SenraiseSession] to the
 * caller, unbind however the print ends.
 *
 * ```kotlin
 * val printer = SenraisePrinter(context)
 * if (!printer.isAvailable()) return      // not a Senraise terminal
 *
 * val result = printer.print {
 *     bitmap(receiptBitmap)               // 384 dots wide for 58mm paper
 *     feed(3)                             // clear the tear-off strip
 * }
 * ```
 *
 * Three things are worth knowing before using it:
 *
 *  * **Bound by class name, not by action.** The service publishes no intent
 *    filter, so there is nothing to resolve and [Intent.setClassName] with the
 *    exact class is the only way in. The same absence is why [isAvailable]
 *    checks for the *package* rather than resolving an intent.
 *  * **The interface is synchronous.** Its methods return when the work is done
 *    rather than calling back, so there is no callback plumbing — but every call
 *    blocks, and none of it may touch the main thread. Everything here is a
 *    `suspend` function that moves the work to `Dispatchers.IO` itself.
 *  * **Print bitmaps, not text.** The vendor font cannot shape Arabic. Render
 *    with [com.esite.senraise.receipt.ReceiptRenderer] and print dots; see
 *    [SenraiseSession.text].
 *
 * Cheap to construct and safe to keep as a singleton — it holds no binder
 * between prints. Binding per print is deliberate: a long-lived connection to a
 * vendor service that gets updated or killed underneath you is a connection
 * that is dead exactly when a receipt is wanted, and the bind costs milliseconds.
 */
class SenraisePrinter @JvmOverloads constructor(
    context: Context,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
) {
    // The application context, always: a printer outliving the Activity that
    // created it must not hold it, and bindService from a destroyed context
    // leaks a connection the framework then complains about.
    private val context: Context = context.applicationContext

    /**
     * Whether the vendor's receipt service is installed on this device.
     *
     * Cheap enough to call before showing a Print button, and it never throws.
     * Checked by package, because this service has no intent filter to resolve
     * against — which also means it needs the `<queries>` entry on Android 11+.
     * This module's manifest contributes that entry, so a consumer gets it by
     * depending on the SDK.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.packageManager.getPackageInfo(SenraiseService.PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * The vendor service's version string, or null if there is no service or it
     * refused to answer. Useful in a diagnostics screen; nothing should branch
     * on it, as the format is the vendor's and varies between terminals.
     */
    suspend fun serviceVersion(): String? {
        var version: String? = null
        print { version = serviceVersion }
        return version
    }

    /**
     * Binds the printer, runs [block] against it, and unbinds.
     *
     * The block runs on `Dispatchers.IO` because every session call blocks. It
     * must not escape: the binder is dead once this returns.
     *
     * Never throws for a printing failure — a till that crashes over a paper jam
     * is worse than one that prints nothing. `IllegalArgumentException` from the
     * session's own argument checks (an empty QR payload, mismatched table
     * columns) is a programming error and still arrives as
     * [PrintFailure.IO] with the exception as its cause rather than as a crash.
     */
    suspend fun print(block: SenraiseSession.() -> Unit): PrintResult {
        val bound = try {
            withTimeout(bindTimeoutMs) { bind() }
        } catch (t: TimeoutCancellationException) {
            // Installed but never connected: a service mid-update, or one that
            // has died. Nothing was written, so this is not an IO failure.
            return PrintResult.Failed(PrintFailure.TIMED_OUT, t)
        } catch (t: BindRefused) {
            return PrintResult.Failed(PrintFailure.BIND_REFUSED, t)
        } catch (t: Throwable) {
            return PrintResult.Failed(PrintFailure.IO, t)
        }
        return try {
            withContext(Dispatchers.IO) {
                BoundSession(bound.printer).block()
                PrintResult.Success
            }
        } catch (t: Throwable) {
            PrintResult.Failed(PrintFailure.IO, t)
        } finally {
            // However the print ended — success, failure or the caller's scope
            // being cancelled — the connection goes back.
            bound.release()
        }
    }

    /**
     * The common case: one rasterised receipt, then a feed to clear the
     * tear-off strip.
     *
     * [bitmap] should be [PaperWidth.dots] wide. See [SenraiseSession.bitmap]
     * for why a very tall one should be split across calls.
     */
    @JvmOverloads
    suspend fun printBitmap(
        bitmap: Bitmap,
        feedLines: Int = DEFAULT_FEED_LINES,
    ): PrintResult = print {
        bitmap(bitmap)
        feed(feedLines)
    }

    private suspend fun bind(): Bound {
        val connected = CompletableDeferred<PrinterInterface>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val printer = PrinterInterface.Stub.asInterface(binder)
                if (printer == null) {
                    connected.completeExceptionally(IllegalStateException("null printer binder"))
                } else {
                    connected.complete(printer)
                }
            }

            // Only ever a dead service: the session is scoped to one print, so
            // there is nothing to reconnect. The in-flight call throws a
            // RemoteException of its own and becomes PrintFailure.IO.
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent().apply {
            setClassName(SenraiseService.PACKAGE, SenraiseService.CLASS)
        }
        val started = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!started) {
            // Android still registers the connection even when bindService
            // returns false, and leaks it if we walk away.
            runCatching { context.unbindService(connection) }
            throw BindRefused()
        }
        return try {
            Bound(connected.await(), connection)
        } catch (t: Throwable) {
            runCatching { context.unbindService(connection) }
            throw t
        }
    }

    private inner class Bound(
        val printer: PrinterInterface,
        private val connection: ServiceConnection,
    ) {
        fun release() {
            runCatching { context.unbindService(connection) }
        }
    }

    private class BindRefused :
        IllegalStateException("Senraise printer service refused the bind")

    companion object {
        /**
         * How long a bind may take before it is called a failure. Generous: a
         * cold vendor service on a slow terminal is not quick, and the cost of
         * being wrong is a receipt that silently does not print.
         */
        const val DEFAULT_BIND_TIMEOUT_MS = 5_000L

        /** Enough to clear the last printed line past the tear-off strip. */
        const val DEFAULT_FEED_LINES = 3
    }
}
