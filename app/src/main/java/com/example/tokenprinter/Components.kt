package com.example.tokenprinter

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ------------------------------------------------------------------ images

@Composable
fun TokenThumb(
    url: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val ctx = LocalContext.current
    val request = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(true).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.clip(shape),
    )
}

private fun manaColor(c: Char) = when (c) {
    'W' -> Color(0xFFF8E7B9) // Magic's cream white
    'U' -> Color(0xFF0E68AB)
    'B' -> Color(0xFF3A2650) // deep purplish black, so it still reads on dark backgrounds
    'R' -> Color(0xFFD3202A)
    'G' -> Color(0xFF00733E)
    else -> Color(0xFFBEB9B2) // colorless
}

/** Small colored dots for a token's colors. Empty string = colorless. */
@Composable
fun ColorPips(colors: String, size: Dp = 12.dp) {
    val list = if (colors.isEmpty()) listOf('C') else colors.toList()
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        list.forEach { c ->
            Box(
                Modifier
                    .size(size)
                    .background(manaColor(c), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
            )
        }
    }
}

@Composable
fun HeartButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, offTint: Color? = null) {
    val scale by animateFloatAsState(
        targetValue = if (on) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "heart",
    )
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (on) "Remove favorite" else "Add favorite",
            tint = if (on) HeartRed else (offTint ?: LocalContentColor.current),
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

/** Keyword abilities (Flying, Vigilance, ...) shown above the power/toughness. */
@Composable
fun KeywordLine(t: Token, modifier: Modifier = Modifier) {
    if (t.keywords.isNotEmpty()) {
        Text(
            t.keywordText,
            modifier = modifier,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ----------------------------------------------------------------- layout bits

@Composable
fun SectionHeader(text: String, count: Int? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (count != null) {
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action()
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onChange)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 99) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { if (value > min) onChange(value - 1) }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Fewer")
        }
        Text(
            "$value",
            Modifier.widthIn(min = 32.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        FilledTonalIconButton(onClick = { if (value < max) onChange(value + 1) }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "More")
        }
    }
}

@Composable
fun Expander(title: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

/** Battery, paper and temperature as last reported by the printer. */
@Composable
fun PrinterInfoLine(info: PrinterInfo?, modifier: Modifier = Modifier) {
    if (info == null) {
        Text(
            "Printer status appears once it reports in.",
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Battery: ${info.batteryText}" + if (info.charging) " (charging)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Paper: " + if (info.paperOut) "out" else "loaded",
            style = MaterialTheme.typography.bodySmall,
            color = if (info.paperOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Print head: " + if (info.hot) "too hot, let it cool" else "OK",
            style = MaterialTheme.typography.bodySmall,
            color = if (info.hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (info.lowVoltage) {
            Text("Battery is low. Charge the printer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// -------------------------------------------------------------- print options

@Composable
fun CaptionControls(o: Opts, onChange: (Opts) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleRow("Name caption under the art", o.caption) { onChange(o.copy(caption = it)) }
        AnimatedVisibility(visible = o.caption) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledSlider("Text size: ${o.capSize}", o.capSize.toFloat(), 14f..72f) {
                    onChange(o.copy(capSize = it.roundToInt()))
                }
                Text("Font", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fonts.all.forEach { f ->
                        FilterChip(
                            selected = o.capFont == f.family,
                            onClick = { onChange(o.copy(capFont = f.family)) },
                            label = { Text(f.label, fontFamily = remember(f.family) { FontFamily(Fonts.typeface(f.family, false)) }) },
                        )
                    }
                }
                ToggleRow("Bold", o.capBold) { onChange(o.copy(capBold = it)) }
                ToggleRow("Show keyword abilities", o.capKeywords) { onChange(o.copy(capKeywords = it)) }
                ToggleRow("Show full rules text", o.capRules, "Emblems always show theirs.") { onChange(o.copy(capRules = it)) }
                ToggleRow("Show power/toughness", o.capPt) { onChange(o.copy(capPt = it)) }
            }
        }
    }
}

@Composable
fun PrintQualityControls(o: Opts, onChange: (Opts) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleRow("Whole card instead of art only", o.fullCard) { onChange(o.copy(fullCard = it)) }
        ToggleRow(
            "Auto contrast",
            o.autoContrast,
            "Stretches dark or flat art so it keeps its detail. Applies to every print, including custom tokens.",
        ) { onChange(o.copy(autoContrast = it)) }
        LabeledSlider("Contrast", o.contrast, 0.6f..2.5f) { onChange(o.copy(contrast = it)) }
        ToggleRow("Invert", o.invert, "Turn on if prints come out as a negative.") { onChange(o.copy(invert = it)) }
    }
}

@Composable
fun PrinterTuningControls(o: Opts, printerName: String, onChange: (Opts) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledSlider("Darkness: ${o.density} (saved for $printerName)", o.density.toFloat(), 1f..3f, steps = 1) {
            onChange(o.copy(density = it.roundToInt()))
        }
        ToggleRow("Remove white space", o.trimSpace, "Cuts the blank paper under the token text. Tear carefully; the last line sits close to the edge.") { onChange(o.copy(trimSpace = it)) }
        ToggleRow("Reverse bit order", o.lsbFirst, "Turn on if prints look scrambled or mirrored.") { onChange(o.copy(lsbFirst = it)) }
        ToggleRow("Use handshake", o.handshake, "The security check Funny Print does when it connects.") { onChange(o.copy(handshake = it)) }
    }
}

// --------------------------------------------------------- printing animation

/**
 * A little receipt printer pushing the paper out as the data is sent.
 * [progress] is 0..1; the paper slides out of the slot as it grows.
 */
@Composable
fun PrintingAnimation(preview: Bitmap?, progress: Float, active: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "paper")
    val infinite = rememberInfiniteTransition(label = "printer")
    val led by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "led",
    )
    val shake by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(90), RepeatMode.Reverse),
        label = "shake",
    )

    val paperWidth = 170f
    val maxPaperHeight = 190f
    val aspect = if (preview != null) preview.height.toFloat() / preview.width else 0.8f
    val w = minOf(paperWidth, maxPaperHeight / aspect)
    val h = w * aspect

    Box(modifier.fillMaxWidth().height(272.dp), contentAlignment = Alignment.TopCenter) {
        // paper, clipped to the area below the printer's slot
        Box(
            Modifier
                .padding(top = 74.dp)
                .width(w.dp)
                .height(h.dp)
                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
        ) {
            val paperModifier = Modifier
                .width(w.dp)
                .height(h.dp)
                .graphicsLayer { translationY = -(1f - p) * size.height }
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = paperModifier,
                )
            } else {
                Box(paperModifier.background(Color.White))
            }
        }
        // printer body
        Box(
            Modifier
                .offset(y = if (active) shake.dp else 0.dp)
                .width(250.dp)
                .height(92.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF4A4F5E), Color(0xFF262932))))
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .width(200.dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF0B0C10))
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 18.dp)
                    .size(10.dp)
                    .background((if (active) Color(0xFF39D98A) else Color(0xFF7FA38F)).copy(alpha = if (active) led else 1f), CircleShape)
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 20.dp, top = 20.dp)
                    .width(46.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF6B7080))
            )
        }
    }
}

@Composable
fun PrintRunDialog(run: PrintRun, onDismiss: () -> Unit, onCancel: () -> Unit) {
    LaunchedEffect(run.finished, run.error) {
        if (run.finished && run.error == null) {
            delay(1500)
            onDismiss()
        }
    }
    Dialog(
        onDismissRequest = { if (run.finished) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = run.finished, dismissOnClickOutside = run.finished),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrintingAnimation(run.preview, run.progress, active = !run.finished)
                Text(
                    run.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (run.steps > 1) {
                    Text(
                        "Token ${run.step} of ${run.steps}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val overall = if (run.finished && run.error == null) 1f
                else ((run.step - 1) + run.progress) / run.steps.coerceAtLeast(1)
                LinearProgressIndicator(progress = { overall.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                when {
                    run.error != null -> Text(
                        run.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    run.finished -> Text("Done! Tear it off.", style = MaterialTheme.typography.bodyMedium)
                    run.progress <= 0f -> Text("Getting ready…", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("Printing…", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(2.dp))
                if (run.finished) Button(onClick = onDismiss) { Text("Close") }
                else OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
