package dev.devstudio.ide.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kit — Hakatron's design system.
 *
 * Everything the new shell draws comes from here, so spacing, corners and colour
 * can't drift screen by screen. Three rules hold the look together:
 *
 *  1. **Tonal, not flat.** Surfaces sit in three steps (ink → surface → surface2)
 *     and depth comes from tone plus a hairline, never a heavy shadow.
 *  2. **One accent per intent.** Violet = act, cyan = inspect, mint = healthy,
 *     amber = caution, rose = stop. Colour never carries meaning alone: every
 *     state also has a word next to it.
 *  3. **Motion is spatial.** Anything that moves on screen uses [Motion.spatial];
 *     anything that changes colour uses a tween. See Motion.kt.
 */
object Pal {
    val ink = Color(0xFF07080C)
    val surface = Color(0xFF0F1117)
    val surface2 = Color(0xFF171A23)
    val surface3 = Color(0xFF1F2431)
    val line = Color(0xFF252B39)
    val lineSoft = Color(0xFF1A1F2B)

    val text = Color(0xFFEDEFF6)
    val dim = Color(0xFF959DB1)
    val faint = Color(0xFF5E6678)

    val violet = Color(0xFF7C5CFF)
    val violetSoft = Color(0xFF2A2350)
    val cyan = Color(0xFF22D3EE)
    val cyanSoft = Color(0xFF123039)
    val mint = Color(0xFF3DDC97)
    val mintSoft = Color(0xFF122E24)
    val amber = Color(0xFFFFB84D)
    val amberSoft = Color(0xFF2E2314)
    val rose = Color(0xFFFF6B81)
    val roseSoft = Color(0xFF321B22)
}

object Tok {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 22.dp
    val xxl = 30.dp

    val rS = 10.dp
    val rM = 14.dp
    val rL = 20.dp
    val rXL = 26.dp
}

/** The page frame every screen uses: title, optional subtitle, actions, content. */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    accent: Color = Pal.violet,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Tok.l, end = Tok.m, top = Tok.m, bottom = Tok.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(width = 4.dp, height = 26.dp)
                        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.25f))),
                            RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(Tok.s + 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Pal.text, fontSize = 19.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-0.3).sp, maxLines = 1)
                    subtitle?.let {
                        Text(it, color = Pal.dim, fontSize = 10.5.sp, maxLines = 1)
                    }
                }
                actions()
            }
            content()
        }
        Box(Modifier.fillMaxSize(), content = floating)
    }
}

/** A tonal card. Depth is tone + hairline; `glow` adds one soft accent wash. */
@Composable
fun TCard(
    modifier: Modifier = Modifier,
    tone: Color = Pal.surface,
    radius: Dp = Tok.rM,
    border: Color = Pal.lineSoft,
    glow: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val base = modifier
        .clip(shape)
        .background(
            if (glow == null) Brush.linearGradient(listOf(tone, tone))
            else Brush.linearGradient(listOf(glow.copy(alpha = 0.16f), tone, tone))
        )
        .border(BorderStroke(1.dp, border), shape)
    Column(
        if (onClick == null) base else base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.foundation.LocalIndication.current,
            onClick = onClick
        )
    ) { content() }
}

@Composable
fun Label(text: String, color: Color = Pal.dim) =
    Text(text, color = color, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)

@Composable
fun SectionRow(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Tok.l, bottom = Tok.s),
        verticalAlignment = Alignment.CenterVertically) {
        Label(text)
        Spacer(Modifier.weight(1f))
        trailing?.let {
            Text(it, color = Pal.violet, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onTrailing?.invoke() })
        }
    }
}

/** Status pill: a dot, a word, and a tone — never colour alone. */
@Composable
fun StatusPill(text: String, tone: Color, filled: Boolean = false) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) tone.copy(alpha = 0.16f) else Color.Transparent)
            .border(BorderStroke(1.dp, tone.copy(alpha = 0.45f)), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).background(tone, RoundedCornerShape(50)))
        Spacer(Modifier.width(5.dp))
        Text(text, color = tone, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The primary action: gradient pill, spring press, optional leading glyph. */
@Composable
fun GlowButton(
    text: String,
    from: Color = Pal.violet,
    to: Color = Pal.cyan,
    enabled: Boolean = true,
    glyph: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Pressable(onClick = onClick, enabled = enabled, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) Brush.horizontalGradient(listOf(from, to))
                    else Brush.horizontalGradient(listOf(Pal.surface3, Pal.surface3))
                )
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            glyph?.let {
                Text(it, color = if (enabled) Pal.ink else Pal.faint, fontSize = 13.sp,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text,
                color = if (enabled) Pal.ink else Pal.faint,
                fontSize = 13.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.2.sp
            )
        }
    }
}

/** Quiet button for secondary actions. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = modifier, scaleDown = 0.97f) {
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(Pal.surface2)
                .border(BorderStroke(1.dp, Pal.line), RoundedCornerShape(50))
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Pal.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Big number tile for the dashboard. */
@Composable
fun StatTile(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    TCard(modifier = modifier, tone = Pal.surface, radius = Tok.rS) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(label, color = Pal.faint, fontSize = 7.5.sp, letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, color = tone, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

/** Launcher-style tile used on the dashboard grid. */
@Composable
fun ActionTile(
    glyph: String,
    title: String,
    sub: String,
    tone: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Pressable(onClick = onClick, modifier = modifier) {
        TCard(tone = Pal.surface, radius = Tok.rM) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .background(tone.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { Text(glyph, color = tone, fontSize = 14.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.height(9.dp))
                Text(title, color = Pal.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1)
                Text(sub, color = Pal.faint, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

/** Skeleton block for anything still loading — never a bare spinner. */
@Composable
fun Skeleton(height: Dp, radius: Dp = Tok.rS) {
    val t = rememberInfiniteTransition(label = "skel")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "sweep"
    )
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius))
            .background(
                Brush.linearGradient(
                    listOf(Pal.surface2, Pal.surface3, Pal.surface2),
                    start = androidx.compose.ui.geometry.Offset(x * 400f, 0f),
                    end = androidx.compose.ui.geometry.Offset(x * 400f + 320f, 320f)
                )
            )
    )
}

/** A live progress ring — used for engine readiness and build progress. */
@Composable
fun Ring(progress: Float, tone: Color, size: Dp = 46.dp, stroke: Dp = 4.dp,
         glyph: String? = null) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.spatial(), label = "ring")
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        drawCircle(color = Pal.surface3, radius = (this.size.minDimension - w) / 2f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(
            color = tone, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        )
    }
    glyph?.let { /* the caller overlays its own centred text */ }
}

/** Monospace block for command output, with a soft inset tone. */
@Composable
fun MonoBlock(text: String, tone: Color = Color(0xFFA9B4C9), maxLines: Int = 18) {
    TCard(tone = Color(0xFF0B0D12), radius = Tok.rS, border = Pal.lineSoft) {
        Text(
            text, color = tone, fontSize = 9.8.sp, lineHeight = 13.5.sp,
            fontFamily = FontFamily.Monospace, maxLines = maxLines,
            modifier = Modifier.fillMaxWidth().padding(10.dp)
        )
    }
}

/** A soft ambient wash behind the dashboard — two very slow blobs, GPU-only. */
@Composable
fun Ambient(tintA: Color = Pal.violet, tintB: Color = Pal.cyan) {
    val t = rememberInfiniteTransition(label = "ambient")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000), RepeatMode.Reverse), label = "phase"
    )
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f
        }.background(
            Brush.radialGradient(
                colors = listOf(tintA.copy(alpha = 0.10f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(120f + phase * 260f, 90f),
                radius = 620f
            )
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(tintB.copy(alpha = 0.07f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(700f - phase * 240f, 420f),
                radius = 520f
            )
        )
    )
}

/** Text sizes used by the new shell, in one place. */
object Type {
    val h1: TextUnit = 22.sp
    val h2: TextUnit = 17.sp
    val body: TextUnit = 12.5.sp
    val small: TextUnit = 11.sp
    val micro: TextUnit = 9.5.sp
}

/** Colour for a log/level word, shared by every screen that shows output. */
fun levelColour(level: String): Color = when (level) {
    "ok", "done" -> Pal.mint
    "warn", "fix" -> Pal.amber
    "err", "stop" -> Pal.rose
    "cmd" -> Pal.cyan
    else -> Pal.dim
}

/* ------------------------------------------------------------------ dialogs */

/**
 * HkDialog — the only dialog shape in the app.
 *
 * Material's default `AlertDialog` (square-ish corners, 24 dp padding, flat
 * surface, text buttons) was the last piece of the old look still visible on
 * every screen, because every screen opens a dialog. This keeps one shape, one
 * tonal surface and one button hierarchy, so a prompt feels like part of the app
 * rather than a system widget on top of it.
 */
@Composable
fun HkDialog(
    title: String,
    sub: String? = null,
    onDismiss: () -> Unit,
    accent: Color = Pal.violet,
    confirmLabel: String = "OK",
    dismissLabel: String? = "Cancel",
    confirmTone: Color = Pal.violet,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Tok.rXL),
        containerColor = Pal.surface,
        tonalElevation = 0.dp,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 3.dp, height = 18.dp)
                        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.2f))),
                            RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    sub?.let { Text(it, color = Pal.faint, fontSize = 9.5.sp, maxLines = 2) }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        },
        confirmButton = {
            Pressable(onClick = onConfirm, scaleDown = 0.96f) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(confirmTone)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(confirmLabel, color = Pal.ink, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        },
        dismissButton = if (dismissLabel == null) null else {
            {
                Pressable(onClick = onDismiss, scaleDown = 0.96f) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Pal.surface2)
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text(dismissLabel, color = Pal.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    )
}

/** The app's one text field: kit corners, ink well, monospace optional. */
@Composable
fun HkField(
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    mono: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 12,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = Pal.text,
            fontSize = if (mono) 11.5.sp else 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Pal.violet),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Tok.rS))
                    .background(Pal.ink)
                    .border(1.dp, Pal.line, RoundedCornerShape(Tok.rS))
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                if (value.isEmpty() && hint.isNotEmpty())
                    Text(hint, color = Pal.faint, fontSize = 10.5.sp,
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
                inner()
            }
        }
    )
}
