@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

// ================================================================= decks tab

@Composable
fun DecksTab(vm: MainViewModel, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val map by vm.tokenMap.collectAsStateWithLifecycle()
    val favArts by vm.favArts.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Deck?>(null) }

    if (decks.isEmpty()) {
        EmptyState(
            title = "No decks yet",
            body = "Import a deck list and Token Printer works out every token it needs, " +
                "then keeps them together in a folder so you can print the whole set in one go.",
            modifier = modifier.fillMaxSize(),
        ) {
            Button(onClick = onImport) { Text("Import a deck") }
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
            items(decks, key = { it.id }) { d ->
                Card(
                    onClick = { vm.selectedDeckId.value = d.id },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // overlapping thumbnails of the first few tokens
                        val thumbs = d.entries.mapNotNull { e ->
                            val t = map[e.tokenKey] ?: return@mapNotNull null
                            (t.arts.firstOrNull { it.id == e.artId } ?: t.thumbArt(favArts)).artUrl
                        }.take(3)
                        Box(Modifier.width(92.dp).heightIn(min = 56.dp)) {
                            if (thumbs.isEmpty()) {
                                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(48.dp).padding(4.dp))
                            }
                            thumbs.forEachIndexed { i, url ->
                                TokenThumb(
                                    url,
                                    Modifier.offset(x = (i * 18).dp, y = (i * 4).dp).size(width = 56.dp, height = 41.dp),
                                    RoundedCornerShape(8.dp),
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${d.entries.size} tokens · ${d.copies} to print",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                listOf(d.origin, if (d.created > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.created)) else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { confirmDelete = d }) { Icon(Icons.Filled.Delete, contentDescription = "Delete deck") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${d.name}\"?") },
            text = { Text("This removes the deck folder from this phone. Your favorites and synced tokens aren't affected.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteDeck(d.id); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// ============================================================== deck detail

@Composable
fun DeckDetailScreen(vm: MainViewModel, deck: Deck, withBle: (() -> Unit) -> Unit, onBack: () -> Unit) {
    val map by vm.tokenMap.collectAsStateWithLifecycle()
    val favArts by vm.favArts.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<DeckEntry?>(null) }
    val total = deck.copies

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deck.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val lines = deck.entries.mapNotNull { e ->
                            map[e.tokenKey]?.let { t -> "${e.qty}x ${t.name}" + if (t.pt.isNotEmpty()) " ${t.pt}" else "" }
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, deck.name)
                            putExtra(Intent.EXTRA_TEXT, deck.name + "\n" + lines.joinToString("\n"))
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share token list"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share token list") }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                },
            )
        },
        floatingActionButton = {
            if (deck.entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { withBle { vm.printDeck(deck.id) } },
                    icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    text = { Text("Print all ($total)") },
                )
            }
        },
    ) { pad ->
        if (deck.entries.isEmpty()) {
            EmptyState(
                title = "This deck is empty",
                body = "Open any token, tap a printing, and choose Add to deck.",
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                items(deck.entries, key = { it.tokenKey }) { e ->
                    val token = map[e.tokenKey]
                    if (token != null) {
                        val art = token.arts.firstOrNull { it.id == e.artId } ?: token.thumbArt(favArts)
                        Card(
                            onClick = { vm.openPrint(token, art) },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                TokenThumb(art.artUrl, Modifier.size(width = 92.dp, height = 67.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(token.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    KeywordLine(token)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ColorPips(token.colors)
                                        Text(
                                            token.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (e.sources.isNotEmpty()) {
                                        Text(
                                            "From " + e.sources.joinToString(", "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Stepper(e.qty, { vm.setEntryQty(deck.id, e.tokenKey, it) })
                                        Spacer(Modifier.weight(1f))
                                        if (token.arts.size > 1) {
                                            IconButton(onClick = { pickFor = e }) {
                                                Icon(Icons.Filled.Style, contentDescription = "Change art")
                                            }
                                        }
                                        IconButton(onClick = { vm.removeEntry(deck.id, e.tokenKey) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove from deck")
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

    if (renaming) {
        var name by remember { mutableStateOf(deck.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename deck") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { vm.renameDeck(deck.id, name); renaming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    pickFor?.let { entry ->
        val token = map[entry.tokenKey]
        if (token != null) {
            ArtPickerDialog(
                token = token,
                selectedId = entry.artId,
                onPick = { vm.setEntryArt(deck.id, entry.tokenKey, it.id); pickFor = null },
                onDismiss = { pickFor = null },
            )
        }
    }
}

@Composable
fun ArtPickerDialog(token: Token, selectedId: String?, onPick: (TokenArt) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose art for ${token.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(token.arts, key = { it.id }) { art ->
                    Column(Modifier.clickable { onPick(art) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TokenThumb(art.artUrl, Modifier.fillMaxWidth().heightIn(min = 70.dp), RoundedCornerShape(10.dp))
                        Text(
                            art.setName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (art.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ============================================================= import dialog

@Composable
fun ImportDeckDialog(vm: MainViewModel, onClose: () -> Unit, onOpenDeck: (String) -> Unit) {
    val state by vm.importState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val shared by vm.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let {
            text = it
            vm.pendingShare.value = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                text = content
                if (name.isBlank()) {
                    name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx).substringBeforeLast('.') else ""
                    }.orEmpty()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(state.doneDeckId) {
        val id = state.doneDeckId
        if (id != null) {
            Toast.makeText(ctx, state.summary ?: "Deck imported", Toast.LENGTH_LONG).show()
            vm.clearImportState()
            onOpenDeck(id)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.busy) { vm.clearImportState(); onClose() } },
        title = { Text("Import a deck") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste a deck list, open a .txt file, or paste a Moxfield link. In Moxfield you can use the deck's Export option to copy the list as text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Deck name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Deck list or Moxfield link") },
                    placeholder = { Text("1 Sol Ring\n1 Doubling Season\n...") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip
                        val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
                        if (pasted.isBlank()) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show() else text = pasted
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                    OutlinedButton(onClick = { picker.launch("text/*") }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open file")
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.importDeck(name, text) }, enabled = !state.busy && text.isNotBlank()) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = { vm.clearImportState(); onClose() }, enabled = !state.busy) { Text("Cancel") }
        },
    )
}
