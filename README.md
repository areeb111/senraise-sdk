# Senraise POS Printer SDK

A Kotlin SDK for the thermal print head built into **Senraise** Android POS
terminals — the black handheld units — with a receipt renderer that prints
Arabic correctly.

Extracted from the printing layer of a POS app running on these terminals in the
field, so what is here is what survived contact with the hardware: the real
service name (misspelling included), the bind-by-class-name quirk, the threading
rules, and the typography decisions that make a 384-dot one-bit head legible.

```kotlin
val printer = SenraisePrinter(context)
if (!printer.isAvailable()) return          // not a Senraise terminal

printer.printReceipt(
    receipt {
        title("مقهى الرشيد")
        rule()
        row("قهوة عربية", "٣٠٠٠")
        row("الإجمالي", "٣٠٠٠", TextStyle.HEADING)
        code("8830-1012-6346")
        qr("8830-1012-6346")
    },
    paper = PaperWidth.MM58,
    rtl = true,
)
```

---

## What this is, and what it is not

The print head on these terminals is **not** a character device your app may
open. A vendor system service (`recieptservice.com.recieptservice`) owns the
serial port, sets the hardware flow control, and is the only writer the printer's
MCU expects. The only legitimate way in is AIDL, and this SDK is the whole of
that conversation:

| It does | It does not |
| --- | --- |
| Find, bind and release the vendor service | Talk to Bluetooth, USB or serial printers |
| Wrap every AIDL call in a typed, validating Kotlin API | Hide the raw binder — `session.raw` is public |
| Render receipts (including Arabic) to dots | Ship fonts; bring your own or use the platform's |
| Report failures as values, never exceptions | Retry on your behalf |

If you need a general ESC/POS library for external printers, this is the wrong
repo. If you have a Senraise terminal and want its built-in head to print real
receipts, this is the whole job.

### Supported hardware

Any Senraise Android POS terminal whose firmware includes the
`recieptservice.com.recieptservice` package — the handhelds with the integrated
58mm head. 80mm paper is supported by the renderer (`PaperWidth.MM80`, 576 dots)
for terminals and desktop units configured that way.

`isAvailable()` is the authority: it answers honestly on every other device,
including emulators, so a single build can ship to mixed hardware.

---

## Install

The SDK is one Android library module, `:senraise`. Three ways to consume it,
easiest first.

**1. Maven local** — good for a single machine or a quick trial:

```bash
git clone https://github.com/areeb111/senraise-sdk.git
cd senraise-sdk
./gradlew :senraise:publishToMavenLocal
```

```kotlin
// settings.gradle.kts of the consuming app
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}

// build.gradle.kts
dependencies {
    implementation("com.esite.senraise:senraise-printer:1.0.0")
}
```

**2. Source dependency** — the SDK builds with the app, so you can step into it
and patch it:

```bash
git submodule add https://github.com/areeb111/senraise-sdk.git libs/senraise-sdk
```

```kotlin
// settings.gradle.kts
include(":senraise")
project(":senraise").projectDir = file("libs/senraise-sdk/senraise")

// build.gradle.kts
dependencies { implementation(project(":senraise")) }
```

**3. GitHub Packages** — for CI. `publishing` in
[`senraise/build.gradle.kts`](senraise/build.gradle.kts) adds the repository when
`GITHUB_ACTOR` and `GITHUB_TOKEN` are in the environment:

```bash
GITHUB_ACTOR=areeb111 GITHUB_TOKEN=$TOKEN ./gradlew :senraise:publishReleasePublicationToGitHubPackagesRepository
```

### Requirements

| | |
| --- | --- |
| `minSdk` | 24 |
| `compileSdk` | 36 |
| Kotlin | 2.1 (coroutines required — the API is `suspend`) |
| Java | 11 bytecode |
| Transitive deps | `kotlinx-coroutines-android`, `androidx.annotation`, `com.google.zxing:core` (QR encoding only) |

**Nothing to add to your manifest.** The `<queries>` entry that makes the vendor
package visible on Android 11+ ships in the library's own manifest and merges
into your app — verified in the sample's merged manifest. No permission is
needed to bind the service.

**Nothing to add to your R8 config.** `consumer-rules.pro` keeps the AIDL stub
and its proxy unrenamed; the sample app builds with `minifyEnabled true` to prove
it.

---

## Usage

### Availability

```kotlin
val printer = SenraisePrinter(context)      // cheap; keep one per process

if (printer.isAvailable()) {
    showPrintButton()
}
printer.serviceVersion()                     // diagnostics only — vendor format
```

`isAvailable()` is a package lookup. It never throws and is cheap enough to call
before showing a button.

### The receipt DSL

A receipt is a *description*, not a layout — sizes are styles and widths are
fractions, so the same receipt renders at 384 or 576 dots without being
rewritten:

```kotlin
val doc = receipt {
    title("Rashid Coffee")              // TITLE, centred
    heading("Rashid St, Baghdad")
    small("0770 123 4567")
    rule()                              // 3 dots solid, with air above and below

    row("Arabic coffee", "3,000")       // label start, value end; mirrors under RTL
    row("Mint tea", "1,500")
    rule()
    row("Total", "4,500", TextStyle.HEADING)

    gap(1.5f)                           // in typographic units (1/48 of the width)
    code("8830-1012-6346")              // auto-fitted to one line, never wraps
    qr("8830-1012-6346")                // integer-scaled modules
    image(logoBitmap, widthRatio = 0.4f)
    small("Thank you")
}

printer.printReceipt(doc, PaperWidth.MM58, rtl = false)
```

`printReceipt` renders on `Dispatchers.Default`, prints on `Dispatchers.IO` and
recycles the page bitmap, so calling it straight from a `viewModelScope` is
correct.

### A bitmap you drew yourself

```kotlin
printer.printBitmap(page, feedLines = 3)
```

One source pixel is one dot, so `page.width` should be `PaperWidth.dots`. A tall
receipt must be split — the image crosses the Binder boundary in one transaction
against a ~1MB buffer, which at 384 dots is roughly 650 rows of ARGB_8888. See
[`examples/Example03Bitmap.kt`](examples/Example03Bitmap.kt) for the slicing
helper.

### Vendor commands, for Latin-only output

```kotlin
printer.print {
    align(Alignment.CENTER)
    textSize(40f)
    bold(true)
    text("ORDER 42\n")
    bold(false)

    align(Alignment.LEFT)
    table(
        cells = listOf("Item", "Qty", "Price"),
        weights = listOf(4, 1, 2),
        alignments = listOf(Alignment.LEFT, Alignment.CENTER, Alignment.RIGHT),
    )

    align(Alignment.CENTER)
    barcode("42", Symbology.CODE128, heightDots = 80, width = 2)
    qrCode("42", moduleDots = 6, errorLevel = QrErrorLevel.M)
    feed(3)
}
```

Everything inside the block runs against one bound service and unbinds on exit —
including on an exception or a cancelled scope.

### Raw ESC/POS and the raw binder

```kotlin
printer.print {
    escPos(EscPosRaster.RESET)                   // ESC @
    escPos(EscPosRaster.raster(bitmap))          // GS v 0
    raw.printText("…")                           // the generated AIDL interface
    feed(3)
}
```

### Failure handling

Printing returns a value; it never throws:

```kotlin
when (val result = printer.printReceipt(doc)) {
    is PrintResult.Success -> Unit
    is PrintResult.Failed -> when (result.reason) {
        PrintFailure.UNAVAILABLE   -> "This device has no built-in printer."
        PrintFailure.BIND_REFUSED  -> "The printer service is disabled."
        PrintFailure.TIMED_OUT     -> "The printer did not respond. Try again."
        PrintFailure.IO            -> "Check the paper and the cover, then reprint."
    }
}
```

Only `TIMED_OUT` is safely retryable: it is the one failure that guarantees
nothing reached the paper. Retrying `IO` can print a receipt twice. And print is
never the record — save the sale first, print second, keep a reprint route open.

---

## Arabic

**The vendor's `printText` cannot print Arabic.** It renders a string codepoint
by codepoint, so letters arrive unjoined, in visual order, and often as tofu.
There is no flag to fix it.

The way through is to let Android do the shaping and send the printer dots:
`ReceiptRenderer` lays the text out with `StaticLayout`, which handles joining,
bidi and mixed Latin runs, then prints the result as a bitmap. That is why the
renderer exists and why it is the default path in this SDK.

`rtl = true` sets the paragraph direction and mirrors the rows; the Arabic is
shaped either way. For a branded or more reliable face, bundle one:

```kotlin
val renderer = ReceiptRenderer(
    ReceiptFonts.fromAssets(
        context,
        regular = "fonts/IBMPlexSansArabic-Medium.ttf",
        medium  = "fonts/IBMPlexSansArabic-SemiBold.ttf",
        bold    = "fonts/IBMPlexSansArabic-Bold.ttf",
    )
)
```

Faces that fail to load fall back to the platform family — a missing font costs
the receipt its brand, not its print.

### Why the receipts look the way they do

Every typographic decision in `ReceiptRenderer` is a one-bit thermal head
decision, and most of them were learned by reprinting:

- **Hierarchy from size *and* weight.** At 384 dots through a hard threshold, a
  two-point size change alone is a rounding error, not a hierarchy.
- **Rules are 3 dots and solid.** A hairline prints as a dashed ghost on one
  printer and as nothing on the next.
- **No greys, anywhere.** Grey can only be a dither pattern, and dithered small
  text turns to mud. A muted line is muted by being *smaller*.
- **No synthetic bold.** `isFakeBoldText` smears an already-thick glyph instead
  of thickening it — worst exactly where it matters most, small Arabic.
- **A real margin** (5.5% each side). Feed skew and the head's own edge fade eat
  anything that runs to the paper edge.
- **Codes shrink before they wrap.** A wrapped voucher code reads as two numbers
  and gets transcribed wrong, so the hero line gives up letter tracking down to a
  floor, then size — tracking last, because it is what keeps `0/O`, `1/7` and
  `5/S` apart.
- **QR modules are a whole number of dots.** Fractional scaling makes modules 4
  dots wide in one column and 5 in the next; the decoder reads the drift as
  distortion and the symbol fails at exactly the angles a handheld scan is held
  at.
- **ESC/POS ink threshold is luma < 200, not 128.** Antialiased glyphs are ringed
  by partial greys; cutting at a mid grey throws the ring away and every letter
  loses its outer pixel — the "thin and broken" look. 200 keeps the drawn weight.
  Above ~220 the ring around counters (ه, م, ق, o, e) fires too and small Arabic
  fills in.

---

## API surface

| Type | Purpose |
| --- | --- |
| `SenraisePrinter` | Find, bind, print, release. `isAvailable`, `serviceVersion`, `print {}`, `printBitmap` |
| `SenraiseSession` | The bound printer inside `print {}`: `text`, `bitmap`, `qrCode`, `barcode`, `table`, `align`, `textSize`, `bold`, `feed`, `escPos`, `raw` |
| `PrintResult` / `PrintFailure` | Outcomes as values: `Success`, or `Failed(reason, cause)` |
| `PaperWidth`, `Alignment`, `Symbology`, `QrErrorLevel` | Typed vendor constants |
| `receipt {}` / `Receipt` / `ReceiptBlock` | The receipt description and its DSL |
| `ReceiptRenderer` / `ReceiptFonts` | Receipt → bitmap, with Arabic shaping |
| `QrCode` | Integer-scaled QR symbol as dots (pure Kotlin, unit-tested) |
| `EscPosRaster` | Bitmap → `GS v 0` bytes, Rec. 601 luma threshold |
| `SenraiseService` | The vendor package and class names, if you want to bind it yourself |

Full KDoc is on every public type — the *why* lives there, not just the what.

---

## Repo layout

```
senraise/                     the SDK (Android library, :senraise)
  src/main/aidl/              the vendor's PrinterInterface.aidl, compiled for you
  src/main/AndroidManifest.xml   the <queries> entry, merged into consumers
  src/main/java/…/senraise/   SenraisePrinter, SenraiseSession, options, results
  src/main/java/…/receipt/    Receipt DSL, ReceiptRenderer, QrCode
  src/main/java/…/escpos/     EscPosRaster
  src/test/                   unit tests (QR geometry, DSL, paper widths)
samples/app/                  runnable sample: every path on one screen
examples/                     focused, documented snippets — compiled by the build
docs/INTEGRATION.md           DI, threading, multi-printer fallback, R8
docs/PROTOCOL.md             the AIDL surface and the vendor's quirks
```

`examples/` is compiled as part of `samples:app`, so a snippet that drifts from
the API breaks the build instead of misleading a reader.

## Building

```bash
./gradlew :senraise:assembleRelease          # the AAR
./gradlew :senraise:testDebugUnitTest        # unit tests
./gradlew :samples:app:assembleDebug         # sample + examples
./gradlew :samples:app:assembleRelease       # proves the R8 consumer rules
./gradlew :senraise:publishToMavenLocal
```

Install the sample on a terminal to see all five paths — availability, Arabic
receipt, Latin receipt, vendor font, raw ESC/POS — on one screen.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `isAvailable()` is false on a real terminal | The vendor package is missing or disabled, or your build stripped the merged `<queries>` entry. Check the merged manifest. |
| `PrintFailure.TIMED_OUT` every time | The service is installed but not starting — often mid-update, or disabled by MDM. Reboot the terminal; check `pm list packages -d`. |
| `TransactionTooLargeException` as an `IO` cause | One bitmap too tall for the Binder buffer. Slice it (`Example03Bitmap.kt`). |
| Arabic prints unjoined or as boxes | You are using `text()`. Use `printReceipt` / `printBitmap`. If it is already rasterised, the device's Arabic fallback font is poor — bundle one via `ReceiptFonts.fromAssets`. |
| The QR is cut in half by the tear-off | No feed at the end. `feed(3)` — `printReceipt` and `printBitmap` do it for you. |
| Receipt prints thin and broken | A mid-grey ink threshold somewhere in your own rasteriser. See `EscPosRaster.INK_THRESHOLD`. |
| `NoSuchMethodError` from a vendor call | An older service build without that method. Wrap it: the SDK turns it into `PrintFailure.IO` rather than a crash. |

## Licence

MIT — see [LICENSE](LICENSE).

`recieptservice.com.recieptservice` and the `PrinterInterface` AIDL are Senraise's
own; the AIDL file here is the interface definition needed to talk to the
vendor's service and is included unmodified apart from an `android.graphics.Bitmap`
import the vendor omitted. This project is not affiliated with or endorsed by
Senraise.
