# Examples

Focused snippets, in reading order. Each file is one path through the SDK with
the reasoning attached.

| File | Shows |
| --- | --- |
| [`Example01Availability.kt`](Example01Availability.kt) | Is there a printer here? What a diagnostics screen should say |
| [`Example02ReceiptDsl.kt`](Example02ReceiptDsl.kt) | An Arabic receipt, start to finish — the path most apps want |
| [`Example03Bitmap.kt`](Example03Bitmap.kt) | Printing a bitmap you drew yourself, slicing a long one, rendering a preview |
| [`Example04VendorCommands.kt`](Example04VendorCommands.kt) | The vendor's own text, table, barcode and QR calls |
| [`Example05EscPos.kt`](Example05EscPos.kt) | Raw ESC/POS raster, a cash-drawer kick, the raw binder |
| [`Example06ErrorHandling.kt`](Example06ErrorHandling.kt) | What to tell the operator, and what is safe to retry |

**These files are compiled.** `samples/app/build.gradle.kts` adds this directory
as a source dir, so an example that drifts from the API breaks the build instead
of quietly misleading a reader:

```bash
./gradlew :samples:app:assembleDebug
```

They are not referenced by the sample UI — for that, see
[`samples/app`](../samples/app), which puts every path behind a button on one
screen. Install it on a terminal when you want to see paper come out.
