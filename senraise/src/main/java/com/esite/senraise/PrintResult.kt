package com.esite.senraise

/**
 * Why a print did not happen, in terms a shop assistant can act on.
 *
 * Deliberately coarse. The vendor interface reports nothing about the hardware —
 * no paper sensor, no cover switch, no return codes — so anything finer than
 * this would be invented. What the caller can genuinely distinguish is "there is
 * no printer of this kind here" from "there is one and talking to it failed",
 * and that distinction is the whole reason this enum exists: the first is a
 * device fact that should hide a Print button, the second is a fault worth
 * retrying or escalating.
 */
enum class PrintFailure {
    /**
     * No Senraise receipt service on this device.
     *
     * Either the terminal is not a Senraise unit, or the host app forgot the
     * `<queries>` manifest entry — on Android 11+ those look identical from
     * inside the process. The SDK's own manifest supplies the entry, so an app
     * that depends on this module gets it merged in automatically.
     */
    UNAVAILABLE,

    /**
     * `bindService` returned false: the service exists but the system refused to
     * start it. Seen on a locked-down build where the vendor package has been
     * disabled rather than removed.
     */
    BIND_REFUSED,

    /**
     * Installed, bound, but never connected inside the timeout — a service
     * mid-update, or one that has died and is not being restarted. Nothing was
     * written to paper, so this is not an IO failure.
     */
    TIMED_OUT,

    /**
     * The call reached the service and threw. A `RemoteException` because the
     * service died mid-print, a `TransactionTooLargeException` from a bitmap too
     * big for the Binder buffer, or the vendor code failing on its own.
     */
    IO,
}

/**
 * The outcome of one print.
 *
 * Printing never throws out of this SDK: a till that crashes because the printer
 * is out of paper is worse than one that prints nothing, and the caller almost
 * always wants to carry on and offer a reprint. Everything is funnelled into
 * [Failed] with the original [Failed.cause] kept for logs.
 */
sealed interface PrintResult {

    data object Success : PrintResult

    data class Failed(
        val reason: PrintFailure,
        val cause: Throwable? = null,
    ) : PrintResult

    val succeeded: Boolean get() = this is Success
}
