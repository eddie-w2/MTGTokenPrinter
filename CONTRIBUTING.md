# Contributing to Token Printer

Thanks for helping! The most valuable things right now are **printer reports** and **bug reports**.

## Printer reports
Got a thermal printer? Open an issue with the **Printer report** form and tell us:
- the name it shows in Token Printer's scan list, and the app it came with (Funny Print, Tiny Print, Fun Print, Phomemo…)
- what Token Printer detected it as, and whether the test page printed correctly
- a photo of the test page helps a lot

A confirmed printer moves from "Experimental" to "Tested".

## Bug reports
Use the **Bug report** form, or Settings > Help > Report a bug in the app (it fills in your version and phone).

## Code
- Open an issue first for anything big, so we can agree on the approach.
- Keep pull requests focused; describe how you tested (which phone, which printer).
- New printer families go in `PrinterDrivers.kt` (a driver) and `PrinterCatalog.kt` / `catalog.json` (detection).
- Never commit signing keys, passwords or tokens.
- By contributing you agree that your contribution is licensed under GPL-3.0-or-later, like the rest of the project.
