package com.example.tokenprinter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** What the updater is doing, with the button for the next step. Used by the pop-up and the Settings card. */
@Composable
fun UpdateBody(vm: MainViewModel, state: UpdateState, installed: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state) {
            UpdateState.Idle -> Text(
                "Token Printer looks for a new version the first time you open it each day.",
                style = MaterialTheme.typography.bodyMedium,
            )
            UpdateState.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            }
            UpdateState.UpToDate -> Text("You have the newest version ($installed).", style = MaterialTheme.typography.bodyMedium)
            is UpdateState.Available -> {
                VersionNotes(state.info, installed)
                Button(onClick = { vm.downloadUpdate(state.info) }) { Text("Update now") }
            }
            is UpdateState.Downloading -> {
                VersionNotes(state.info, installed)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            is UpdateState.ReadyToInstall -> {
                VersionNotes(state.info, installed)
                if (state.needsPermission) {
                    Text(
                        "Android needs your OK once before Token Printer can install updates. On the page that opened, turn on " +
                            "\"Allow from this source\", go back here, then tap Install.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = { vm.installUpdate(state.info) }) { Text("Install") }
            }
            is UpdateState.Installing -> {
                VersionNotes(state.info, installed)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Installing. If Android asks, tap Update. The app restarts when it's done.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is UpdateState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                state.info?.let { info ->
                    VersionNotes(info, installed)
                    Button(onClick = { vm.downloadUpdate(info) }) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun VersionNotes(info: Updater.Info, installed: String) {
    Text(
        "Version ${info.versionName} is available (you have $installed).",
        style = MaterialTheme.typography.titleSmall,
    )
    if (info.notes.isNotBlank()) {
        Text("What's new", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(info.notes, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shown after the daily check finds something newer. "Later" keeps it available in Settings. */
@Composable
fun UpdateDialog(vm: MainViewModel, installed: String) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { vm.dismissUpdateDialog() },
        title = { Text("Update available") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UpdateBody(vm, state, installed)
                Text(
                    "Choosing Later keeps it in Settings > App updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissUpdateDialog() }) { Text("Later") } },
    )
}

/** Whether the pop-up has anything to show right now. */
fun UpdateState.hasUpdate(): Boolean = this is UpdateState.Available || this is UpdateState.Downloading ||
    this is UpdateState.ReadyToInstall || this is UpdateState.Installing || (this is UpdateState.Failed && info != null)

/** The Check for updates button for the Settings card. */
@Composable
fun CheckUpdateButton(vm: MainViewModel, state: UpdateState) {
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }, enabled = !busy) { Text("Check for updates") }
}
