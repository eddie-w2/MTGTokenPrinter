package com.example.tokenprinter

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun IconOption(icon: AppIcon, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The launcher shows the middle 72dp of the 108dp icon layers, so preview exactly that.
        Box(
            Modifier.size(72.dp)
                .clip(CircleShape)
                .background(colorResource(icon.bg))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon.fg), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
        }
        Text(
            icon.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SettingsTab(
    vm: MainViewModel,
    onPrinter: () -> Unit,
    onFaq: () -> Unit,
    withBle: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val o by vm.opts.collectAsStateWithLifecycle()
    val forceDark by vm.forceDark.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val status by vm.printer.status.collectAsStateWithLifecycle()
    val link by vm.printer.link.collectAsStateWithLifecycle()
    val info by vm.printer.info.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    var icon by remember { mutableStateOf(AppIcons.current(ctx)) }
    // Read from the installed app, so it always matches the build (it used to be typed in by hand and went stale).
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    var showFontLicenses by remember { mutableStateOf(false) }
    if (showFontLicenses) {
        val text = remember {
            listOf("paint", "notebook", "spooky").joinToString("\n\n----------\n\n") { n ->
                try {
                    ctx.assets.open("fonts/$n-OFL.txt").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "License text for $n could not be loaded."
                }
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFontLicenses = false },
            title = { Text("Font licenses") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showFontLicenses = false }) { Text("Close") } },
        )
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard("Scryfall sync") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Last synced", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lastSync > 0) {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Never", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                "${tokens.size} tokens · ${tokens.sumOf { it.arts.size }} printings on this phone",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { vm.load(true) }, enabled = !loading) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Syncing…" else "Sync now")
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress.ifEmpty { "Starting…" }, style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "New tokens are added to Scryfall with every set release, so sync now and then to see them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Help") {
            Text(
                "New here? The FAQ walks through finding tokens, connecting a printer, printing, decks, custom tokens and exporting images.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFaq) { Text("Open the FAQ") }
                OutlinedButton(onClick = { vm.showWelcome() }) { Text("Welcome guide") }
            }
            Text(
                "Questions, ideas, or a printer that isn't supported yet? Get in touch.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Contact.email(ctx) }) { Text("Contact me") }
                OutlinedButton(onClick = { Contact.bugReport(ctx) }) { Text("Report a bug") }
            }
        }

        SettingsCard("App updates") {
            UpdateBody(vm, updateState, version)
            CheckUpdateButton(vm, updateState)
        }

        SettingsCard("Appearance") {
            Text("App icon", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon.values().forEach { opt ->
                    IconOption(opt, selected = icon == opt, onClick = {
                        if (icon != opt) {
                            AppIcons.set(ctx, opt)
                            icon = opt
                            Toast.makeText(ctx, "Icon changed. Your launcher may take a moment to update.", Toast.LENGTH_LONG).show()
                        }
                    })
                }
            }
            Text(
                "Themed follows Android 13+ themed icons when you have them turned on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                "Dark mode",
                forceDark,
                "Always use the dark look, even when your phone is set to light. The app looks best in dark mode. " +
                    "When this is off, the app follows your phone.",
            ) { vm.setForceDark(it) }
        }

        SettingsCard("Caption text") { CaptionControls(o) { vm.setOpts(it) } }

        SettingsCard("Image quality") { PrintQualityControls(o) { vm.setOpts(it) } }

        SettingsCard("Printer") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            if (link == Link.Connected) PrinterInfoLine(info)
            Text("Printer model", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Profiles.all.forEach { p ->
                    FilterChip(selected = p.id == profile.id, onClick = { vm.setProfile(p) }, label = { Text(p.label) })
                }
            }
            Text(
                "${profile.widthDots} dots across. More printer models can be added here later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrinter) { Text(if (link == Link.Connected) "Printer" else "Connect") }
                OutlinedButton(onClick = { withBle { vm.printTest() } }) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test page")
                }
            }
            PrinterTuningControls(o, profile.label) { vm.setOpts(it) }
        }

        SettingsCard("Backup and restore") {
            Text(
                "One file with your favorites, decks, custom tokens and settings. Restoring adds to what's already here and never deletes anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
                    exportLauncher.launch("tokenprinter-backup-$day.json")
                }) { Text("Save backup") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text("Restore backup")
                }
            }
        }

        SettingsCard("Legal") {
            Text(
                "Token Printer is unofficial Fan Content permitted under the Fan Content Policy. Not approved/endorsed by Wizards. " +
                    "Portions of the materials used are property of Wizards of the Coast. ©Wizards of the Coast LLC.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Magic: The Gathering, its card names, art, symbols and token designs are trademarks and copyrighted works of " +
                    "Wizards of the Coast LLC and their respective artists. This app is not affiliated with, sponsored or endorsed by " +
                    "Wizards of the Coast, Hasbro, or Scryfall.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Card data and images are provided by Scryfall (scryfall.com) and are fetched on your phone. Nothing is copied to any server by this app. " +
                    "Art stays credited to its artists. Use printed tokens for personal play only, not for sale.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Not affiliated with the makers of YTETCN, Funny Print or LX-D02 printers. Product names are used only to describe compatibility. " +
                    "Custom pictures you add stay on your phone, and you are responsible for having the right to use them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Fonts: Finger Paint (© 2011 Ralph du Carrois), Gloria Hallelujah (© 2010 Kimberly Geswein) and Creepster " +
                    "(© 2011 Font Diner, Inc) are used under the SIL Open Font License 1.1. They appear in the app as Paint, Notebook and Spooky.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { showFontLicenses = true }) { Text("View font licenses") }
            Text(
                "The app is provided as is, without warranty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Token Printer $version · Card data and images from Scryfall",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
