package com.dock.scanner.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * A scrolling column of pages that can be pinch-zoomed (up to 5x), double-tapped to zoom in
 * and out, and panned with a fling. At 1x the list scrolls normally; when zoomed, dragging
 * pans the magnified view and vertical movement past its edge scrolls the list. Used for
 * PDFs, documents and slides alike.
 *
 * [content] gets the width available to the pages (inside [contentPadding]).
 */
@Composable
fun ZoomableColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    spacing: Dp = 14.dp,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.(pageWidth: Dp) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var fling by remember { mutableStateOf<Job?>(null) }

    // The view is scaled from its top-left corner, so it can move between (1 - scale) × size and 0.
    fun clamp(o: Offset, s: Float) = Offset(
        o.x.coerceIn(viewport.width * (1 - s), 0f),
        o.y.coerceIn(viewport.height * (1 - s), 0f),
    )

    /** Moves the zoomed view; vertical movement beyond its edge scrolls the list instead. */
    fun pan(delta: Offset) {
        val target = offset + delta
        val clamped = clamp(target, scale)
        offset = clamped
        val leftover = target.y - clamped.y
        if (leftover != 0f) listState.dispatchRawDelta(-leftover / scale)
    }

    BoxWithConstraints(
        modifier
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Initial pass: pinches and zoomed pans are handled here, before the list sees them.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fling?.cancel()
                    val velocity = VelocityTracker()
                    var singlePan = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val centroid = event.calculateCentroid()
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            // Keep the point between the fingers under the fingers.
                            val zoomed = centroid - (centroid - offset) * (newScale / scale) + event.calculatePan()
                            scale = newScale
                            offset = clamp(zoomed, newScale)
                            singlePan = false
                            event.changes.forEach { it.consume() }
                        } else if (pressed == 1 && scale > 1f) {
                            val change = event.changes.first { it.pressed }
                            velocity.addPosition(change.uptimeMillis, change.position)
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                pan(delta)
                                singlePan = true
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale < 1.03f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else if (singlePan) {
                        // Carry the finger's speed on, like a normal scroll fling.
                        val v = velocity.calculateVelocity()
                        fling = scope.launch {
                            var last = Offset.Zero
                            Animatable(Offset.Zero, Offset.VectorConverter)
                                .animateDecay(Offset(v.x, v.y), exponentialDecay()) {
                                    pan(value - last)
                                    last = value
                                }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    fling?.cancel()
                    scope.launch {
                        val startScale = scale
                        val endScale = if (scale > 1.1f) 1f else DOUBLE_TAP_ZOOM
                        val anchor = (tap - offset) / startScale // content point under the finger
                        animate(0f, 1f, animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                            val s = startScale + (endScale - startScale) * t
                            scale = s
                            offset = clamp(tap - anchor * s, s)
                        }
                    }
                })
            },
    ) {
        val direction = LocalLayoutDirection.current
        val pageWidth = maxWidth - contentPadding.calculateStartPadding(direction) - contentPadding.calculateEndPadding(direction)
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            content(pageWidth)
        }
    }
}

/** Zoom and pan of one [ZoomableBox]; a pager reads [zoomed] to stop swiping while a page is magnified. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    val zoomed: Boolean get() = scale > 1f

    internal var viewport = IntSize.Zero
    internal var fling: Job? = null

    // Scaled from the top-left corner, so the view can move between (1 - scale) × size and 0.
    private fun clamp(o: Offset, s: Float) = Offset(
        o.x.coerceIn(viewport.width * (1 - s), 0f),
        o.y.coerceIn(viewport.height * (1 - s), 0f),
    )

    /** A pinch step: keeps the point between the fingers under the fingers. */
    internal fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = clamp(centroid - (centroid - offset) * (newScale / scale) + pan, newScale)
        scale = newScale
    }

    internal fun pan(delta: Offset) {
        offset = clamp(offset + delta, scale)
    }

    /** Double tap: zooms in on [tap], or back out when already zoomed. */
    internal suspend fun toggle(tap: Offset) {
        fling?.cancel()
        val startScale = scale
        val endScale = if (scale > 1.1f) 1f else DOUBLE_TAP_ZOOM
        val anchor = (tap - offset) / startScale // content point under the finger
        animate(0f, 1f, animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
            val s = startScale + (endScale - startScale) * t
            scale = s
            offset = clamp(tap - anchor * s, s)
        }
    }

    fun reset() {
        fling?.cancel()
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * One page that can be pinch-zoomed (up to 5x), double-tapped in and out, and panned with a
 * fling. At 1x one-finger drags are left alone, so a surrounding pager still swipes.
 *
 * With [oneFingerPan] off (while the content is drawn on), one finger belongs to the content:
 * only two-finger gestures zoom and pan, and double tap does nothing. Touch positions inside
 * [content] are in its own unscaled coordinates, so drawing works at any zoom.
 */
@Composable
fun ZoomableBox(
    state: ZoomState,
    modifier: Modifier = Modifier,
    oneFingerPan: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val oneFinger by rememberUpdatedState(oneFingerPan)
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.viewport = it }
            .pointerInput(state) {
                awaitEachGesture {
                    // Initial pass: pinches and zoomed pans are taken before the content or a pager sees them.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.fling?.cancel()
                    val velocity = VelocityTracker()
                    var panned = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            state.transform(event.calculateCentroid(), event.calculateZoom(), event.calculatePan())
                            panned = false
                            event.changes.forEach { it.consume() }
                        } else if (pressed == 1 && state.zoomed && oneFinger) {
                            val change = event.changes.first { it.pressed }
                            velocity.addPosition(change.uptimeMillis, change.position)
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                state.pan(delta)
                                panned = true
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (state.scale < 1.03f) {
                        state.reset()
                    } else if (panned) {
                        val v = velocity.calculateVelocity()
                        state.fling = scope.launch {
                            var last = Offset.Zero
                            Animatable(Offset.Zero, Offset.VectorConverter)
                                .animateDecay(Offset(v.x, v.y), exponentialDecay()) {
                                    state.pan(value - last)
                                    last = value
                                }
                        }
                    }
                }
            }
            .pointerInput(state) {
                detectTapGestures(onDoubleTap = { tap -> if (oneFinger) scope.launch { state.toggle(tap) } })
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            content = content,
        )
    }
}
