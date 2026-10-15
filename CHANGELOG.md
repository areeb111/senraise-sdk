# Changelog

## 1.0.0 — 2026-10-16

First release. Extracted from the printing layer of a POS app running on Senraise
terminals in the field.

- `SenraisePrinter`: availability by package lookup, bind-by-class-name, per-print
  binding with a 5s timeout, guaranteed unbind, failures as values.
- `SenraiseSession`: typed, validating wrappers over every `PrinterInterface`
  method, plus `raw` for anything a vendor build adds.
- `receipt {}` DSL and `ReceiptRenderer`: Arabic-capable receipts rasterised for a
  one-bit 203dpi head, at 384 or 576 dots.
- `QrCode`: integer-scaled module grid, pure Kotlin, unit-tested off-device.
- `EscPosRaster`: `GS v 0` conversion with a Rec. 601 luma threshold at 200.
- `<queries>` entry and R8 consumer rules ship in the library, so consumers add
  nothing to their manifest or ProGuard config.
- Sample app covering all five paths; `examples/` compiled by the build.
