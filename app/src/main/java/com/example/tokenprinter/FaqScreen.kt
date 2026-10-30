@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private class FaqEntry(val q: String, val a: String)

private class FaqGroup(val title: String, val items: List<FaqEntry>)

private val faqGroups = listOf(
    FaqGroup(
        "Getting started",
        listOf(
            FaqEntry(
                "What does Token Printer do?",
                "It lists every Magic: The Gathering token and emblem from Scryfall, lets you pick the artwork you like, and prints it as a sticker on a small Bluetooth thermal printer.",
            ),
            FaqEntry(
                "What printers are currently supported?",
                "It is built for the YTETCN / Funny Print printers, which show up as LX-D02.\n" +
                    "There are plans to add a lot more, it's just still in the works.\n" +
                    "\n" +
                    "For printers it can't connect to yet, you can export a token as a JPEG and print it from that printer's own app (see Exporting images).",
            ),
            FaqEntry(
                "Why does it download tokens the first time I open it?",
                "The first launch downloads the token list from Scryfall. That needs internet and can take a minute. After that the list is saved on your phone, so you can browse without a connection.\n" +
                    "\n" +
                    "New tokens are added with every set release. Go to Settings > Sync now to refresh the list.",
            ),
            FaqEntry(
                "How do I connect my printer?",
                "1. Switch the printer on.\n" +
                    "2. Close the default printing app you use if it is open. Only one app can talk to the printer at a time.\n" +
                    "3. Tap the Connect button at the top right of the app.\n" +
                    "4. Tap Scan, then pick your printer. It should say 'Likely your printer'.\n" +
                    "5. Allow the Bluetooth permission when Android asks.\n" +
                    "\n" +
                    "The button changes to Ready once it is connected, and the app remembers the printer for next time. Settings > Printer > Test page prints a pattern so you can check everything works. If there are any issues, there are additional printer settings within the settings menu.",
            ),
            FaqEntry(
                "How do I print my first token?",
                "On the Tokens tab, tap a token, then tap the printing (artwork) you like. A print screen opens with a preview of exactly what will print. Tap Print.\n" +
                    "\n" +
                    "Use the Copies buttons at the bottom left for more than one. For a shortcut, press and hold a token (see Quick print).",
            ),
        ),
    ),
    FaqGroup(
        "Finding tokens",
        listOf(
            FaqEntry(
                "How do I search and filter?",
                "The search box matches names, types (like Zombie or Treasure), keywords and set names. When you change the search or a filter, the list jumps back to the top.\n" +
                    "\n" +
                    "Under the search box are chips for Favorites, Custom (your own tokens) and the five colors plus colorless (W U B R G C). Tap more than one color to see tokens of any of them.\n" +
                    "\n" +
                    "The Filter button at the top adds Type (Creature, Artifact, Enchantment, Emblem, Custom, Other) and Set, with a Clear filters button. The Sort button orders A to Z, Z to A, or by newest or oldest release, and the grid button switches between list and grid.",
            ),
        ),
    ),
    FaqGroup(
        "The print screen",
        listOf(
            FaqEntry(
                "What am I looking at?",
                "The preview at the top is exactly what the printer will print, in black and white dots. Tap Zoom to look at it close up. The options below the preview are in three tabs: Text, Image and Stacked.\n" +
                    "\n" +
                    "The top bar has the favorite heart and Add to deck. The bottom bar has Copies, Export and Print, and it always stays in view.",
            ),
            FaqEntry(
                "What can I change in the Text tab?",
                "Caption turns the text under the art on or off.\n" +
                    "\n" +
                    "Show picks what goes in the caption: Keyword abilities, Full rules text (replaces the keyword line; emblems always show theirs), Power/toughness, and Color identity.\n" +
                    "\n" +
                    "Text size, Line spacing (below 1.0 is tighter, above is looser, and Reset puts it back), and Font and Bold. The Single line switch runs the name, keywords and power/toughness together on one line instead of separate lines.",
            ),
            FaqEntry(
                "What is Color identity?",
                "It writes the token's colors in the bottom left corner of the picture, in white with a black outline, using W U B R G with slashes between them. A black and green token shows B/G, and a black, red and green token shows B/R/G. Colorless tokens show C.\n" +
                    "\n" +
                    "It works even when the caption is off.",
            ),
            FaqEntry(
                "What can I change in the Image tab?",
                "Art only prints just the artwork, and Whole card prints the whole token card.\n" +
                    "\n" +
                    "Contrast makes the picture punchier or softer. Auto contrast brings out detail in dark or flat art. Invert is only for printers that come out as a negative.",
            ),
            FaqEntry(
                "What does Stacked do?",
                "Stacked (also called Goblin Mode) writes a quantity such as 10x, 25x, 50x or 100x in the top right corner. It is for when one printed token stands in for a whole pile of them, like a hundred Goblins.",
            ),
            FaqEntry(
                "Do my settings stick?",
                "Yes. The caption, image and spacing choices are remembered, and they are the same ones used by quick print and by decks. The Stacked quantity is per print and is not remembered.",
            ),
        ),
    ),
    FaqGroup(
        "Quick print and export (long press)",
        listOf(
            FaqEntry(
                "How does long press work?",
                "Press and hold any token card: in the token list or grid, in a deck, or in a token's list of printings. The card swells, then pops up, the background blurs, and two buttons appear: Export and Print.\n" +
                    "\n" +
                    "Without lifting your finger, slide onto a button and let go to do it straight away. Let go anywhere else to cancel.\n" +
                    "\n" +
                    "Print uses your saved settings and prints one copy. From a deck it prints the count that token has in the deck. Export repeats whatever you last chose in the Export window.",
            ),
        ),
    ),
    FaqGroup(
        "Exporting images (other printers)",
        listOf(
            FaqEntry(
                "How do I use a printer that Token Printer can't connect to?",
                "On the print screen tap Export and choose a style:\n" +
                    "\n" +
                    "Full color: the original art in color, for color printers or sharing.\n" +
                    "Grayscale: grey tones with your contrast settings. Usually the best choice for other thermal printers, because their app turns it into dots.\n" +
                    "Black & white: exactly the dots this app would send to its own printer. Use it if the other app's conversion looks muddy.\n" +
                    "\n" +
                    "Then tap Share to send the JPEG straight to the other printer's app, or Save to put it in your Pictures folder under Token Printer (Android 8 and 9 ask where to save it). The image includes your caption, font, stack label and color identity.",
            ),
            FaqEntry(
                "Can I export without opening the print screen?",
                "Yes. Press and hold a token and slide to Export. It repeats the style and Share or Save choice you last used in the Export window. The first time, it saves a grayscale image to Pictures.",
            ),
        ),
    ),
    FaqGroup(
        "Decks",
        listOf(
            FaqEntry(
                "How do I make a deck from a card list?",
                "Go to the Decks tab and tap Import deck. Paste your list (like '1 Sol Ring', or an export from Arena, Moxfield and similar) or open a text file, add a name if you like, and tap Import.\n" +
                    "\n" +
                    "The app looks up every card and finds all the tokens they create. You can also share a text list into Token Printer from another app and it opens the import box for you.\n" +
                    "\n" +
                    "Moxfield links often get blocked. If that happens, use Export in Moxfield to copy the text list and paste that instead.",
            ),
            FaqEntry(
                "What can I do inside a deck?",
                "Each token shows how many to print. Use the plus and minus to change the count, the art button to pick a different printing, and the bin to remove it. Tap a token to open its print screen, or press and hold for quick print and export.\n" +
                    "\n" +
                    "Print all prints every token, as many times as its count. The share button sends the token list as text, and the pencil renames the deck. Delete a deck from the decks list.",
            ),
            FaqEntry(
                "How do I add one token to a deck?",
                "On the print screen tap the folder icon at the top right, then pick a deck or type a new deck name.",
            ),
            FaqEntry(
                "Some cards or tokens are missing after an import.",
                "The import tells you which cards it couldn't find and which tokens aren't in your list yet. Check the spelling, and try Settings > Sync now in case a token is from a brand new set.",
            ),
        ),
    ),
    FaqGroup(
        "Custom tokens",
        listOf(
            FaqEntry(
                "How do I make my own token?",
                "On the Tokens tab tap New token. Choose Draw to sketch it with your finger, or Photo to use a picture and crop it. Type a name, add optional extra text (printed small under the name), and Save.\n" +
                    "\n" +
                    "The preview shows how it will look on the printer.",
            ),
            FaqEntry(
                "Can I put my own art on an existing token?",
                "Yes. Open the token and tap Add your own art, or link a new custom token to an existing one. It is added as another printing of that token. Custom tokens that aren't linked appear as their own tokens, and the Custom chip shows only those.\n" +
                    "\n" +
                    "To delete custom art, open the token and tap the bin on that printing.",
            ),
        ),
    ),
    FaqGroup(
        "Settings and backup",
        listOf(
            FaqEntry(
                "How do I turn on dark mode?",
                "Settings > Appearance > Dark mode. It forces the dark look even when your phone is set to light, and the app looks best this way. With it off, the app follows your phone's setting.",
            ),
            FaqEntry(
                "What else is in Settings?",
                "Sync now refreshes the token list. Appearance has the app icon and dark mode. Caption text and Image quality are your default print options. The Printer section has the printer model, Darkness (how dark the print is), Remove white space, Reverse bit order and Use handshake, plus a Test page. Legal has the credits and licenses.",
            ),
            FaqEntry(
                "How do I back up my stuff?",
                "Settings > Backup and restore > Save backup. It makes one file with your favorites, decks, custom tokens and settings. Restore backup adds what is in the file to what is already on the phone, and never deletes anything.",
            ),
            FaqEntry(
                "How do I update the app?",
                "Token Printer checks for a new version the first time you open it each day, and shows what's new. Choose Update now, and Android will ask you to confirm the install. The first time, it also asks you to allow Token Printer to install updates.\n" +
                    "\n" +
                    "If you choose Later, or want to check right away, go to Settings > App updates.",
            ),
        ),
    ),
    FaqGroup(
        "Troubleshooting",
        listOf(
            FaqEntry(
                "The app can't find my printer.",
                "Switch the printer on, close the manufacturer’s app, make sure Bluetooth is on, and tap Scan again. Move the phone close to the printer. Allow the Bluetooth permission if Android asks (older Android versions ask for Location instead, which Bluetooth scanning needs there).\n" +
                    "\n" +
                    "Your printer might not be supported yet. If that is the case reach out and let me know what printer you're using and I will do my best to add compatibility! You can reach me from Settings > Help > Contact me.",
            ),
            FaqEntry(
                "The print comes out as a negative (black and white swapped).",
                "On the print screen, open the Image tab and turn on Invert.",
            ),
            FaqEntry(
                "The print looks scrambled or mirrored.",
                "Settings > Printer > turn on Reverse bit order.",
            ),
            FaqEntry(
                "Prints are too light or too dark.",
                "Settings > Printer > Darkness (1 to 3). You can also adjust Contrast in the Image tab, and Auto contrast helps dark art.",
            ),
            FaqEntry(
                "There is too much blank paper after the text.",
                "Settings > Printer > Remove white space cuts the empty paper under the text. Tear carefully, because the last line sits close to the edge.",
            ),
            FaqEntry(
                "The Ready button shows a warning.",
                "When the printer reports a problem, the button at the top right shows it: paper out, battery low, or print head hot. Tap it for details. Reload paper, charge the printer, or let it cool for a minute.",
            ),
            FaqEntry(
                "A new token isn't showing up.",
                "Settings > Sync now downloads the latest tokens from Scryfall.",
            ),
        ),
    ),
    FaqGroup(
        "About",
        listOf(
            FaqEntry(
                "Where does my data go?",
                "Nothing is sent to a server by this app. Card data and images come from Scryfall and are fetched onto your phone. Your favorites, decks, custom tokens and settings stay on the phone (and in backups you save yourself).",
            ),
            FaqEntry(
                "Is this official?",
                "No. Token Printer is unofficial Fan Content, and is not approved by or affiliated with Wizards of the Coast, Scryfall or the printer makers. Printed tokens are for personal play only. The full wording and font licenses are at the bottom of Settings.",
            ),
        ),
    ),
)

/** The FAQ: every feature explained, in groups of questions that open when tapped. */
@Composable
fun FaqScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FAQ") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How to use Token Printer", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap a question to open it. In short: connect your printer, find a token, pick a printing, and press Print. " +
                                "Press and hold any token for quick print and export.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = { vm.showWelcome() }) { Text("Show the welcome guide") }
                    }
                }
            }
            faqGroups.forEach { g ->
                item(key = "h-" + g.title) { SectionHeader(g.title) }
                items(g.items, key = { g.title + "|" + it.q }) { FaqRow(it) }
            }
            item {
                Text(
                    "Token Printer $version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FaqRow(e: FaqEntry) {
    var open by rememberSaveable(e.q) { mutableStateOf(false) }
    Card(
        onClick = { open = !open },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.q, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            AnimatedVisibility(visible = open) {
                Text(
                    e.a,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ welcome

/**
 * Shown when the app opens. "Not now" hides it until the app is opened again, "Don't show this again" hides it for good.
 * It is always available again from Settings > Help or the top of the FAQ.
 */
@Composable
fun WelcomeDialog(vm: MainViewModel, onFaq: () -> Unit) {
    val forceDark by vm.forceDark.collectAsStateWithLifecycle()
    val phoneIsDark = isSystemInDarkTheme()

    AlertDialog(
        onDismissRequest = { vm.dismissWelcome(false) },
        title = { Text("Welcome to Token Printer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Print Magic: The Gathering tokens as stickers using small Bluetooth thermal printers. Here's how to get going:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                WelcomeStep(
                    "1. Connect your printer",
                    "Switch it on, then tap Connect at the top right, tap Scan and pick your printer.",
                )
                WelcomeStep(
                    "2. Find a token",
                    "Search by name, type or set, or use the color chips and Filter button. Tap the heart to keep your favorites at the top.",
                )
                WelcomeStep(
                    "3. Pick a printing and print",
                    "Tap a token, tap the artwork you like, check the preview, then tap Print. Tweak the text and image in the tabs below the preview.",
                )
                WelcomeStep(
                    "Shortcut",
                    "Press and hold any token, then slide onto Print or Export and let go. It will default to your most recent settings.",
                )
                WelcomeStep(
                    "More",
                    "Import a deck list on the Decks tab to get every token it needs, make your own tokens with New token, " +
                        "or export an image to use with a printer this app can't connect to.",
                )
                if (!phoneIsDark) {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            ToggleRow(
                                "Use dark mode",
                                forceDark,
                                "Recommended. Token Printer looks best in dark mode. You can change this later in Settings.",
                            ) { vm.setForceDark(it) }
                        }
                    }
                }
                TextButton(onClick = { vm.dismissWelcome(true) }) { Text("Don't show this again") }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.dismissWelcome(false)
                onFaq()
            }) { Text("Open the FAQ") }
        },
        dismissButton = { TextButton(onClick = { vm.dismissWelcome(false) }) { Text("Not now") } },
    )
}

@Composable
private fun WelcomeStep(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
