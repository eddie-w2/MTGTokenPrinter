# Token Printer (Android) 0.6

Browse every Scryfall token, favorite the ones you use, build deck folders, and print the art straight to
the YTETCN / "Funny Print" thermal printer (shows up as LX-D02) over Bluetooth LE. No Funny Print app needed.

## Open and run
1. Android Studio Quail 4 (2026.1.4) or newer (the build uses AGP 9.4, Gradle 9.8.2, built-in Kotlin). File > Open > pick this folder, let Gradle sync.
2. Plug in an Android phone (USB debugging on) and press Run (Android 8.0+, real phone, emulators have no BLE).
3. Shareable APK: Build > Build APK(s). It lands in app/build/outputs/apk/debug/.

Updating from 0.1: replace the whole project folder (or copy every file over, and delete the old `Ui.kt`), then
File > Sync Project with Gradle Files. Your favorites survive because they live on the phone, not in the project.

## What's new in 0.6
- Custom photos can be cropped: Wide, Square, Tall or Whole, drag to move, pinch or slider to zoom.
- Custom tokens take extra text, printed under the name. Text options has "Show full rules text" for any token.
- Three bundled fonts (Notebook, Paint, Spooky; SIL Open Font License, licenses in Settings) next to the built-in Android fonts, and a Scratched effect font.
- Goblin Mode sits next to Copies and no longer skews auto contrast.
- Darkness moved to Settings > Printer and is remembered per printer model.
- Legal notices at the bottom of Settings.

## What was in 0.5
- Release date sorting lives in the Sort menu. Emblems print their rules text under the name.
- **Custom tokens**: tap the brush button. Draw with a finger or pick a photo (previewed as it will print). Type a name and tap a matching
  token to add the picture as another art for it; otherwise it goes in the **Custom** folder (filter chip). Same-name customs share one entry.
  Any token's detail screen also has "Add your own art".
- **Goblin Mode**: button in the print dialog cycles 10x, 25x, 50x, 100x, off. The quantity is printed in a corner label.
- **Printer status**: battery, paper, heat and low-voltage warnings (printer chip and Printer dialog). Battery scale is unverified; check the log.
- **Auto contrast** toggle (Settings > Image quality), off by default.
- **Printer models**: Settings > Printer model. Width and feed come from a profile, so new printers are one entry in PrinterProfile.kt.
- **Backup and restore** in Settings (favorites, decks, custom tokens, settings). Restoring only adds, never deletes.
- **Share**: send a deck list or Moxfield link to the app from any app's share sheet; share a deck's token list from the deck screen.

## What was in 0.4
- Printer tuning (reverse bit order, handshake, remove white space) lives only in Settings. **Invert** stays under Image quality.
- Keyword abilities (Flying, Vigilance...) show on every token, above power/toughness, and print on the card (toggle in Text options).
- Official-style Magic colors in the color filter (black is a deep purple).
- Sort button (A-Z, Z-A, release date newest/oldest) and a Filters button (type, set list newest first) next to the grid/list button.
- Emblems are included. The first launch after updating re-downloads the token list to pick up emblems and keywords.

## What was in 0.2
- **Thumbnails** on every token, with a User-Agent on image requests (Scryfall asks for one) and a 300 MB image cache.
- **Favorite printings**: heart a token, or heart individual arts inside it. A favorite printing becomes that token's thumbnail
  and the default art when a deck is imported. Both kinds pin to the top of the list.
- **Settings tab**: last synced date/time (plus "x days ago"), Sync now, caption options, image quality, printer tuning.
- **Caption text**: size slider (14-72), six fonts (Sans, Serif, Mono, Condensed, Handwriting, Casual), bold, show/hide P/T.
  The preview in the print dialog updates live.
- **Decks**: each deck is a folder of tokens with quantities and a chosen art for each. "Print all" prints the lot.
  You can also add any token to a deck from the print dialog.
- **Deck import**: paste a list, open a .txt file, or paste a Moxfield link (see below).
- **Launcher icon**: Themed (default, also supports Android 13+ themed icons) or Color. Switch in Settings > App icon.
- **Printing animation** and a restyled app (bottom navigation, grid/list view, dark and light themes).

## Deck import
Scryfall links every card to the tokens it makes. The app looks your cards up in batches, collects those tokens, and matches
them to your synced token list. Works with plain names, "1 Sol Ring", "1x Sol Ring (CMM) 400 *F*", Arena and Moxfield text exports.
Maybeboard / considering sections are skipped.

Moxfield links are best effort. Moxfield only lets approved apps use its API, so the link may be refused. If so, open the deck in
Moxfield, use Export > copy as text, and paste it into the import box. That path always works.

## First test checklist (printer)
1. Connect, then "Test page". Expect: solid block at the LEFT edge, outlined box at the RIGHT edge, "TEST ->" text, grey ramp dark to light.
2. Everything is a negative? Turn on **Invert**. Scrambled or mirrored in thin strips? Turn on **Reverse bit order**.
3. Nothing prints? Close Funny Print first (it holds the connection), then check the Bluetooth log in the Printer dialog.
4. Too pale or dark: Darkness and Contrast sliders.

## Files
- Scryfall.kt: API paging, grouping by token, disk cache
- CustomEditor.kt, CustomTokens.kt, Backup.kt, PrinterProfile.kt: custom tokens, backup, printer models
- Raster.kt: scale to the printer width (384 dots), caption (fonts/size), Floyd-Steinberg dither, 1-bit packing
- PrinterClient.kt: BLE scan/connect, handshake, packet streaming
- Decks.kt, DeckImport.kt: deck storage, list parsing, Moxfield and Scryfall lookups
- MainViewModel.kt: app state and actions
- AppIcon.kt: launcher icon switcher (activity-aliases in the manifest; art in res/drawable/ic_fg_*.xml).
- TokenApp.kt: image loader. Theme.kt, Components.kt: look and shared widgets
- App.kt, TokensScreen.kt, DecksScreen.kt, SettingsScreen.kt, PrinterDialog.kt, MainActivity.kt: screens
