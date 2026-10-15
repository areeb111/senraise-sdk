# Integration notes

Everything here is about fitting the SDK into a real app: where it lives in your
object graph, what thread it runs on, how it sits alongside other printers, and
how to test it on a machine that has no printer at all.

## Dependency injection

`SenraisePrinter` holds no binder between prints and is cheap to construct, so
one per process is right. With Hilt:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object PrintingModule {

    @Provides
    @Singleton
    fun senraisePrinter(@ApplicationContext context: Context): SenraisePrinter =
        SenraisePrinter(context)

    // Holds the loaded faces, so it is worth sharing rather than rebuilding per
    // receipt.
    @Provides
    @Singleton
    fun receiptRenderer(@ApplicationContext context: Context): ReceiptRenderer =
        ReceiptRenderer(
            ReceiptFonts.fromAssets(
                context,
                regular = "fonts/IBMPlexSansArabic-Medium.ttf",
                medium = "fonts/IBMPlexSansArabic-SemiBold.ttf",
                bold = "fonts/IBMPlexSansArabic-Bold.ttf",
            ),
        )
}
```

Pass the **application** context. The SDK takes `context.applicationContext`
itself, so an Activity cannot be leaked through it, but binding a service from a
destroyed context is still not something to arrange on purpose.

## Threading

The rule the vendor interface imposes: **every call blocks**. The service is
synchronous — its methods return when the head has caught up — so none of it may
touch the main thread.

The SDK handles this. Every entry point is `suspend` and moves its own work:

| Work | Dispatcher | Who chooses |
| --- | --- | --- |
| Package lookup (`isAvailable`) | `IO` | SDK |
| Bind + every session call | `IO` | SDK |
| Receipt layout (`ReceiptRenderer.render`) | `Default` | `printReceipt` does; a direct `render()` call is yours to place |
| Your `print {}` block body | `IO` | SDK |

So `viewModelScope.launch { printer.printReceipt(doc) }` is correct and complete.
Do not wrap SDK calls in `withContext(Dispatchers.IO)` — it is already done, and
nesting it hides where the work actually happens.

`print {}` is cancellation-safe: if the calling scope dies mid-print the service
is unbound in a `finally`. The paper already moving cannot be recalled, which is
the other half of why a print must never be the record of a sale.

## Sitting alongside other printers

Most POS apps support more than one route to paper. The arrangement that works on
this class of hardware, learned the hard way:

1. **The built-in head first.** On a terminal that has one it is the printer the
   operator means, it needs no pairing, and it cannot be stolen by a Bluetooth
   device someone paired to the till last week.
2. **Then an external Bluetooth printer.**
3. **Then the platform print spooler** — but never silently, because it opens a
   dialog. Reach it only when the operator asked for it, or when nothing else
   exists.

**Do not fall back to writing `/dev/ttyS1` yourself.** On these terminals the
built-in head is not a free-for-all character device: the vendor service holds
the port open, sets the flow control and is the only writer the MCU expects.
Writing the node directly interleaves your bytes with the daemon's mid-command,
which desynchronises the printer's parser and produces a receipt of literal
control characters. If a board genuinely exposes a bare port, make it an explicit,
warned-about operator choice — not an automatic fallback.

A sketch of the selector:

```kotlin
suspend fun preferred(): VoucherPrinter =
    senraise.takeIf { it.isAvailable() }
        ?: bluetooth.takeIf { it.isAvailable() }
        ?: systemSpooler
```

Keep a `transport` enum in settings so an operator can pin one, and keep every
transport disposable — the app must survive a device with no printer at all.

### Two vendors, one build

IPOS terminals (the blue units) have a different service,
`com.iposprinter.iposprinterservice`, with a different and *asynchronous*
interface. Only one of the two services is ever present on a given device, so
both transports can be offered unconditionally and the absent one reports itself
unavailable. If you support both, each needs its own `<queries>` entry — this
SDK contributes only the Senraise one.

## Paper width and RTL must agree with your UI

Two settings leak into the printer and both have to agree with the rest of the
app:

- **Paper width** is in dots, and the renderer scales everything from it. Store
  `PaperWidth` (or its `dots`) in settings, and use `PaperWidth.ofDots()` when
  reading back a raw number.
- **RTL** must follow the *app's* locale, not the device's. If your app lets the
  user pick Arabic while the device is English, resolve the receipt's labels and
  its `rtl` flag through the same locale — otherwise the strings come out in one
  language laid out for the other.

## R8 / ProGuard

Nothing to add. `consumer-rules.pro` keeps `PrinterInterface` and its
`Stub`/`Proxy` unrenamed, because they are reached through Binder by descriptor
rather than by a Java reference. `samples:app` builds with `minifyEnabled true`,
so the rules are exercised by the repo's own build; the release mapping shows the
interface, `Stub`, `Stub$Proxy`, `Default` and `_Parcel` all surviving under
their original names.

If you write your own `-keep` policy that strips library consumer rules, keep
these:

```proguard
-keep interface recieptservice.com.recieptservice.PrinterInterface { *; }
-keep class recieptservice.com.recieptservice.PrinterInterface$* { *; }
```

## Testing without hardware

The parts worth unit-testing are the parts with no Android in them, and they are
separated for exactly that reason:

- `QrCode` is pure arithmetic on a module grid — module size, quiet zone, fit
  against the hint.
- The `receipt {}` DSL is a plain object graph.
- `EscPosRaster` needs only `Bitmap.getPixels`, which Robolectric provides.

For the printer itself, depend on an interface in your own code rather than on
`SenraisePrinter` directly:

```kotlin
interface VoucherPrinter {
    suspend fun isAvailable(): Boolean
    suspend fun print(document: ReceiptDocument): PrintResult
}
```

Then a fake returns `PrintResult.Failed(PrintFailure.IO)` and you can assert that
the sale was still saved and a reprint still offered — which is the behaviour that
actually matters, and it cannot be tested through a real binder.

On-device, the sample app is the test: install it on a terminal and all five
paths are one tap each.

## Checklist for a new app

- [ ] `implementation("com.esite.senraise:senraise-printer:1.0.0")`
- [ ] Nothing added to the manifest — confirm `<queries>` merged, don't re-declare it
- [ ] `SenraisePrinter` provided as a singleton with the application context
- [ ] A `ReceiptRenderer` singleton, with bundled Arabic faces if the receipts are Arabic
- [ ] Paper width and RTL read from the same settings the UI uses
- [ ] Sale saved **before** the print call
- [ ] Every `PrintFailure` mapped to something an operator can act on
- [ ] A reprint route that does not depend on the first print having worked
