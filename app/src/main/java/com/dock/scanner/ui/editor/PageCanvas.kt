package com.dock.scanner.ui.editor

import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dock.scanner.core.Adjustments
import com.dock.scanner.core.Point2
import com.dock.scanner.data.InkStroke
import com.dock.scanner.data.Page
import com.dock.scanner.data.PageCompositor
import com.dock.scanner.data.StrokeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** What touches on the page do. */
sealed interface CanvasInput {
    data object None : CanvasInput
    data class Draw(val kind: StrokeKind, val color: Int, val width: Float) : CanvasInput
    data object Pick : CanvasInput
}

/**
 * The page as it will be exported: the rendered base image, brightness/contrast applied live
 * through a color matrix, and the strokes drawn as vectors on top. Drawing tools add strokes
 * here; the eyedropper samples colors from the page.
 */
@Composable
fun PageCanvas(
    page: Page,
    brightness: Float,
    contrast: Float,
    modifier: Modifier = Modifier,
    /** Applied to the page itself rather than the whole canvas, e.g. for a shared element transition. */
    pageModifier: Modifier = Modifier,
    input: CanvasInput = CanvasInput.None,
    onStroke: (InkStroke) -> Unit = {},
    onPick: (Int) -> Unit = {},
) {
    val bitmap by rememberDecodedBitmap(page.base, maxSide = 2048)
    val density = LocalDensity.current
    val live = remember { mutableStateListOf<Point2>() }
    val currentInput by rememberUpdatedState(input)
    val currentOnStroke by rememberUpdatedState(onStroke)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentAdjust by rememberUpdatedState(brightness to contrast)

    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: page.aspect
        val (w, h) = if (aspect > boxW / boxH) boxW to boxW / aspect else boxH * aspect to boxH
        val currentBitmap by rememberUpdatedState(bitmap)

        Box(
            Modifier
                .offset { IntOffset(((boxW - w) / 2).roundToInt(), ((boxH - h) / 2).roundToInt()) }
                .size(with(density) { DpSize(w.toDp(), h.toDp()) })
                .align(Alignment.TopStart)
                .then(pageModifier)
                .shadow(6.dp, MaterialTheme.shapes.extraSmall)
                .clip(MaterialTheme.shapes.extraSmall)
                // One handler for both modes: two separate detectors would fight over who
                // consumes the touch-down, and the loser never sees the gesture.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        when (val mode = currentInput) {
                            is CanvasInput.Pick -> {
                                down.consume()
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                val bmp = currentBitmap ?: return@awaitEachGesture
                                val x = (up.position.x / size.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                                val y = (up.position.y / size.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                                val (b, c) = currentAdjust
                                val px = bmp.getPixel(x, y)
                                currentOnPick(
                                    android.graphics.Color.rgb(
                                        Adjustments.apply(android.graphics.Color.red(px), b, c),
                                        Adjustments.apply(android.graphics.Color.green(px), b, c),
                                        Adjustments.apply(android.graphics.Color.blue(px), b, c),
                                    ),
                                )
                            }
                            is CanvasInput.Draw -> {
                                // Ink starts on touch-down and follows the finger 1:1.
                                down.consume()
                                live.clear()
                                live += down.position.normalized(size.width.toFloat(), size.height.toFloat())
                                var last = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // A second finger means zooming, not ink: drop the stroke.
                                    if (event.changes.count { it.pressed } > 1) {
                                        live.clear()
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (hypot(change.position.x - last.x, change.position.y - last.y) > 1.5f) {
                                        live += change.position.normalized(size.width.toFloat(), size.height.toFloat())
                                        last = change.position
                                    }
                                    change.consume()
                                }
                                if (live.isNotEmpty()) {
                                    currentOnStroke(InkStroke(mode.kind, live.toList(), mode.color, mode.width))
                                }
                                live.clear()
                            }
                            CanvasInput.None -> Unit
                        }
                    }
                },
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = "Page",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (Adjustments.isIdentity(brightness, contrast)) null
                    else ColorFilter.colorMatrix(ColorMatrix(Adjustments.matrix(brightness, contrast))),
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    page.strokes.forEach { drawInk(it) }
                    val mode = input
                    if (mode is CanvasInput.Draw && live.isNotEmpty()) {
                        drawInk(InkStroke(mode.kind, live.toList(), mode.color, mode.width))
                    }
                }
            } else if (page.output != null) {
                // The finished page (as on the Review card) until the editable bitmap is decoded.
                AsyncImage(model = page.output, contentDescription = "Page", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (bitmap == null && page.output == null) CircularProgressIndicator()
    }
}

fun DrawScope.drawInk(stroke: InkStroke) {
    drawPath(
        PageCompositor.strokePath(stroke, size.width, size.height).asComposePath(),
        Color(stroke.color),
        style = Stroke(width = stroke.width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun Offset.normalized(width: Float, height: Float) =
    Point2((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))

/**
 * Decodes an image off the main thread, downsampled to about [maxSide]. Keeps showing the
 * previous bitmap until the new one is ready, so re-renders never flash an empty page.
 */
@Composable
fun rememberDecodedBitmap(file: File?, maxSide: Int): State<Bitmap?> {
    val state = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        if (decoded != null) state.value = decoded
    }
    return state
}
