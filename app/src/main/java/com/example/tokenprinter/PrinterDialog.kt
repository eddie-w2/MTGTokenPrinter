package com.example.tokenprinter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PrinterDialog(vm: MainViewModel, withBle: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val link by vm.printer.link.collectAsStateWithLifecycle()
    val status by vm.printer.status.collectAsStateWithLifecycle()
    val found by vm.printer.found.collectAsStateWithLifecycle()
    val scanning by vm.printer.scanning.collectAsStateWithLifecycle()
    val log by vm.printer.log.collectAsStateWithLifecycle()
    val info by vm.printer.info.collectAsStateWithLifecycle()
    var showOthers by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val printers = found.filter { it.known || it.imaging }
    val others = found.filter { !it.known && !it.imaging }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Printer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (link == Link.Connected) PrinterTypeLine(vm)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { withBle { vm.scan() } }, enabled = !scanning) {
                        Text(if (scanning) "Scanning…" else "Scan")
                    }
                    if (link != Link.Disconnected) {
                        OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                    }
                }
                if (link == Link.Connected) {
                    PrinterInfoLine(info)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { withBle { vm.printTest() } }) { Text("Print test page") }
                        OutlinedButton(onClick = { vm.printer.refreshStatus() }) { Text("Refresh status") }
                    }
                }
                if (found.isEmpty()) {
                    Text(
                        "Switch the printer on, close its own app, tap Scan, then pick the printer. Printers are listed under the app they " +
                            "came with: Funny Print, Tiny Print / iPrint, Fun Print / iBleem, Phomemo, or receipt printers.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Grouped by the app the printer is sold with: that's the name people know from the box.
                val groups = printers.groupBy { it.types.firstOrNull()?.appGroup ?: "Other printers" }
                val order = listOf("Funny Print", "Tiny Print / iPrint", "Fun Print / iBleem", "Phomemo", "Receipt printers")
                groups.keys.sortedWith(compareBy<String> { order.indexOf(it).let { i -> if (i < 0) 99 else i } }.thenBy { it })
                    .forEach { app ->
                        Text(
                            if (app == "Other printers" || app == "Receipt printers") app else "$app app",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        groups.getValue(app).forEach { f -> FoundRow(vm, f, withBle) }
                    }
                if (others.isNotEmpty()) {
                    TextButton(onClick = { showOthers = !showOthers }) {
                        Text(if (showOthers) "Hide other Bluetooth devices" else "Not listed? Show other Bluetooth devices (${others.size})")
                    }
                    if (showOthers) {
                        Text(
                            "Token Printer doesn't recognise these names. If one is your printer, tap it to let the app work out its type, " +
                                "or use ⋮ to pick one. Then print a test page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        others.forEach { f -> FoundRow(vm, f, withBle) }
                    }
                }
                if (log.isNotEmpty()) {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide Bluetooth log" else "Show Bluetooth log") }
                    if (showLog) {
                        Text(
                            log.takeLast(20).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun FoundRow(vm: MainViewModel, f: FoundPrinter, withBle: (() -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clickable { withBle { vm.connect(f) } }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge)
                when {
                    f.tested -> Tag("Tested", MaterialTheme.colorScheme.primaryContainer)
                    f.known -> Tag("Experimental", MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
            val t = f.types.firstOrNull()
            Text(
                when {
                    t != null && f.types.size > 1 && f.types.map { it.family }.distinct().size > 1 -> t.family.label + " (or similar) · checks when connecting"
                    t != null -> t.family.label + " · ${t.widthDots} dots"
                    f.imaging -> "Printer, type unknown"
                    else -> f.address
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (f.paired || f.classic) {
                Text(
                    if (f.paired) "Paired (Classic Bluetooth)" else "Classic Bluetooth",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (f.rssi > -100) Text("${f.rssi} dBm", style = MaterialTheme.typography.labelSmall)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Try as a printer type") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    "Try as…",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                vm.printer.catalog.tryAs.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(opt.title)
                                Text(
                                    opt.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            menu = false
                            withBle { vm.connect(f, opt.type) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** What kind of printer is connected, and whether it has been tested. */
@Composable
fun PrinterTypeLine(vm: MainViewModel) {
    val detected by vm.printer.detected.collectAsStateWithLifecycle()
    val name by vm.printer.connectedName.collectAsStateWithLifecycle()
    val d = detected ?: return
    val tested = name?.let { it.trim().uppercase() in PrinterCatalog.TESTED_NAMES } == true
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.type.family.label, style = MaterialTheme.typography.bodyMedium)
            if (tested) Tag("Tested", MaterialTheme.colorScheme.primaryContainer)
            else Tag("Experimental", MaterialTheme.colorScheme.tertiaryContainer)
        }
        Text(
            "${d.type.widthDots} dots across" + (if (d.sure) "" else " · picked from its Bluetooth services or by hand"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!tested) {
            Text(
                "Nobody has confirmed this printer works with Token Printer yet. Print a test page; if it looks wrong, try " +
                    "another type with ⋮ next to it in the scan list, and let me know (Settings > Help > Report a bug).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
