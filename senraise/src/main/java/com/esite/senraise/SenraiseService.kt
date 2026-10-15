package com.esite.senraise

/**
 * Where the vendor's printer service lives.
 *
 * Public because an app that wants to do its own thing — bind the service for a
 * long-lived session, or check for it in a device-capability probe it already
 * has — should not have to copy these strings out of a decompiled APK again.
 */
object SenraiseService {

    /** The package that ships the receipt service on a Senraise terminal. */
    const val PACKAGE = "recieptservice.com.recieptservice"

    /**
     * The service class, bound by name.
     *
     * **The misspelling is the vendor's.** The package really is
     * `recieptservice`, in both the application id and the Java package, and
     * "correcting" it to `receiptservice` here simply fails to bind. The same
     * spelling appears in the AIDL package, which is why the interface this SDK
     * compiles sits under `recieptservice.com.recieptservice` too — an AIDL
     * interface is identified at runtime by its descriptor string, so the
     * package has to match the vendor's exactly or `asInterface` hands back a
     * proxy that every transaction rejects.
     */
    const val CLASS = "recieptservice.com.recieptservice.service.PrinterService"
}
