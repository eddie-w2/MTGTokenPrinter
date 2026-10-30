# Third-party notices

Token Printer is licensed under the GNU General Public License v3.0 or later (see `LICENSE`).
It includes or builds on the following work, under their own licenses.

## TiMini-Print (Apache License 2.0)
Copyright 2026 Daniel Banecki — https://github.com/Dejniel/TiMini-Print

- `app/src/main/assets/printers/catalog.json` is converted from TiMini-Print's printer catalog by `tools/build_printer_catalog.py`.
- The printer protocol code in `PrinterCatalog.kt`, `PrinterDrivers.kt` and `PrinterLink.kt` is ported from TiMini-Print
  (detection rules, the classic cat / V5G / V5X / Funny Print LX / Phomemo job formats, the V5X signing reply).
- Its NOTICE and license text ship in the app: `app/src/main/assets/printers/NOTICE.txt` and `TiMini-Print-LICENSE.txt`.

The Apache License 2.0 is compatible with GPL-3.0; the ported parts keep their Apache notice.

## Cat-Printer (GPL-3.0)
Copyright NaitLee and contributors — https://github.com/NaitLee/Cat-Printer

- The blank-line paper feed for MX05/MX06/MX08/MX09/MX10 printers follows its `problem_feeding` workaround.

## Fonts (SIL Open Font License 1.1)
In `app/src/main/assets/fonts`, with their license texts:
- Finger Paint © 2011 Ralph du Carrois ("Paint")
- Gloria Hallelujah © 2010 Kimberly Geswein ("Notebook")
- Creepster © 2011 Font Diner, Inc ("Spooky")

## Payment logos (CC0 1.0)
The Venmo, Bitcoin and Dogecoin logos in `app/src/main/res/drawable/ic_tip_*.xml` come from Simple Icons
(https://simpleicons.org, CC0 1.0). The brands and trademarks belong to their owners.

## Card data
Card data and images come from the Scryfall API (https://scryfall.com) at run time and are not part of this repository.
Magic: The Gathering is © Wizards of the Coast LLC. Token Printer is unofficial Fan Content permitted under the
Wizards of the Coast Fan Content Policy. Not approved or endorsed by Wizards. It must stay free.
