// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

package com.example.tokenprinter

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * Long press on a token card: the card grows while the finger is down, then hops up (a bouncy spring) and
 * the rest of the screen blurs and dims. Two buttons appear; slide onto one and let go to run it with the
 * settings you used last. Letting go anywhere else just closes it.
 *
 * The finger stays on the original card the whole time, so the buttons are not touched themselves: this file
 * works out where they are and checks the finger's position against them.
 */

/**
 * Buzzes the phone's vibration motor directly. The standard Compose haptic call goes through the system's
 * "touch feedback" setting, which many phones ship turned off, so it gave nothing on a long press. If the
 * phone has no vibrator (or refuses), it falls back to the standard call.
 */
class QuickHaptics(ctx: Context, private val fallback: HapticFeedback) {
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    private fun buzz(effect: Int, millis: Long, amplitude: Int, type: HapticFeedbackType) {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            fallback.performHapticFeedback(type)
            return
        }
        try {
            v.vibrate(
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(effect)
                else VibrationEffect.createOneShot(millis, amplitude),
            )
        } catch (e: Exception) {
            fallback.performHapticFeedback(type)
        }
    }

    /** The card lifts: a firm thump. */
    fun lift() = buzz(VibrationEffect.EFFECT_HEAVY_CLICK, 40, 255, HapticFeedbackType.LongPress)

    /** The finger moves onto or off a button: a light tick. */
    fun tick() = buzz(VibrationEffect.EFFECT_TICK, 12, 90, HapticFeedbackType.TextHandleMove)

    /** A button is chosen. */
    fun confirm() = buzz(VibrationEffect.EFFECT_CLICK, 25, 180, HapticFeedbackType.LongPress)
}

enum class QuickAction(val label: String) { Export("Export"), Print("Print"), Queue("Add to queue") }

/** Where the lifted card and the two buttons sit, in window pixels. */
class QuickLayout(
    val card: Rect,
    val scale: Float,
    val export: Rect,
    val print: Rect,
    val queue: Rect,
    /** All three in one row (not enough room above and below the card), so the queue label is shortened. */
    val row: Boolean,
    private val slop: Float,
) {
    fun hit(p: Offset): QuickAction? = when {
        export.inflate(slop).contains(p) -> QuickAction.Export
        print.inflate(slop).contains(p) -> QuickAction.Print
        queue.inflate(slop).contains(p) -> QuickAction.Queue
        else -> null
    }
}

/** One long press in progress (or just finished, while it fades out). */
@Stable
class QuickPress(
    val owner: Any,
    val token: Token,
    val art: TokenArt,
    val qty: Int,
    val bounds: Rect,
    val layout: QuickLayout,
    val content: @Composable (Modifier) -> Unit,
) {
    var hover by mutableStateOf<QuickAction?>(null)
    var chosen by mutableStateOf<QuickAction?>(null)
    var open by mutableStateOf(true)
}

@Stable
class QuickPressController(
    private val onPrint: (Token, TokenArt, Int) -> Unit,
    private val onExport: (Token, TokenArt) -> Unit,
    private val onQueue: (Token, TokenArt, Int) -> Unit,
) {
    var active by mutableStateOf<QuickPress?>(null)
        private set

    /** Filled in by the app root so the buttons can be kept on screen and clear of the system bars. */
    var rootSize: IntSize = IntSize.Zero
    var safeTop: Float = 0f
    var safeBottom: Float = 0f

    private var haptic: QuickHaptics? = null

    fun begin(
        owner: Any,
        token: Token,
        art: TokenArt,
        qty: Int,
        bounds: Rect,
        density: Density,
        haptic: QuickHaptics,
        pointer: Offset,
        content: @Composable (Modifier) -> Unit,
    ) {
        this.haptic = haptic
        val layout = computeLayout(bounds, density)
        active = QuickPress(owner, token, art, qty, bounds, layout, content).also { it.hover = layout.hit(pointer) }
        haptic.lift()
    }

    fun move(p: Offset) {
        val a = active ?: return
        if (!a.open) return
        val h = a.layout.hit(p)
        if (h != a.hover) {
            a.hover = h
            if (h != null) haptic?.tick()
        }
    }

    fun release(p: Offset) {
        val a = active ?: return
        if (!a.open) return
        val h = a.layout.hit(p)
        a.hover = h
        a.chosen = h
        a.open = false
        when (h) {
            QuickAction.Print -> {
                haptic?.confirm()
                onPrint(a.token, a.art, a.qty)
            }
            QuickAction.Export -> {
                haptic?.confirm()
                onExport(a.token, a.art)
            }
            QuickAction.Queue -> {
                haptic?.confirm()
                onQueue(a.token, a.art, a.qty)
            }
            null -> Unit
        }
    }

    fun clear(a: QuickPress) {
        if (active === a) active = null
    }

    private fun clampSafe(v: Float, lo: Float, hi: Float) = if (hi < lo) lo else v.coerceIn(lo, hi)

    private fun computeLayout(bounds: Rect, d: Density): QuickLayout = with(d) {
        val rw = rootSize.width.toFloat()
        val rh = rootSize.height.toFloat()
        val edge = 2.dp.toPx()
        val margin = 12.dp.toPx()
        val maxScale = if (bounds.width > 0f && rw > 0f) (rw - 2 * edge) / bounds.width else 1f
        val s = minOf(1.12f, maxScale).coerceAtLeast(1f)
        val cw = bounds.width * s
        val ch = bounds.height * s
        val cx = clampSafe(bounds.center.x, edge + cw / 2, rw - edge - cw / 2)
        val cy = bounds.center.y
        val card = Rect(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)

        val bw = 116.dp.toPx()
        val bh = 76.dp.toPx()
        val gap = 14.dp.toPx()
        val pairW = 2 * bw + gap
        val left = clampSafe(cx - pairW / 2, margin, rw - margin - pairW)
        val gapY = 26.dp.toPx() // leaves room for the lift and shadow
        val minTop = safeTop + margin
        val maxTop = rh - safeBottom - margin - bh
        val below = card.bottom + gapY
        val above = card.top - gapY - bh
        val roomBelow = below <= maxTop
        val roomAbove = above >= minTop
        if (roomBelow && roomAbove) {
            // Export and Print below the card, Add to queue above it.
            val qLeft = clampSafe(cx - bw / 2, margin, rw - margin - bw)
            QuickLayout(
                card = card,
                scale = s,
                export = Rect(left, below, left + bw, below + bh),
                print = Rect(left + bw + gap, below, left + pairW, below + bh),
                queue = Rect(qLeft, above, qLeft + bw, above + bh),
                row = false,
                slop = 12.dp.toPx(),
            )
        } else {
        // Only one side has room: all three in a row, Add to queue in the middle.
        val rowGap = 10.dp.toPx()
        val w3 = minOf(bw, (rw - 2 * margin - 2 * rowGap) / 3f)
        val rowW = 3 * w3 + 2 * rowGap
        val l3 = clampSafe(cx - rowW / 2, margin, rw - margin - rowW)
        val top = clampSafe(if (roomBelow) below else above, minTop, maxTop)
        QuickLayout(
            card = card,
            scale = s,
            export = Rect(l3, top, l3 + w3, top + bh),
            queue = Rect(l3 + w3 + rowGap, top, l3 + 2 * w3 + rowGap, top + bh),
            print = Rect(l3 + 2 * (w3 + rowGap), top, l3 + rowW, top + bh),
            row = true,
            slop = 8.dp.toPx(),
        )
        }
    }
}

val LocalQuickPress = staticCompositionLocalOf<QuickPressController?> { null }

private class CoordsHolder {
    var coords: LayoutCoordinates? = null
}

/**
 * Put this on a token card (after its padding) to give it the long-press menu. [content] draws the same card
 * again for the lifted copy; it is given a modifier that fills the size of the original.
 * Taps and scrolling keep working: nothing is taken from the touch until the press is long enough.
 */
@Composable
fun Modifier.quickPress(
    token: Token,
    art: TokenArt,
    qty: Int = 1,
    content: @Composable (Modifier) -> Unit,
): Modifier {
    val c = LocalQuickPress.current ?: return this
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val standardHaptic = LocalHapticFeedback.current
    val haptic = remember(ctx, standardHaptic) { QuickHaptics(ctx, standardHaptic) }
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val owner = remember { Any() }
    val holder = remember { CoordsHolder() }
    var pressed by remember { mutableStateOf(false) }
    val tokenNow by rememberUpdatedState(token)
    val artNow by rememberUpdatedState(art)
    val qtyNow by rememberUpdatedState(qty)
    val contentNow by rememberUpdatedState(content)

    // Slowly swells while the finger is down, which is the hint that holding does something.
    val grow by animateFloatAsState(
        targetValue = if (pressed) 1.04f else 1f,
        animationSpec = if (pressed) tween(timeout.toInt(), easing = FastOutSlowInEasing)
        else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "quickGrow",
    )

    return this
        .onGloballyPositioned { holder.coords = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                // Initial pass: we see the touch before the card's own click handling and take nothing from it
                // unless this turns out to be a long press.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                val slop = viewConfiguration.touchSlop
                val stillHolding = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed || ch.isConsumed || (ch.position - down.position).getDistance() > slop) {
                            break
                        }
                    }
                } == null
                if (!stillHolding) {
                    pressed = false
                    return@awaitEachGesture
                }

                val lc = holder.coords
                if (lc == null || !lc.isAttached) {
                    pressed = false
                    return@awaitEachGesture
                }
                val origin = lc.positionInRoot()
                val bounds = Rect(origin, Size(lc.size.width.toFloat(), lc.size.height.toFloat()))
                pressed = false
                c.begin(owner, tokenNow, artNow, qtyNow, bounds, density, haptic, origin + down.position, contentNow)

                // From here the finger belongs to the menu: take every event so the list doesn't scroll
                // and the card doesn't register a tap when the finger lifts.
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    val p = origin + ch.position
                    val up = !ch.pressed
                    e.changes.forEach { it.consume() }
                    if (up) {
                        c.release(p)
                        break
                    }
                    c.move(p)
                }
            }
        }
        .graphicsLayer {
            val hidden = c.active?.let { it.owner === owner && it.open } == true
            scaleX = grow
            scaleY = grow
            alpha = if (hidden) 0f else 1f
        }
}

// ------------------------------------------------------------------ overlay

/** Draws the dimmed backdrop, the lifted card and the two buttons. Place it above the (blurred) app content. */
@Composable
fun QuickPressLayer(c: QuickPressController) {
    val a = c.active ?: return
    key(a) { QuickPressContent(c, a) }
}

@Composable
private fun QuickPressContent(c: QuickPressController, a: QuickPress) {
    val d = LocalDensity.current
    val vis by animateFloatAsState(
        targetValue = if (a.open) 1f else 0f,
        animationSpec = tween(if (a.open) 160 else 200),
        label = "quickVis",
    )
    // 0 -> 1 with an overshoot, which is the hop.
    val hop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        hop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(a.open) {
        if (!a.open) {
            delay(260)
            c.clear(a)
        }
    }

    val b = a.bounds
    val l = a.layout
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = vis }
                .background(Color.Black.copy(alpha = 0.32f)),
        )
        Box(
            Modifier
                .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
                .size(with(d) { b.width.toDp() }, with(d) { b.height.toDp() })
                .graphicsLayer {
                    val j = hop.value
                    val settle = j.coerceIn(0f, 1f)
                    val s = 1.04f + (l.scale - 1.04f) * settle + (j - settle) * 0.05f
                    val leave = 1f - 0.05f * (1f - vis)
                    scaleX = s * leave
                    scaleY = s * leave
                    translationX = (l.card.center.x - b.center.x) * settle
                    translationY = (l.card.center.y - b.center.y) * settle - 14.dp.toPx() * j
                    shadowElevation = 28.dp.toPx() * settle
                    shape = RoundedCornerShape(16.dp)
                    clip = false
                    alpha = vis
                },
        ) {
            a.content(Modifier.fillMaxSize())
        }
        QuickButton(QuickAction.Export, Icons.Filled.Share, l.export, a, vis, delayMs = 30)
        QuickButton(QuickAction.Queue, Icons.AutoMirrored.Filled.PlaylistAdd, l.queue, a, vis, delayMs = 60)
        QuickButton(QuickAction.Print, Icons.Filled.Print, l.print, a, vis, delayMs = 90)
    }
}

/**
 * A tile with the icon over its label. It slides out from behind the lifted card, and while the finger is over it
 * it fills with the accent color, swells and its corners soften into a rounder blob, so the choice feels alive.
 */
@Composable
private fun QuickButton(
    action: QuickAction,
    icon: ImageVector,
    rect: Rect,
    a: QuickPress,
    vis: Float,
    delayMs: Int,
) {
    val d = LocalDensity.current
    val hovered = a.hover == action
    val picked = a.chosen == action
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }
    val grow by animateFloatAsState(
        targetValue = when {
            picked -> 1.22f
            hovered -> 1.1f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "quickBtnScale",
    )
    val corner by animateDpAsState(if (hovered) 38.dp else 22.dp, spring(dampingRatio = 0.6f), label = "quickBtnCorner")
    val iconLift by animateDpAsState(if (hovered) (-3).dp else 0.dp, spring(dampingRatio = 0.5f), label = "quickBtnIcon")
    val bg by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "quickBtnBg",
    )
    val fg by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "quickBtnFg",
    )
    val from = a.layout.card.center - rect.center
    val shape = RoundedCornerShape(corner)
    // Labels must fit the tile: follow the phone's font size, but only up to a point.
    val capped = Density(d.density, minOf(d.fontScale, 1.15f))
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(d) { rect.width.toDp() }, with(d) { rect.height.toDp() })
            .graphicsLayer {
                val p = appear.value
                val back = 1f - p
                translationX = from.x * back * 0.6f
                translationY = from.y * back * 0.6f
                val sc = (0.4f + 0.6f * p) * grow
                scaleX = sc
                scaleY = sc
                alpha = p.coerceIn(0f, 1f) * vis
                shadowElevation = (if (hovered) 20.dp else 10.dp).toPx()
                this.shape = shape
                clip = false
            }
            .background(bg, shape)
            .border(1.dp, fg.copy(alpha = if (hovered) 0f else 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalDensity provides capped) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp).offset(y = iconLift))
                Spacer(Modifier.height(4.dp))
                Text(
                    action.label,
                    color = fg,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (hovered) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
