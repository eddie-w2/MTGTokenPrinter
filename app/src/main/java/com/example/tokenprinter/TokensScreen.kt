@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ================================================================= tokens tab

@Composable
fun TokensTab(vm: MainViewModel, modifier: Modifier = Modifier) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val favArts by vm.favArts.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val grid by vm.gridView.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { vm.setQuery(it) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search name, type or set") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filters.favOnly,
                onClick = { vm.toggleFavOnly() },
                label = { Text("Favorites") },
                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = HeartRed, modifier = Modifier.size(16.dp)) },
            )
            FilterChip(
                selected = filters.customOnly,
                onClick = { vm.toggleCustomOnly() },
                label = { Text("Custom") },
                leadingIcon = { Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
            "WUBRGC".forEach { c ->
                FilterChip(
                    selected = c in filters.colors,
                    onClick = { vm.toggleColor(c) },
                    label = { ColorPips(if (c == 'C') "" else c.toString(), 18.dp) },
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            Text(
                progress.ifEmpty { "Loading…" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        error?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load tokens: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.load(true) }) { Text("Retry") }
            }
        }

        if (!loading && error == null && list.isEmpty()) {
            EmptyState(
                title = if (filters.favOnly) "No favorites yet" else "No tokens match",
                body = if (filters.favOnly) "Tap the heart on a token, or on one of its printings, to pin it here."
                else "Try a different search or clear the color filters.",
            )
        }

        val pinned = list.filter { it.key in favs || it.favArtCount(favArts) > 0 }
        val rest = list.filter { it.key !in favs && it.favArtCount(favArts) == 0 }

        if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "h-fav", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Favorites", pinned.size) }
                    items(pinned, key = { "f-" + it.key }) { t ->
                        TokenGridCard(t, t.key in favs, t.thumbArt(favArts).artUrl, t.favArtCount(favArts),
                            { vm.selected.value = t }, { vm.toggleFavorite(t.key) })
                    }
                    item(key = "h-all", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("All tokens", rest.size) }
                }
                items(rest, key = { it.key }) { t ->
                    TokenGridCard(t, false, t.thumbArt(favArts).artUrl, 0,
                        { vm.selected.value = t }, { vm.toggleFavorite(t.key) })
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (pinned.isNotEmpty()) {
                    item(key = "h-fav") { SectionHeader("Favorites", pinned.size) }
                    items(pinned, key = { "f-" + it.key }) { t ->
                        TokenRow(t, t.key in favs, t.thumbArt(favArts).artUrl, t.favArtCount(favArts),
                            { vm.selected.value = t }, { vm.toggleFavorite(t.key) })
                    }
                    item(key = "h-all") { SectionHeader("All tokens", rest.size) }
                }
                items(rest, key = { it.key }) { t ->
                    TokenRow(t, false, t.thumbArt(favArts).artUrl, 0,
                        { vm.selected.value = t }, { vm.toggleFavorite(t.key) })
                }
            }
        }
    }
}

@Composable
private fun TokenRow(
    t: Token,
    fav: Boolean,
    thumbUrl: String,
    favPrintings: Int,
    onClick: () -> Unit,
    onFav: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenThumb(thumbUrl, Modifier.size(width = 92.dp, height = 67.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                KeywordLine(t)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPips(t.colors)
                    Text(
                        t.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val n = t.arts.size
                val extra = if (favPrintings > 0) "  ·  $favPrintings favorite${if (favPrintings == 1) "" else "s"}" else ""
                Text(
                    "$n art${if (n == 1) "" else "s"}$extra",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HeartButton(on = fav, onClick = onFav)
        }
    }
}

@Composable
private fun TokenGridCard(
    t: Token,
    fav: Boolean,
    thumbUrl: String,
    favPrintings: Int,
    onClick: () -> Unit,
    onFav: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box {
            TokenThumb(thumbUrl, Modifier.fillMaxWidth().aspectRatio(1.37f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
                HeartButton(on = fav, onClick = onFav, modifier = Modifier.size(36.dp), offTint = Color.White)
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            KeywordLine(t)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorPips(t.colors, 10.dp)
                Text(
                    if (t.pt.isNotEmpty()) t.pt else "${t.arts.size} arts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (favPrintings > 0) {
                    Text("♥ $favPrintings", style = MaterialTheme.typography.labelSmall, color = HeartRed)
                }
            }
        }
    }
}

// ============================================================== token detail

@Composable
fun TokenDetailScreen(vm: MainViewModel, token: Token, onBack: () -> Unit) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val favArts by vm.favArts.collectAsStateWithLifecycle()
    val fav = token.key in favs
    val arts = remember(token, favArts) { token.arts.sortedByDescending { it.id in favArts } }
    var confirmDelete by remember { mutableStateOf<TokenArt?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(token.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { HeartButton(on = fav, onClick = { vm.toggleFavorite(token.key) }) },
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeywordLine(token)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorPips(token.colors, 14.dp)
                            Text(
                                listOf(colorLabel(token.colors), token.subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        if (token.oracle.isNotBlank()) Text(token.oracle, style = MaterialTheme.typography.bodyMedium)
                        val n = token.arts.size
                        Text(
                            "$n available art${if (n == 1) "" else "s"}. Tap one to preview and print, or tap the heart to favorite that printing.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedButton(onClick = {
                            vm.openEditor(
                                linkKey = if (token.key.startsWith("custom|")) null else token.key,
                                name = token.name,
                            )
                        }) {
                            Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add your own art")
                        }
                    }
                }
            }
            items(arts, key = { it.id }) { art ->
                val isFav = art.id in favArts
                Card(
                    onClick = { vm.openPrint(token, art) },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Box {
                        TokenThumb(
                            art.artUrl,
                            Modifier.fillMaxWidth().aspectRatio(1.37f),
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        )
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
                            HeartButton(on = isFav, onClick = { vm.toggleFavArt(art.id) }, modifier = Modifier.size(36.dp), offTint = Color.White)
                        }
                    }
                    Column(Modifier.padding(10.dp)) {
                        Text(art.setName, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(art.artist, art.released.take(4)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (art.setCode == "custom") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Custom",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                IconButton(onClick = { confirmDelete = art }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete custom art", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { art ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this custom art?") },
            text = { Text("It is removed from this phone, and from any backup you make from now on.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCustomArt(art.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// =============================================================== print dialog

@Composable
fun PrintDialog(vm: MainViewModel, ui: PrintUi, withBle: (() -> Unit) -> Unit) {
    val o by vm.opts.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val favArts by vm.favArts.collectAsStateWithLifecycle()
    val run by vm.printRun.collectAsStateWithLifecycle()
    var addToDeck by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { vm.closePrint() },
        title = { Text(ui.token.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp).background(Color.White, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val p = ui.preview
                    if (p == null) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    } else {
                        androidx.compose.foundation.Image(
                            bitmap = p.asImageBitmap(),
                            contentDescription = "Print preview",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
                if (ui.message.isNotEmpty()) {
                    Text(ui.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Copies", style = MaterialTheme.typography.bodyMedium)
                    Stepper(copies, { vm.copies.value = it }, min = 1, max = 20)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { vm.cycleGoblin() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (ui.goblin == 0) "Goblin Mode" else "Goblin ${ui.goblin}x",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addToDeck = true }) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add to deck")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Favorite printing", style = MaterialTheme.typography.labelMedium)
                    HeartButton(on = ui.art.id in favArts, onClick = { vm.toggleFavArt(ui.art.id) })
                }
                Expander("Text options") { CaptionControls(o) { vm.setOpts(it) } }
                Expander("Image quality") { PrintQualityControls(o) { vm.setOpts(it) } }
            }
        },
        confirmButton = {
            Button(
                onClick = { withBle { vm.printCurrent() } },
                enabled = ui.preview != null && run == null,
            ) {
                Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copies > 1) "Print $copies" else "Print")
            }
        },
        dismissButton = { TextButton(onClick = { vm.closePrint() }) { Text("Close") } },
    )

    if (addToDeck) AddToDeckDialog(vm, ui.token, ui.art, onClose = { addToDeck = false })
}

@Composable
fun AddToDeckDialog(vm: MainViewModel, token: Token, art: TokenArt, onClose: () -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add to deck") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                decks.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                vm.addToDeck(d.id, token, art)
                                Toast.makeText(ctx, "Added to ${d.name}", Toast.LENGTH_SHORT).show()
                                onClose()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${d.entries.size} tokens", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("New deck name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = {
                    val d = vm.newDeck(newName)
                    vm.addToDeck(d.id, token, art)
                    Toast.makeText(ctx, "Added to ${d.name}", Toast.LENGTH_SHORT).show()
                    onClose()
                },
            ) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
