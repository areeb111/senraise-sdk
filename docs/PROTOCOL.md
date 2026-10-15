# The vendor protocol

What the Senraise receipt service actually exposes, and the things about it that
are not obvious until a receipt comes out wrong. Read this if you are debugging a
print, or if you want to bind the service yourself instead of using
`SenraisePrinter`.

## Finding the service

| | |
| --- | --- |
| Package | `recieptservice.com.recieptservice` |
| Service class | `recieptservice.com.recieptservice.service.PrinterService` |
| AIDL interface | `recieptservice.com.recieptservice.PrinterInterface` |
| Permission | none |

**The misspelling is the vendor's.** It really is `reciept` — in the application
id, the Java package and the AIDL package. Correcting it fails to bind, and
"correcting" the AIDL package is worse: an AIDL interface is identified at runtime
by its descriptor string, so a renamed package yields a proxy whose every
transaction is rejected. The file in `senraise/src/main/aidl` must stay exactly
where it is.

**It is bound by class name, not by action.** The service publishes no intent
filter, so there is nothing to resolve:

```kotlin
val intent = Intent().apply {
    setClassName(
        "recieptservice.com.recieptservice",
        "recieptservice.com.recieptservice.service.PrinterService",
    )
}
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

The same absence is why presence is tested with `getPackageInfo` rather than by
resolving an intent — and why Android 11+ needs the `<queries>` declaration or
the lookup throws `NameNotFoundException` on the very terminals that have the
printer.

`bindService` returning `false` means the service exists but the system refused
to start it — usually a disabled package. Android still registers the connection
in that case, so it must be unbound anyway or it leaks.

## The interface

```java
interface PrinterInterface {
    void printEpson(in byte[] data);
    String getServiceVersion();
    void printText(String text);
    void printBitmap(in Bitmap pic);
    void printBarCode(String data, int symbology, int height, int width);
    void printQRCode(String data, int modulesize, int errorlevel);
    void setAlignment(int alignment);
    void setTextSize(float textSize);
    void nextLine(int line);
    void printTableText(in String[] text, in int[] weight, in int[] alignment);
    void setTextBold(boolean bold);
}
```

The copy in this repo adds one line the vendor omitted: `import
android.graphics.Bitmap`. The interface takes a `Bitmap` without importing it,
which only some `aidl` toolchain versions tolerate.

### Semantics worth knowing

**Every method is synchronous.** It returns when the work is done rather than
calling back. There is no callback plumbing to write — and no call may touch the
main thread.

**Nothing returns a status.** No paper sensor, no cover switch, no error codes,
no return values at all beyond `getServiceVersion`. A failure surfaces only as a
thrown `RemoteException` or as paper that does not move, which is why
`PrintFailure` in this SDK is coarse: anything finer would be invented.

**State persists across calls and across clients.** `setAlignment`,
`setTextSize` and `setTextBold` apply to everything printed after them, and
nothing resets between prints — the service is shared with whatever else on the
device prints. Set what you depend on rather than inheriting it.

### Per-method notes

| Method | Notes |
| --- | --- |
| `printText` | The service's own font. **Cannot shape Arabic** — codepoint by codepoint, so letters arrive unjoined, in visual order, often as tofu. Append `\n` yourself. |
| `printBitmap` | One source pixel is one dot. Binarised internally, so there is no threshold to set. Clipped at the right edge if wider than the paper. Crosses Binder whole — see the size limit below. |
| `printQRCode` | `modulesize` is the module size in dots; below 3 a module lands on too few dots to survive the paper. `errorlevel` is 0–3 = L, M, Q, H. |
| `printBarCode` | `symbology` is the ESC/POS `GS k` index (0 UPC-A, 1 UPC-E, 2 EAN13, 3 EAN8, 4 CODE39, 5 ITF, 6 CODABAR, 7 CODE93, 8 CODE128). `height` is in dots; `width` is the narrow-bar multiplier, 2–3 on 58mm. **No payload validation** — a string that does not fit the symbology prints blank or truncated rather than failing. |
| `printTableText` | The three arrays must be the same length; `weight` entries are relative column widths. Latin only, same as `printText`. |
| `setTextSize` | The service's own units, roughly pixels at 203dpi. |
| `nextLine` | Blank line feed. **Needed at the end of every receipt**: the last printed line sits under the head, not past the tear-off strip, so without it the tear cuts through whatever printed last — usually the QR. Three lines clears it. |
| `printEpson` | Raw ESC/POS passthrough, uninterpreted. The one call that can strand the parser in a state the next client does not expect — bracket it with `ESC @`. |
| `getServiceVersion` | Vendor-formatted and inconsistent between terminals. Diagnostics only; never branch on it. |

### Not available

No cutter control, no cash-drawer method, no density or speed setting, no paper
status, no bitmap scaling, no page mode. A drawer kick and anything else the
interface omits goes through `printEpson` as raw ESC/POS — see
[`examples/Example05EscPos.kt`](../examples/Example05EscPos.kt).

## Geometry

| Paper | Printable width | Dots | 1 dot |
| --- | --- | --- | --- |
| 58mm | ~48mm | 384 | 0.125mm (203dpi) |
| 80mm | ~72mm | 576 | 0.125mm |

Everything is addressed in dots. A design expressed in millimetres or in `sp`
will not survive a change of paper; a design expressed as fractions of the width
will.

## Limits

**Binder transaction size.** A bitmap crosses the boundary whole against a
buffer of about 1MB, shared across the process's in-flight transactions. At 384
dots that is roughly 650 rows of `ARGB_8888` before
`TransactionTooLargeException`; at 576 dots, roughly 430. Split a long receipt
across several `printBitmap` calls inside one bound session — there is no feed
between them, so the seam is invisible.

**Service lifetime.** The vendor service can be updated or killed underneath a
long-lived connection, leaving you a dead binder exactly when a receipt is
wanted. This SDK binds per print for that reason; the bind costs milliseconds.

**One writer.** The service owns the serial port and the flow control. Do not
also write `/dev/ttyS1` — interleaved bytes desynchronise the printer's parser
and the paper fills with literal control characters.

## Raw ESC/POS, if you go that way

`GS v 0` — raster mode — is the right command, not the older `ESC *` column
mode: the printer handles the line feeds, which is faster and immune to the
drifting vertical gaps column mode produces when a line is sent a dot-row short.

```
GS v 0 m xL xH yL yH d1…dk
  m  = 0 (normal)
  xL/xH = width in BYTES, little endian ((dots + 7) / 8)
  yL/yH = height in dot rows, little endian
  data  = rows, MSB first, 1 = fire a dot
```

Pad the last byte of a row with white when the width is not a multiple of 8 —
padding it black puts a bar down the edge of every receipt. `EscPosRaster` does
all of this, including the Rec. 601 luma threshold at 200 that keeps antialiased
glyphs at their drawn weight.
