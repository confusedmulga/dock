package com.dock.scanner.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dock.scanner.R
import com.dock.scanner.core.Quad
import com.dock.scanner.data.ScanMode
import com.dock.scanner.data.ScanQuality
import com.dock.scanner.ui.ScanViewModel
import com.dock.scanner.ui.components.drawQuad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * @param retakePageId when set, the camera takes a single photo that replaces this page,
 *   then closes; gallery import and the page counter are hidden.
 */
@Composable
fun CameraScreen(vm: ScanViewModel, onDone: () -> Unit, onImported: () -> Unit, retakePageId: String? = null) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    if (granted) {
        CameraContent(vm, onDone, onImported, retakePageId)
    } else {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Dock needs the camera to scan documents. Photos never leave your device.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = onDone) { Text("Not now") }
        }
    }
}

@Composable
private fun CameraContent(vm: ScanViewModel, onDone: () -> Unit, onImported: () -> Unit, retakePageId: String?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val mode by vm.scanMode.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }

    var liveQuad by remember { mutableStateOf<Quad?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }

    val session = remember { CameraSession(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // No usable camera (or it failed to open): say so and close instead of crashing.
    fun cameraFailed() {
        Toast.makeText(context, "The camera isn't available", Toast.LENGTH_SHORT).show()
        onDone()
    }
    LaunchedEffect(lifecycleOwner) {
        previewView.awaitLayout()
        try {
            session.start(lifecycleOwner, previewView, vm.quality.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cameraFailed()
        }
    }
    LaunchedEffect(quality) {
        try {
            session.setQuality(quality)
        } catch (e: Exception) {
            cameraFailed()
        }
    }
    // Edges are only looked for in Auto edges mode; Standard keeps the whole frame.
    DisposableEffect(mode) {
        if (mode == ScanMode.AutoEdges) session.setAnalyzer(analysisExecutor, LiveDocumentAnalyzer { liveQuad = it })
        onDispose {
            session.setAnalyzer(analysisExecutor, null)
            liveQuad = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            session.stop()
            analysisExecutor.shutdown()
        }
    }

    // Focus controls: tapping the preview puts a circle there with the lens slider beside it.
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focused by remember { mutableStateOf<Boolean?>(null) }
    /** Bumped by every touch of the controls; they hide a few seconds after the last one. */
    var focusTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusPoint, focusTouch) {
        if (focusPoint != null) {
            delay(FOCUS_CONTROLS_MS)
            focusPoint = null
        }
    }
    val manual = session.manualFocus != null
    // While autofocus is on, the slider follows the lens.
    var autoPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(focusPoint != null, manual) {
        while (focusPoint != null && !manual) {
            autoPosition = session.lensPosition
            delay(80)
        }
    }
    var zoomTouch by remember { mutableIntStateOf(0) }
    var showZoom by remember { mutableStateOf(false) }
    LaunchedEffect(zoomTouch) {
        if (zoomTouch == 0) return@LaunchedEffect
        showZoom = true
        delay(1200)
        showZoom = false
    }

    fun toggleFocusMode() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (manual) {
            // Back to autofocus: the held position and any tapped point are forgotten.
            session.autoFocus()
            focusPoint = null
        } else {
            session.holdFocus(session.lensPosition)
            focusPoint = Offset(previewSize.width / 2f, previewSize.height / 2f)
            focused = null
            focusTouch++
        }
    }

    // Back works like the close button once pages exist: they go to Review instead of staying
    // in a session that nothing on screen shows.
    BackHandler(enabled = retakePageId == null && pages.isNotEmpty()) { onDone() }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) {
            vm.importImages(uris)
            // Imports finish in the background, so don't wait for the pages to exist.
            onImported()
        }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(300))
        }
        val cropToEdges = vm.scanMode.value == ScanMode.AutoEdges
        val file = vm.newCaptureFile()
        session.takePicture(
            file,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    if (retakePageId != null) {
                        vm.retakePhoto(retakePageId, file, cropToEdges)
                        onDone()
                    } else {
                        vm.addPhoto(file, cropToEdges = cropToEdges)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    file.delete()
                    Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Fixed-focus cameras have nothing to switch.
                if (session.nearestFocus > 0f) FocusModeSwitch(manual, onToggle = ::toggleFocusMode)
            }
            IconButton(onClick = { settingsOpen = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Scan settings", tint = Color.White)
            }
            IconButton(onClick = {
                torchOn = !torchOn
                session.enableTorch(torchOn)
            }) {
                Icon(
                    painterResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off),
                    contentDescription = if (torchOn) "Turn light off" else "Turn light on",
                    tint = Color.White,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // 3:4 matches the sensor, so the photo contains exactly what the preview shows.
            Box(Modifier.aspectRatio(3f / 4f).clip(MaterialTheme.shapes.extraLarge).onSizeChanged { previewSize = it }) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                val outline = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    if (mode == ScanMode.AutoEdges) {
                        liveQuad?.let { drawQuad(it, Rect(0f, 0f, size.width, size.height), outline, 3.dp.toPx(), 0.22f) }
                    } else {
                        // Thirds, to line the page up by eye.
                        val line = Color.White.copy(alpha = 0.35f)
                        for (k in 1..2) {
                            drawLine(line, Offset(size.width * k / 3, 0f), Offset(size.width * k / 3, size.height), 1.dp.toPx())
                            drawLine(line, Offset(0f, size.height * k / 3), Offset(size.width, size.height * k / 3), 1.dp.toPx())
                        }
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                focusPoint = tap
                                focused = null
                                focusTouch++
                                session.focusOn(previewView.meteringPointFactory.createPoint(tap.x, tap.y)) { focused = it }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) {
                                    session.zoomBy(zoom)
                                    zoomTouch++
                                }
                            }
                        },
                ) {
                    focusPoint?.let { point ->
                        FocusControls(
                            point = point,
                            position = session.manualFocus ?: autoPosition,
                            nearest = session.nearestFocus,
                            held = manual,
                            focused = focused,
                            distance = session.manualFocus?.takeIf { session.calibrated }?.let(::focusDistance),
                            onPosition = {
                                session.holdFocus(it)
                                focusTouch++
                            },
                        )
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (quality == ScanQuality.Original) CameraLabel("Original quality")
                    if (showZoom) {
                        Spacer(Modifier.height(6.dp))
                        CameraLabel("%.1f×".format(session.zoomRatio))
                    }
                }
                CameraLabel(
                    when {
                        mode == ScanMode.Standard && retakePageId != null -> "Retake: fit the page in the frame"
                        mode == ScanMode.Standard -> "Fit the page in the frame"
                        retakePageId != null && liveQuad == null -> "Retake: point the camera at the page"
                        liveQuad == null -> "Point the camera at a document"
                        else -> "Document found"
                    },
                    Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }

        ModeSwitch(mode, onMode = vm::setScanMode)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (retakePageId == null) {
                IconButton(
                    onClick = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_gallery), contentDescription = "Import from gallery", tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(56.dp))
            }

            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.Gray else Color.White)
                    .clickable(enabled = !capturing, onClickLabel = "Take photo") { capture() },
                contentAlignment = Alignment.Center,
            ) {
                if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
            }

            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                val last = pages.lastOrNull()
                if (last != null && retakePageId == null) {
                    BadgedBox(badge = { Badge { Text("${pages.size}") } }) {
                        AsyncImage(
                            model = last.output ?: last.source,
                            contentDescription = "Review ${pages.size} pages",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(MaterialTheme.shapes.small)
                                .border(2.dp, Color.White, MaterialTheme.shapes.small)
                                .clickable(onClick = onDone),
                        )
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ScanSettingsSheet(
            quality = quality,
            hasPages = pages.isNotEmpty(),
            onQuality = vm::setQuality,
            onDismiss = { settingsOpen = false },
        )
    }
}

private const val FOCUS_CONTROLS_MS = 4000L
private val ReticleSize = 72.dp
private val SliderSize = DpSize(36.dp, 168.dp)
private val SliderIcon = 16.dp
private val SliderGap = 18.dp

/** Readable on white paper too: white on a soft dark halo. */
private val Halo = Color.Black.copy(alpha = 0.3f)

/** Text over the preview, on a dark pill so it reads against any background. */
@Composable
private fun CameraLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Diopters as a distance from the lens. */
private fun focusDistance(diopters: Float): String {
    if (diopters < 0.1f) return "∞"
    val cm = 100f / diopters
    return if (cm >= 100f) "%.1f m".format(cm / 100f) else "${cm.roundToInt()} cm"
}

@Composable
private fun FocusModeSwitch(manual: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .toggleable(value = manual, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = if (manual) "Manual focus" else "Auto focus" }
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_focus),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp).size(18.dp),
        )
        listOf("Auto" to !manual, "Manual" to manual).forEach { (label, selected) ->
            val background by animateColorAsState(if (selected) Color.White else Color.Transparent, label = "focusSegment")
            Text(
                label,
                color = if (selected) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(background, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Auto edges or Standard, under the preview like a camera app's modes. */
@Composable
private fun ModeSwitch(mode: ScanMode, onMode: (ScanMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
        ScanMode.entries.forEach { option ->
            val selected = option == mode
            val background by animateColorAsState(
                if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                label = "modeBackground",
            )
            Text(
                option.label,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .background(background)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onMode(option) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The circle where the preview was tapped and, beside it, the lens slider: far at the top,
 * near at the bottom. Its thumb is hollow while it follows autofocus and solid once dragged,
 * which holds focus where it was let go.
 */
@Composable
private fun FocusControls(
    point: Offset,
    position: Float,
    nearest: Float,
    held: Boolean,
    focused: Boolean?,
    distance: String?,
    onPosition: (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val reticle = with(density) { ReticleSize.toPx() }
        val sliderWidth = with(density) { SliderSize.width.toPx() }
        val sliderHeight = with(density) { SliderSize.height.toPx() }
        val gap = with(density) { SliderGap.toPx() }
        val cx = point.x.coerceIn(reticle / 2, width - reticle / 2)
        val cy = point.y.coerceIn(reticle / 2, height - reticle / 2)

        val scale = remember(point) { Animatable(1.3f) }
        LaunchedEffect(point) { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        Canvas(
            Modifier
                .offset { IntOffset((cx - reticle / 2).roundToInt(), (cy - reticle / 2).roundToInt()) }
                .size(ReticleSize)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        ) {
            val radius = size.minDimension / 2 - 2.dp.toPx()
            drawCircle(Halo, radius, style = Stroke(3.5.dp.toPx()))
            drawCircle(Color.White, radius, style = Stroke(1.5.dp.toPx()))
            if (focused == true && !held) drawCircle(Color.White, 3.dp.toPx())
        }

        if (distance != null) {
            CameraLabel(
                distance,
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset((cx - width / 2).roundToInt(), (cy - reticle / 2 - 40.dp.toPx()).coerceAtLeast(0f).roundToInt()) },
            )
        }

        if (nearest > 0f) {
            // Beside the circle, on whichever side has room.
            val sx = if (cx + reticle / 2 + gap + sliderWidth <= width) cx + reticle / 2 + gap else cx - reticle / 2 - gap - sliderWidth
            val sy = (cy - sliderHeight / 2).coerceIn(0f, (height - sliderHeight).coerceAtLeast(0f))
            FocusSlider(
                position = position,
                nearest = nearest,
                held = held,
                onPosition = onPosition,
                modifier = Modifier
                    .offset { IntOffset(sx.roundToInt(), sy.roundToInt()) }
                    .size(SliderSize),
            )
        }
    }
}

@Composable
private fun FocusSlider(position: Float, nearest: Float, held: Boolean, onPosition: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(position)
    val update by rememberUpdatedState(onPosition)
    Canvas(
        modifier
            .semantics { contentDescription = "Focus distance" }
            .pointerInput(nearest) {
                awaitEachGesture {
                    // Relative, like the camera app's sliders: the thumb never jumps to the finger.
                    val down = awaitFirstDown()
                    down.consume()
                    val track = size.height - 2 * (SliderIcon.toPx() + 8.dp.toPx())
                    var value = current
                    drag(down.id) { change ->
                        value = (value + change.positionChange().y / track * nearest).coerceIn(0f, nearest)
                        change.consume()
                        update(value)
                    }
                }
            },
    ) {
        val icon = SliderIcon.toPx()
        val top = icon + 8.dp.toPx()
        val bottom = size.height - icon - 8.dp.toPx()
        val x = size.width / 2
        drawFar(Offset(x - icon / 2, 0f), icon)
        drawNear(Offset(x - icon / 2, size.height - icon), icon)
        drawLine(Halo, Offset(x, top), Offset(x, bottom), 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.85f), Offset(x, top), Offset(x, bottom), 2.dp.toPx(), cap = StrokeCap.Round)
        val thumb = Offset(x, top + (bottom - top) * (position / nearest).coerceIn(0f, 1f))
        val r = 8.dp.toPx()
        drawCircle(Halo, r + 1.5.dp.toPx(), thumb)
        if (held) {
            drawCircle(Color.White, r, thumb)
        } else {
            drawCircle(Color.Black.copy(alpha = 0.35f), r, thumb)
            drawCircle(Color.White, r - 1.dp.toPx(), thumb, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Mountains: focus far away. */
private fun DrawScope.drawFar(at: Offset, size: Float) {
    val path = Path().apply {
        moveTo(at.x, at.y + size * 0.85f)
        lineTo(at.x + size * 0.36f, at.y + size * 0.25f)
        lineTo(at.x + size * 0.58f, at.y + size * 0.6f)
        lineTo(at.x + size * 0.72f, at.y + size * 0.42f)
        lineTo(at.x + size, at.y + size * 0.85f)
        close()
    }
    drawPath(path, Halo, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    drawPath(path, Color.White)
}

/** A flower: focus close up, as on camera macro buttons. */
private fun DrawScope.drawNear(at: Offset, size: Float) {
    val center = Offset(at.x + size / 2, at.y + size * 0.36f)
    val petal = size * 0.17f
    val spread = size * 0.19f
    drawLine(Color.White, Offset(center.x, center.y), Offset(center.x, at.y + size), 1.5.dp.toPx())
    listOf(Offset(0f, -spread), Offset(spread, 0f), Offset(0f, spread), Offset(-spread, 0f)).forEach {
        drawCircle(Halo, petal + 1.dp.toPx(), center + it)
    }
    listOf(Offset(0f, -spread), Offset(spread, 0f), Offset(0f, spread), Offset(-spread, 0f)).forEach {
        drawCircle(Color.White, petal, center + it)
    }
    drawCircle(Color.Black.copy(alpha = 0.5f), petal * 0.7f, center)
}

/** Suspends until the view has been laid out: the camera's crop is set from its size. */
private suspend fun View.awaitLayout() {
    if (isLaidOut && width > 0 && height > 0) return
    suspendCancellableCoroutine { cont -> doOnLayout { if (cont.isActive) cont.resume(Unit) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSettingsSheet(quality: ScanQuality, hasPages: Boolean, onQuality: (ScanQuality) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text("Scan settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            Text(
                "Scan quality",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
            listOf(ScanQuality.Original, ScanQuality.Standard).forEach { option ->
                val selected = option == quality
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onQuality(option) })
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.padding(top = 10.dp, end = 12.dp)) {
                        Text(
                            if (option == ScanQuality.Standard) "${option.label} (default)" else option.label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (hasPages) {
                Text(
                    "Also applies to the pages you've already scanned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                )
            }
        }
    }
}
