// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The card at the top of the Decks tab while something is in the print queue. */
@Composable
fun QueueCard(vm: MainViewModel, modifier: Modifier = Modifier) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    if (queue.isEmpty()) return
    Card(
        onClick = { vm.queueOpen.value = true },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Print queue", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${queue.size} tokens · ${queue.sumOf { it.qty }} to print · each with its own settings",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A short line saying which settings an item was queued with. */
private fun settingsLine(o: Opts): String = buildList {
    add(if (o.fullCard) "Whole card" else "Art")
    if (o.caption) add("caption ${o.capSize} ${o.capFont.substringAfterLast('/').substringBeforeLast('.')}") else add("no caption")
    add("contrast %.2f".format(o.contrast))
    if (o.autoContrast) add("auto contrast")
    if (o.invert) add("inverted")
    add("darkness ${o.density}")
    if (o.capLineSpacing != 1f) add("spacing %.1f".format(o.capLineSpacing))
}.joinToString(" · ")

@Composable
fun PrintQueueScreen(vm: MainViewModel, withBle: (() -> Unit) -> Unit, onBack: () -> Unit) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val total = queue.sumOf { it.qty }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Clear all")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (queue.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { withBle { vm.printQueue() } },
                    icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    text = { Text("Print all ($total)") },
                )
            }
        },
    ) { pad ->
        if (queue.isEmpty()) {
            EmptyState(
                title = "The print queue is empty",
                body = "Press and hold any token and slide to Add to queue. Each token keeps the settings it was added with, " +
                    "so you can change settings between tokens and print them all at once.",
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                items(queue, key = { it.id }) { q ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                TokenThumb(q.art.artUrl, Modifier.size(width = 92.dp, height = 67.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(q.token.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        settingsLine(q.opts),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Stepper(q.qty, { vm.setQueueQty(q.id, it) })
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { vm.removeFromQueue(q.id) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove from queue")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the print queue?") },
            text = { Text("This removes all ${queue.size} tokens from the queue.") },
            confirmButton = { TextButton(onClick = { vm.clearQueue(); confirmClear = false }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
