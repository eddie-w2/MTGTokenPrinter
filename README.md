# Token Printer

An Android app that prints Magic: The Gathering tokens as stickers on small, cheap Bluetooth thermal printers.

Browse every token and emblem from Scryfall, pick the art you like, and print it, with the name, rules text,
power/toughness and color identity underneath. Import a deck list to get every token it makes, draw or photograph your
own tokens, and export images for printers the app can't talk to.

**Just want the app?** Download it from the [releases page](https://github.com/eddie-w2/MTGTokenPrinter-releases/releases/latest).
It updates itself after the first install.

## Supported printers
Printers are recognised by their Bluetooth name and grouped by the app they come with:

| Printer's own app | Example models | Status |
| --- | --- | --- |
| Funny Print | LX-D02, LX-D01..D09, XenGro AH-M2 | LX-D02 tested, others experimental |
| Tiny Print / iPrint | GB01..GB05, GT01, X5, X6, X7 | Experimental |
| Fun Print / iBleem | MX05..MX13, YT01, BQ series, MXW01 | Experimental |
| Phomemo | M02, M02X, M02S, M02 Pro, T02 | Experimental |
| ESC/POS | 58 mm and 80 mm receipt printers | Experimental |

"Experimental" means the protocol is implemented but nobody has confirmed it on real hardware yet.
**Printer reports are the most useful contribution you can make**: open an issue with the "Printer report" form.

## Building
1. Android Studio (the build uses AGP 9.4, Gradle 9.8.2 and Kotlin built into AGP). File > Open > pick this folder and let Gradle sync.
2. Plug in an Android phone (8.0 or newer, USB debugging on) and press Run. Emulators have no Bluetooth printers.
3. Without signing settings, builds use the debug key. Release signing reads `TOKENPRINTER_STORE_FILE`,
   `TOKENPRINTER_STORE_PASSWORD`, `TOKENPRINTER_KEY_ALIAS` and `TOKENPRINTER_KEY_PASSWORD` from `~/.gradle/gradle.properties`
   or environment variables. Never commit keys or passwords.

GitHub Actions builds every push. Pushes to `main` of this repository also publish a release for the in-app updater
(release notes come from `CHANGELOG.md`).

## Code map
- `app/src/main/java/com/example/tokenprinter/`
  - `MainViewModel.kt` app state, `App.kt` / `*Screen.kt` the Compose UI
  - `Scryfall.kt` token download, `Raster.kt` layout and dithering, `TokenExport.kt` JPEG export
  - `PrinterClient.kt` scanning and connecting, `PrinterLink.kt` BLE / Classic transport,
    `PrinterDrivers.kt` one driver per printer family, `PrinterCatalog.kt` model detection
  - `Updater.kt` self-updating from the releases repo
- `app/src/main/assets/printers/catalog.json` printer models (regenerate with `tools/build_printer_catalog.py`)
- `tools/printer_check/` checks the drivers' bytes against TiMini-Print on a desktop JVM

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
Token Printer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE). Third-party parts are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Token Printer is unofficial Fan Content permitted under the Wizards of the Coast Fan Content Policy. Not approved or
endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. ©Wizards of the Coast LLC.
Card data and images are provided by Scryfall. The app must stay free; printed tokens are for personal play, not for sale.
