@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One finger stroke, in canvas pixels. A single point is a dot. */
private class DrawStroke(val pts: SnapshotStateList<Offset>, val width: Float, val erase: Boolean)

private fun DrawScope.drawStroke(s: DrawStroke) {
    val color = if (s.erase) Color.White else Color.Black
    val pts = s.pts
    if (pts.size == 1) {
        drawCircle(color, radius = s.width / 2f, center = pts[0])
    } else if (pts.size > 1) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(path, color, style = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Size in source pixels of the visible part of a photo for a crop with this aspect (width / height) and zoom. */
private fun cropRegion(iw: Float, ih: Float, aspect: Float, zoom: Float): Pair<Float, Float> {
    val (rw, rh) = if (iw / ih > aspect) Pair(ih * aspect, ih) else Pair(iw, iw / aspect)
    return Pair(rw / zoom, rh / zoom)
}

/** Cuts the visible region out of [src]. [fx]/[fy] are the region's centre as a fraction of the photo. */
private fun cropPhoto(src: Bitmap, aspect: Float, zoom: Float, fx: Float, fy: Float, outW: Int): Bitmap {
    val iw = src.width.toFloat()
    val ih = src.height.toFloat()
    val (rw, rh) = cropRegion(iw, ih, aspect, zoom)
    val cx = (fx * iw).coerceIn(rw / 2f, iw - rw / 2f)
    val cy = (fy * ih).coerceIn(rh / 2f, ih - rh / 2f)
    val l = (cx - rw / 2f).roundToInt().coerceIn(0, src.width - 1)
    val t = (cy - rh / 2f).roundToInt().coerceIn(0, src.height - 1)
    val r = (cx + rw / 2f).roundToInt().coerceIn(l + 1, src.width)
    val b = (cy + rh / 2f).roundToInt().coerceIn(t + 1, src.height)
    val outH = (outW / aspect).roundToInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.WHITE)
    c.drawBitmap(
        src, android.graphics.Rect(l, t, r, b), android.graphics.Rect(0, 0, outW, outH),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    return out
}

/** Paints the strokes black-on-white into a 768-pixel-wide bitmap with the same shape as the on-screen canvas. */
private fun renderDrawing(strokes: List<DrawStroke>, w: Int, h: Int): Bitmap {
    val outW = 768
    val scale = outW.toFloat() / w
    val outH = (h * scale).roundToInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (s in strokes) {
        paint.color = if (s.erase) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        paint.strokeWidth = s.width * scale
        val pts = s.pts
        if (pts.size == 1) {
            paint.style = android.graphics.Paint.Style.FILL
            c.drawCircle(pts[0].x * scale, pts[0].y * scale, s.width * scale / 2f, paint)
        } else if (pts.size > 1) {
            paint.style = android.graphics.Paint.Style.STROKE
            val path = android.graphics.Path()
            path.moveTo(pts[0].x * scale, pts[0].y * scale)
            for (i in 1 until pts.size) path.lineTo(pts[i].x * scale, pts[i].y * scale)
            c.drawPath(path, paint)
        }
    }
    return bmp
}

/** Full-screen maker for custom tokens: draw one with a finger, or use a photo. */
@Composable
fun CustomTokenEditor(vm: MainViewModel, req: CustomEditorRequest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allTokens by vm.tokens.collectAsStateWithLifecycle()
    val opts by vm.opts.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(0) } // 0 = draw, 1 = photo
    var name by remember { mutableStateOf(req.name) }
    var linkKey by remember { mutableStateOf(req.linkKey) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoPreview by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(0) } // 0 wide, 1 square, 2 tall, 3 whole photo
    var zoom by remember { mutableStateOf(1f) }
    var fx by remember { mutableStateOf(0.5f) }
    var fy by remember { mutableStateOf(0.5f) }
    var cropBoxW by remember { mutableStateOf(1) }
    val aspect = when (shape) {
        0 -> 1.37f
        1 -> 1f
        2 -> 0.75f
        else -> photo?.let { it.width.toFloat() / it.height } ?: 1.37f
    }
    val strokes = remember { mutableStateListOf<DrawStroke>() }
    var pen by remember { mutableStateOf(10f) }
    var erase by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val penNow by rememberUpdatedState(pen)
    val eraseNow by rememberUpdatedState(erase)

    val linked = linkKey?.let { k -> allTokens.firstOrNull { it.key == k } }
    val matches = remember(name, allTokens) {
        val q = name.trim()
        if (q.length < 2) emptyList()
        else allTokens.filter { !it.key.startsWith("custom|") && it.name.contains(q, ignoreCase = true) }
            .sortedBy { it.name.length }
            .take(6)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    photo = withContext(Dispatchers.IO) { CustomImages.loadPicked(ctx, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Couldn't open that picture: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Show how the photo will look on the printer, using the same settings as a real print.
    LaunchedEffect(photo, shape, zoom, fx, fy, opts.contrast, opts.invert, opts.lsbFirst, opts.autoContrast, profile) {
        val p = photo
        if (p != null) delay(200) // wait while the user is still dragging
        photoPreview = if (p == null) null else withContext(Dispatchers.Default) {
            Raster.render(
                cropPhoto(p, aspect, zoom, fx, fy, 600), null, CaptionStyle(opts.capSize, opts.capFont, opts.capBold),
                opts.contrast, opts.invert, opts.lsbFirst, true, profile, opts.autoContrast,
            ).preview
        }
    }

    val hasImage = if (mode == 0) strokes.isNotEmpty() else photo != null
    val hasName = linked != null || name.isNotBlank()
    val save = {
        val bmp: Bitmap? = if (mode == 0) {
            if (canvasSize.width > 0 && canvasSize.height > 0) renderDrawing(strokes, canvasSize.width, canvasSize.height) else null
        } else photo?.let { cropPhoto(it, aspect, zoom, fx, fy, 900) }
        if (bmp != null) vm.saveCustom(linked?.name ?: name, linked?.key, bmp, photo = mode == 1, text = text)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("New custom token") },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    },
                    actions = {
                        TextButton(onClick = { save() }, enabled = hasImage && hasName) { Text("Save") }
                    },
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // ---- name, and whether it joins an existing token
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (linked != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TokenThumb(linked.thumbArt(emptySet()).artUrl, Modifier.size(width = 72.dp, height = 52.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Added as another art for",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(linked.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            linked.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { linkKey = null }) { Text("Unlink") }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Token name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (matches.isNotEmpty()) {
                                Text(
                                    "Tap a token to add this as another art for it:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                matches.forEach { t ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable {
                                                linkKey = t.key
                                                name = t.name
                                            }
                                            .padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TokenThumb(t.thumbArt(emptySet()).artUrl, Modifier.size(width = 60.dp, height = 44.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                ColorPips(t.colors, 10.dp)
                                                Text(
                                                    t.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                if (name.isBlank()) "Name it, or tap a matching token above."
                                else "Otherwise it is saved to the Custom folder. Custom tokens with the same name share one entry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Extra text (optional)") },
                        supportingText = { Text("Printed in smaller type under the name, e.g. rules or a note.") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ---- draw or photo
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Draw") })
                        FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Photo") })
                    }

                    if (mode == 0) {
                        Text(
                            "Draw with your finger. Black on white prints best.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            Modifier.fillMaxWidth()
                                .aspectRatio(1.37f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .onSizeChanged { canvasSize = it }
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { o ->
                                        strokes.add(DrawStroke(mutableStateListOf(o), penNow, eraseNow))
                                    })
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { o -> strokes.add(DrawStroke(mutableStateListOf(o), penNow, eraseNow)) },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            strokes.lastOrNull()?.pts?.add(change.position)
                                        },
                                    )
                                },
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                strokes.forEach { drawStroke(it) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = !erase, onClick = { erase = false }, label = { Text("Pen") })
                            FilterChip(selected = erase, onClick = { erase = true }, label = { Text("Eraser") })
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
                            TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                        }
                        LabeledSlider("Brush size: ${pen.roundToInt()}", pen, 3f..48f) { pen = it }
                    } else {
                        OutlinedButton(
                            onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        ) { Text(if (photo == null) "Choose a picture" else "Choose a different picture") }
                        photo?.let { p ->
                            Text(
                                "Drag to move, pinch or use the slider to zoom.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Wide", "Square", "Tall", "Whole").forEachIndexed { i, label ->
                                    FilterChip(
                                        selected = shape == i,
                                        onClick = {
                                            shape = i
                                            zoom = 1f
                                            fx = 0.5f
                                            fy = 0.5f
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            val view = remember(p, aspect, zoom, fx, fy) { cropPhoto(p, aspect, zoom, fx, fy, 600) }
                            Box(
                                Modifier.fillMaxWidth()
                                    .aspectRatio(aspect)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                    .onSizeChanged { cropBoxW = it.width.coerceAtLeast(1) }
                                    .pointerInput(p, aspect) {
                                        detectTransformGestures { _, pan, gestureZoom, _ ->
                                            zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                            val (rw, _) = cropRegion(p.width.toFloat(), p.height.toFloat(), aspect, zoom)
                                            val perPx = rw / cropBoxW // source pixels per screen pixel
                                            fx = (fx - pan.x * perPx / p.width).coerceIn(0f, 1f)
                                            fy = (fy - pan.y * perPx / p.height).coerceIn(0f, 1f)
                                        }
                                    },
                            ) {
                                Image(
                                    bitmap = view.asImageBitmap(),
                                    contentDescription = "Crop",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds,
                                )
                            }
                            LabeledSlider("Zoom: ${"%.1f".format(zoom)}x", zoom, 1f..6f) { zoom = it }
                        }
                        photoPreview?.let { preview ->
                            Text("How it will print", style = MaterialTheme.typography.labelMedium)
                            Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp))) {
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = "Print preview",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth,
                                )
                            }
                            Text(
                                "Contrast, Invert and Auto contrast come from Settings > Image quality. Simple, high-contrast pictures print best.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
