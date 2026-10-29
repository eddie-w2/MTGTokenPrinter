package com.example.tokenprinter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Printer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
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
                        "Switch the printer on, close the Funny Print app, tap Scan, then pick the printer. Yours should show up as LX-D02.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                found.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { withBle { vm.connect(f) } }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text(f.address, style = MaterialTheme.typography.bodySmall)
                            if (f.likely) {
                                Text(
                                    "Likely your printer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text("${f.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (log.isNotEmpty()) {
                    Text("Bluetooth log", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        log.takeLast(14).joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
