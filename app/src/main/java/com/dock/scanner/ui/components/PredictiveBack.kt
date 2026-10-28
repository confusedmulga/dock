package com.dock.scanner.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/** One duration for every screen change, so forward and back feel like the same motion reversed. */
private const val SCREEN_MS = 380

/** While the back gesture is held, the screen follows the finger but never leaves more than this. */
private const val MAX_HELD_OFFSET = 0.9f

/** How much the screen zooms out over that distance: down to 75% of its size. */
private const val MAX_SHRINK = 0.25f

/** Material's emphasized decelerate: quick start, gentle landing. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Opening a screen: it slides in from the right while the old one drifts a little to the left. */
val pushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SCREEN_MS, easing = EmphasizedDecelerate)) { it }
}
val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(SCREEN_MS, easing = EmphasizedDecelerate)) { -it / 4 }
}

/**
 * Going back: the screen underneath slides out from the left. Linear on purpose: during the
 * predictive back gesture Navigation seeks this transition with the finger's progress, so
 * the motion maps 1:1 to the gesture.
 */
val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SCREEN_MS, easing = LinearEasing)) { -it / 4 }
}

/** The leaving screen stays put as far as Navigation is concerned; [BackSwipe] moves it. */
val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    ExitTransition.KeepUntilTransitionsFinished
}

/**
 * Wraps a destination so that going back slides the whole screen to the right, as one card
 * with rounded corners and a shadow, zooming out slightly and revealing the previous screen underneath.
 *
 * - While the back gesture is held the screen follows the finger, up to [MAX_HELD_OFFSET] of the width.
 * - When released past the threshold it carries on from exactly where the finger let go and slides off.
 * - When cancelled it slides back into place.
 * - A plain back (button, or popping in code) slides it all the way off.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedContentScope.BackSwipe(nav: NavController, entry: NavBackStackEntry, content: @Composable () -> Unit) {
    val top by nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(nav.currentBackStackEntry)
    val onTop = top?.id == entry.id
    // A screen that's still in the stack while leaving is the one a new screen is opening over.
    val inStack = onTop || nav.previousBackStackEntry?.id == entry.id
    // Same length and easing as the pop transition, so it reads Navigation's seek position directly.
    val progress = transition.animateFloat(
        transitionSpec = { tween(SCREEN_MS, easing = LinearEasing) },
        label = "backSwipe",
    ) { if (it == EnterExitState.PostExit) 1f else 0f }
    val leaving = transition.targetState == EnterExitState.PostExit
    val tracker = remember { SwipeTracker() }
    val shape = MaterialTheme.shapes.extraLarge

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                val offset = when {
                    !leaving -> tracker.reset()
                    onTop -> tracker.held(p, MAX_HELD_OFFSET * p) // finger still down
                    !inStack -> tracker.released(p) // committed: popped from the back stack
                    else -> 0f // a new screen is opening on top; the push exit handles this one
                }
                translationX = offset * size.width
                // Shrinks as it slides: 100% at rest, 75% once it's 90% across.
                val scale = 1f - MAX_SHRINK * (offset / MAX_HELD_OFFSET).coerceAtMost(1f)
                scaleX = scale
                scaleY = scale
                if (offset > 0f) {
                    shadowElevation = 24.dp.toPx()
                    this.shape = shape
                    clip = true
                }
            },
    ) {
        CompositionLocalProvider(LocalScreenAnimation provides this@BackSwipe, content = content)
    }
}

/** The layout that runs shared element transitions between screens; null outside it (e.g. in previews). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitions = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Which tab a list of PDFs is on, for [SharedKeys.pdf]. */
val LocalSharedOrigin = staticCompositionLocalOf { "" }

/** The current screen's enter/exit animation, which its shared elements follow. Provided by [BackSwipe]. */
val LocalScreenAnimation = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared element keys, so both ends of a transition name things the same way. */
object SharedKeys {
    /**
     * A PDF's first page: its thumbnail in a list, the page in the viewer. [origin] is the tab
     * the list is on: the pager keeps neighbouring tabs composed, so the same PDF can be on
     * screen twice and each copy needs its own key.
     */
    fun pdf(documentId: String, origin: String) = "pdf/$origin/$documentId"

    /** A scanned page: its card in Review, the page in the editor. */
    fun page(pageId: String) = "page/$pageId"
}

/**
 * Growing means a screen is opening (a thumbnail becomes the full page): same motion as the
 * screen sliding in. Shrinking means going back, which the predictive back gesture seeks, so
 * it moves linearly and tracks the finger.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val SharedBounds = BoundsTransform { initial, target ->
    tween(SCREEN_MS, easing = if (target.width < initial.width) LinearEasing else EmphasizedDecelerate)
}

/**
 * Pairs this element with the element of the same [key] on the screen being opened or closed:
 * during the change it moves and resizes from one to the other, following the back gesture
 * too. Does nothing when there's no matching element (e.g. the list was scrolled away).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPage(key: String): Modifier {
    val shared = LocalSharedTransitions.current ?: return this
    val screen = LocalScreenAnimation.current ?: return this
    return with(shared) {
        this@sharedPage.sharedElement(rememberSharedContentState(key), screen, boundsTransform = SharedBounds)
    }
}

/** Remembers where the finger let go so the commit animation continues from there without a jump. */
private class SwipeTracker {
    private var lastProgress = 0f
    private var lastOffset = 0f
    private var anchor: Pair<Float, Float>? = null

    fun reset(): Float {
        lastProgress = 0f
        lastOffset = 0f
        anchor = null
        return 0f
    }

    fun held(progress: Float, offset: Float): Float {
        lastProgress = progress
        lastOffset = offset
        anchor = null
        return offset
    }

    /** Maps the rest of the transition onto the distance still to go, from the release point to off-screen. */
    fun released(progress: Float): Float {
        val (p0, o0) = anchor ?: (lastProgress to lastOffset).also { anchor = it }
        if (p0 >= 1f) return 1f
        val t = ((progress - p0) / (1f - p0)).coerceIn(0f, 1f)
        return o0 + (1f - o0) * t
    }
}
