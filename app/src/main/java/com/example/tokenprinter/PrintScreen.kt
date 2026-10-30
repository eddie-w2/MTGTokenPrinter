@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.tokenprinter

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

private const val DEFAULT_CONTRAST = 1.2f
private val GOBLIN_STEPS = listOf(0, 10, 25, 50, 100)

private data class OptionTab(val label: String, val icon: ImageVector)

private val optionTabs = listOf(
    OptionTab("Text", Icons.Filled.TextFields),
    OptionTab("Image", Icons.Filled.Photo),
    OptionTab("Goblin", Icons.Filled.Tag),
)

/**
 * Full-screen print screen for one printing. The preview stays in view at the top (or on the left when the
 * phone is sideways) while the options scroll in their own tabbed panel, so every change shows straight away.
 */
@Composable
fun PrintDialog(vm: MainViewModel, ui: PrintUi, withBle: (() -> Unit) -> Unit) {
    val o by vm.opts.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val favArts by vm.favArts.collectAsStateWithLifecycle()
    val run by vm.printRun.collectAsStateWithLifecycle()
    val exporting by vm.exportBusy.collectAsStateWithLifecycle()
    var addToDeck by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val set: (Opts) -> Unit = { vm.setOpts(it) }

    // Part of the app window rather than a pop-up Dialog window. A Dialog doesn't receive the system bar sizes
    // when the app draws edge to edge, which pushed the Print and Export bar under the navigation bar on some
    // phones. Here the Scaffold and the bottom bar get the real insets on every device.
    BackHandler { vm.closePrint() }
    run {
        Surface(
            Modifier.fillMaxSize().pointerInput(Unit) {}, // also stops touches reaching the screen underneath
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { vm.closePrint() }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        },
                        title = {
                            Column {
                                Text(ui.token.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(ui.art.setName, ui.art.artist).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            HeartButton(on = ui.art.id in favArts, onClick = { vm.toggleFavArt(ui.art.id) })
                            IconButton(onClick = { addToDeck = true }) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Add to deck")
                            }
                        },
                    )
                },
                bottomBar = {
                    PrintBar(
                        copies = copies,
                        onCopies = { vm.copies.value = it },
                        ready = ui.preview != null,
                        printing = run != null,
                        exporting = exporting,
                        onExport = { showExport = true },
                        onPrint = { withBle { vm.printCurrent() } },
                    )
                },
            ) { pad ->
                BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
                    val sideways = maxWidth > maxHeight && maxWidth >= 560.dp
                    val options: @Composable (Modifier) -> Unit = { m ->
                        OptionsPanel(tab, { tab = it }, m) {
                            when (tab) {
                                0 -> TextOptions(o, set)
                                1 -> ImageOptions(o, set)
                                else -> GoblinOptions(ui.goblin) { vm.setGoblin(it) }
                            }
                        }
                    }
                    if (sideways) {
                        Row(Modifier.fillMaxSize()) {
                            PreviewPane(ui, Modifier.weight(1f).fillMaxHeight()) { zoom = true }
                            VerticalDivider()
                            options(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PreviewPane(ui, Modifier.fillMaxWidth().weight(0.46f)) { zoom = true }
                            HorizontalDivider()
                            options(Modifier.fillMaxWidth().weight(0.54f))
                        }
                    }
                }
            }
        }
    }

    if (addToDeck) AddToDeckDialog(vm, ui.token, ui.art, onClose = { addToDeck = false })
    if (showExport) ExportDialog(vm, onClose = { showExport = false })
    if (zoom) ui.preview?.let { ZoomDialog(it, onClose = { zoom = false }) }
}

// ------------------------------------------------------------------ preview

/** The print preview on a strip of "paper", scaled to fit so the whole token is always visible. */
@Composable
private fun PreviewPane(ui: PrintUi, modifier: Modifier, onZoom: () -> Unit) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val p = ui.preview
        if (p == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = p.asImageBitmap(),
                contentDescription = "Print preview. Tap to zoom in.",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(p.width.toFloat() / p.height)
                    .shadow(6.dp, RoundedCornerShape(3.dp))
                    .background(Color.White)
                    .clickable(onClick = onZoom),
            )
            Surface(
                onClick = onZoom,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Zoom", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (ui.message.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Text(
                    ui.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

/** The preview at full width, dot for dot, so small text can be checked. Tap anywhere to close. */
@Composable
private fun ZoomDialog(preview: android.graphics.Bitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(onClick = onClose),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Print preview, zoomed in",
                    contentScale = ContentScale.FillWidth,
                    filterQuality = FilterQuality.None, // crisp dots instead of a blur
                    modifier = Modifier.fillMaxWidth().background(Color.White),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close zoom", tint = Color.White)
            }
        }
    }
}

// ------------------------------------------------------------------ bottom bar

@Composable
private fun PrintBar(
    copies: Int,
    onCopies: (Int) -> Unit,
    ready: Boolean,
    printing: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    onPrint: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Copies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Stepper(copies, onCopies, min = 1, max = 20)
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = onExport,
                enabled = ready && !exporting,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Export")
            }
            Button(
                onClick = onPrint,
                enabled = ready && !printing,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (copies > 1) "Print $copies" else "Print")
            }
        }
    }
}

// ------------------------------------------------------------------ options

@Composable
private fun OptionsPanel(tab: Int, onTab: (Int) -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            optionTabs.forEachIndexed { i, t ->
                LeadingIconTab(
                    selected = tab == i,
                    onClick = { onTab(i) },
                    text = { Text(t.label, maxLines = 1) },
                    icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        key(tab) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/** A labelled block of options, with an optional value shown on the right of the label. */
@Composable
private fun OptionGroup(title: String, value: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (value != null) Text(value, style = MaterialTheme.typography.labelLarge)
        }
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An on/off chip that shows a check mark while on. */
@Composable
private fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label) },
        leadingIcon = if (on) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
    )
}

/** A slider with − and + buttons either side for exact steps. */
@Composable
private fun StepSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.start) {
            Icon(Icons.Filled.Remove, contentDescription = "Less")
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.endInclusive) {
            Icon(Icons.Filled.Add, contentDescription = "More")
        }
    }
}

@Composable
private fun ColumnScope.TextOptions(o: Opts, set: (Opts) -> Unit) {
    ToggleRow("Caption under the art", o.caption, "The token's name, plus anything you pick below.") {
        set(o.copy(caption = it))
    }
    if (!o.caption) {
        Hint("Turn the caption on to choose its text, size and font.")
        return
    }
    OptionGroup("Show") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Keyword abilities", o.capKeywords) { set(o.copy(capKeywords = it)) }
            ToggleChip("Full rules text", o.capRules) { set(o.copy(capRules = it)) }
            ToggleChip("Power/toughness", o.capPt) { set(o.copy(capPt = it)) }
        }
        Hint("Full rules text replaces the keyword line. Emblems always show their rules.")
    }
    OptionGroup("Text size", "${o.capSize}") {
        StepSlider(o.capSize.toFloat(), 14f..72f, 1f) { set(o.copy(capSize = it.roundToInt())) }
    }
    OptionGroup("Font") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fonts.all.forEach { f ->
                FilterChip(
                    selected = o.capFont == f.family,
                    onClick = { set(o.copy(capFont = f.family)) },
                    label = { Text(f.label, fontFamily = remember(f.family) { FontFamily(Fonts.typeface(f.family, false)) }) },
                )
            }
        }
        ToggleRow("Bold", o.capBold) { set(o.copy(capBold = it)) }
    }
}

@Composable
private fun ImageOptions(o: Opts, set: (Opts) -> Unit) {
    OptionGroup("Picture") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !o.fullCard,
                onClick = { set(o.copy(fullCard = false)) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("Art only") },
            )
            SegmentedButton(
                selected = o.fullCard,
                onClick = { set(o.copy(fullCard = true)) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("Whole card") },
            )
        }
    }
    OptionGroup("Contrast", String.format(Locale.US, "%.1f", o.contrast)) {
        StepSlider(o.contrast, 0.6f..2.5f, 0.1f) { set(o.copy(contrast = (it * 20).roundToInt() / 20f)) }
        if (kotlin.math.abs(o.contrast - DEFAULT_CONTRAST) > 0.01f) {
            TextButton(onClick = { set(o.copy(contrast = DEFAULT_CONTRAST)) }) { Text("Reset to $DEFAULT_CONTRAST") }
        }
    }
    ToggleRow("Auto contrast", o.autoContrast, "Brings out detail in dark or flat art.") { set(o.copy(autoContrast = it)) }
    ToggleRow("Invert", o.invert, "Only if prints come out as a negative.") { set(o.copy(invert = it)) }
}

@Composable
private fun GoblinOptions(goblin: Int, onPick: (Int) -> Unit) {
    OptionGroup("Goblin Mode") {
        Hint("Writes a quantity in the corner, so one token can stand in for a whole pile.")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            GOBLIN_STEPS.forEachIndexed { i, n ->
                SegmentedButton(
                    selected = goblin == n,
                    onClick = { onPick(n) },
                    shape = SegmentedButtonDefaults.itemShape(i, GOBLIN_STEPS.size),
                    icon = {}, // no check mark, so "100x" fits on small phones
                    label = { Text(if (n == 0) "Off" else "${n}x", maxLines = 1) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ export

/** Pick color, grayscale or black and white, then share the JPEG or save it to the phone. */
@Composable
private fun ExportDialog(vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var style by rememberSaveable { mutableStateOf(vm.lastExportStyle()) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) vm.writeExport(style, uri)
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Export image") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("A JPEG with the same caption, font and Goblin label as the print, for printers this app can't connect to yet.")
                Spacer(Modifier.size(4.dp))
                ExportStyle.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = style == s, onClick = { style = s }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = style == s, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                        Column {
                            Text(s.label, style = MaterialTheme.typography.bodyLarge)
                            Hint(s.hint)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.rememberExport(style, share = true)
                vm.shareExport(style) { uri -> ctx.startActivity(TokenExport.shareIntent(uri)) }
                onClose()
            }) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClose) { Text("Cancel") }
                OutlinedButton(onClick = {
                    vm.rememberExport(style, share = false)
                    if (TokenExport.canSaveToPictures) {
                        vm.saveExport(style)
                        onClose()
                    } else {
                        saveAs.launch(vm.exportName(style))
                    }
                }) { Text("Save") }
            }
        },
    )
}
