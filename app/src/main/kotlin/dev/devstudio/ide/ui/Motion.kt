package dev.devstudio.ide.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.delay

/**
 * Motion — one place for how Hakatron moves.
 *
 * The rules come from Material 3 Expressive's physics model rather than hand-tuned
 * durations:
 *
 *  · **Spatial** motion (position, size, shape) may overshoot — that is what makes a
 *    press feel physical. Damping 0.7–0.8, stiffness 380–500.
 *  · **Effect** motion (colour, opacity) is critically damped and never bounces,
 *    because a colour that overshoots looks like a glitch.
 *  · Everything respects the system "remove animations" setting: when animators are
 *    disabled the tokens collapse to near-instant so the UI stays usable for people
 *    with motion sensitivity.
 *
 * Transforms go through `graphicsLayer` so an animation never triggers relayout.
 */
object Motion {

    /** snappy, no visible bounce — list items, tabs, small state changes */
    fun <T> responsive(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 500f)

    /** expressive: visible but controlled overshoot — buttons, cards, hero moments */
    fun <T> expressive(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = 380f)

    /** spatial/slow: unobtrusive — panels, screen transitions */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = 220f)

    /** effect: colour and alpha are never springy */
    fun <T> fade(ms: Int = 180): FiniteAnimationSpec<T> = tween(ms)
}

/** True when the OS has animations switched off (accessibility / battery saver). */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(animatorScale(ctx) != 0f) }
    DisposableEffect(ctx) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = animatorScale(ctx) != 0f }
        }
        runCatching {
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(observer) } }
    }
    return enabled
}

private fun animatorScale(ctx: android.content.Context): Float = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

/**
 * A surface that answers a touch: it dips to 0.96 on a spatial spring, with the
 * ripple sharing the same interaction source. Cheap — `graphicsLayer` only, so no
 * relayout per frame — and the single most effective way to make a phone UI feel
 * alive.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(pressed: Boolean) -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = Motion.expressive(),
        label = "press-scale"
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
    ) { content(pressed) }
}

/**
 * Numbers that count up instead of jumping — used for the confidence percentage and
 * the file counter. Interruptible: a new target animates from wherever it is.
 */
@Composable
fun animatedCount(target: Float, spec: FiniteAnimationSpec<Float> = Motion.expressive()): Float {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) { value.animateTo(target, spec) }
    return value.value
}

/**
 * Staggered entrance: children slide up 12 dp and fade in, 40 ms apart. Used for
 * plan evidence, step lists and dashboard cards — a list that appears all at once
 * reads as frozen, the same list revealed in sequence reads as fast.
 */
@Composable
fun Staggered(
    index: Int,
    step: Long = 40L,
    distance: Float = 14f,
    content: @Composable (Modifier) -> Unit
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay(index * step)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.spatial(),
        label = "stagger-$index"
    )
    content(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * distance
        }
    )
}

/** Shimmer for skeletons: a highlight sweeping across a placeholder surface. */
@Composable
fun rememberShimmer(): Float {
    val x = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            x.snapTo(0f)
            x.animateTo(1f, tween(durationMillis = 1100))
            delay(120)
        }
    }
    return x.value
}

/** Compose's built-in spring constants, named so call sites read like the spec. */
object Springs {
    const val BOUNCY = Spring.DampingRatioLowBouncy
    const val NO_BOUNCE = Spring.DampingRatioNoBouncy
    const val MEDIUM_LOW = Spring.StiffnessMediumLow
    const val MEDIUM = Spring.StiffnessMedium
}
