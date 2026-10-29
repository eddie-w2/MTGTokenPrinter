@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun App(vm: MainViewModel, withBle: (() -> Unit) -> Unit) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val deckId by vm.selectedDeckId.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val printUi by vm.printUi.collectAsStateWithLifecycle()
    val printRun by vm.printRun.collectAsStateWithLifecycle()
    val tokenMap by vm.tokenMap.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val shared by vm.pendingShare.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var showPrinter by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val deck = decks.firstOrNull { it.id == deckId }
    // Look the token up again so the detail screen sees new or deleted custom art straight away.
    val sel = selected?.let { tokenMap[it.key] }
    BackHandler(enabled = sel != null) { vm.selected.value = null }
    BackHandler(enabled = sel == null && deck != null) { vm.selectedDeckId.value = null }

    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearNotice()
        }
    }
    LaunchedEffect(shared) {
        if (shared != null) {
            tab = 1
            showImport = true
        }
    }
    when {
        sel != null -> TokenDetailScreen(vm, sel, onBack = { vm.selected.value = null })
        deck != null -> DeckDetailScreen(vm, deck, withBle, onBack = { vm.selectedDeckId.value = null })
        else -> MainShell(
            vm = vm,
            tab = tab,
            onTab = { tab = it },
            onPrinter = { showPrinter = true },
            onImport = { showImport = true },
            withBle = withBle,
        )
    }

    if (showPrinter) PrinterDialog(vm, withBle, onClose = { showPrinter = false })
    if (showImport) {
        ImportDeckDialog(
            vm,
            onClose = { showImport = false },
            onOpenDeck = { id ->
                showImport = false
                tab = 1
                vm.selectedDeckId.value = id
            },
        )
    }
    editor?.let { CustomTokenEditor(vm, it, onClose = { vm.closeEditor() }) }
    printUi?.let { PrintDialog(vm, it, withBle) }
    printRun?.let { PrintRunDialog(it, onDismiss = { vm.dismissRun() }, onCancel = { vm.cancelRun() }) }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
private fun MainShell(
    vm: MainViewModel,
    tab: Int,
    onTab: (Int) -> Unit,
    onPrinter: () -> Unit,
    onImport: () -> Unit,
    withBle: (() -> Unit) -> Unit,
) {
    val link by vm.printer.link.collectAsStateWithLifecycle()
    val info by vm.printer.info.collectAsStateWithLifecycle()
    val grid by vm.gridView.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val tabs = listOf(
        Tab("Tokens", Icons.Filled.Style),
        Tab("Decks", Icons.Filled.Folder),
        Tab("Settings", Icons.Filled.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs[tab].label, fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (tab == 0) {
                        SortMenuButton(vm)
                        FilterButton(vm) { showFilters = true }
                        IconButton(onClick = { vm.setGridView(!grid) }) {
                            Icon(
                                if (grid) Icons.Filled.Menu else Icons.Filled.GridView,
                                contentDescription = if (grid) "Show as list" else "Show as grid",
                            )
                        }
                    }
                    AssistChip(
                        onClick = onPrinter,
                        label = {
                            Text(
                                when (link) {
                                    Link.Connected -> info?.warning ?: "Ready"
                                    Link.Connecting -> "Connecting…"
                                    Link.Disconnected -> "Connect"
                                }
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (link == Link.Connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                                contentDescription = null,
                            )
                        },
                        colors = if (link == Link.Connected && info?.warning != null) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else if (link == Link.Connected) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { vm.openEditor() },
                    icon = { Icon(Icons.Filled.Brush, contentDescription = null) },
                    text = { Text("New token") },
                )
            }
            if (tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Import deck") },
                )
            }
        },
    ) { pad ->
        when (tab) {
            0 -> TokensTab(vm, Modifier.padding(pad))
            1 -> DecksTab(vm, onImport, Modifier.padding(pad))
            else -> SettingsTab(vm, onPrinter, withBle, Modifier.padding(pad))
        }
    }

    if (showFilters) FilterSheet(vm, onClose = { showFilters = false })
}
