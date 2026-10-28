package com.dock.scanner.ui.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dock.scanner.data.ScanQuality
import java.io.File
import java.util.concurrent.Executor

/**
 * The scanner's camera: preview, still capture and live edge analysis, sharing one 4:3 crop.
 *
 * Beyond what CameraX's controller offers, it can hold the lens at a fixed focus distance, and
 * it reads where autofocus has put the lens from every preview frame, so switching to manual
 * focus starts exactly where autofocus was instead of jumping.
 *
 * Focus distances are in diopters (1 / meters): 0 is infinity, larger is closer.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraSession(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var view: PreviewView? = null
    private var camera: Camera? = null
    private var quality = ScanQuality.Standard
    private var torch = false

    /** Where the lens was in the latest preview frame. Written on the camera's thread. */
    @Volatile
    var lensPosition = 0f
        private set

    /** The closest the lens can focus; 0 when it can't be focused by hand (fixed focus, or no manual control). */
    var nearestFocus by mutableFloatStateOf(0f)
        private set

    /** Whether lens positions are real distances, so they can be shown in centimeters. */
    var calibrated by mutableStateOf(false)
        private set

    /** The lens position focus is held at, or null while autofocus is on. */
    var manualFocus by mutableStateOf<Float?>(null)
        private set

    var zoomRatio by mutableFloatStateOf(1f)
        private set

    private val frames = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lensPosition = it }
        }
    }

    private val preview = Preview.Builder()
        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(FOUR_THREE).build())
        .also { Camera2Interop.Extender(it).setSessionCaptureCallback(frames) }
        .build()

    private val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(FOUR_THREE)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    private var capture = imageCapture(quality)

    /** Starts the camera into [view], which must already be laid out (its size sets the crop). */
    suspend fun start(owner: LifecycleOwner, view: PreviewView, quality: ScanQuality) {
        this.owner = owner
        this.view = view
        this.quality = quality
        preview.setSurfaceProvider(view.surfaceProvider)
        provider = ProcessCameraProvider.awaitInstance(context)
        bind()
    }

    /** Switching quality rebinds the camera, so it only happens when the setting actually changes. */
    fun setQuality(quality: ScanQuality) {
        if (quality == this.quality) return
        this.quality = quality
        bind()
    }

    /** Binds the use cases; throws [IllegalStateException] when the device has no camera to use. */
    private fun bind() {
        val provider = provider ?: return
        val owner = owner ?: return
        // The camera is optional in the manifest: some tablets and laptops only have a front one.
        val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
            ?: throw IllegalStateException("No camera available")
        provider.unbind(preview, capture, analysis)
        capture = imageCapture(quality)
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            // The view port crops all three alike, so the photo and the analysed frames are what the preview shows.
            .apply { view?.viewPort?.let(::setViewPort) }
            .build()
        val bound = provider.bindToLifecycle(owner, selector, group)
        camera = bound
        val info = Camera2CameraInfo.from(bound.cameraInfo)
        val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        nearestFocus = if (CaptureRequest.CONTROL_AF_MODE_OFF in afModes) {
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } else {
            0f
        }
        calibrated = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
            .let { it != null && it != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED }
        // A rebind starts a new session: carry the held focus, zoom and light over.
        manualFocus?.let(::holdFocus)
        if (zoomRatio != 1f) bound.cameraControl.setZoomRatio(zoomRatio)
        if (torch) bound.cameraControl.enableTorch(true)
    }

    fun stop() {
        provider?.unbind(preview, capture, analysis)
        camera = null
    }

    fun setAnalyzer(executor: Executor, analyzer: ImageAnalysis.Analyzer?) {
        if (analyzer == null) analysis.clearAnalyzer() else analysis.setAnalyzer(executor, analyzer)
    }

    fun takePicture(file: File, callback: ImageCapture.OnImageSavedCallback) {
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), callback)
    }

    fun enableTorch(on: Boolean) {
        torch = on
        camera?.cameraControl?.enableTorch(on)
    }

    /** Holds the lens at [diopters] (autofocus off) until [autoFocus]. */
    fun holdFocus(diopters: Float) {
        val camera = camera ?: return
        if (nearestFocus <= 0f) return
        val position = diopters.coerceIn(0f, nearestFocus)
        manualFocus = position
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, position)
                .build(),
        )
    }

    /** Back to continuous autofocus, forgetting the held lens position and any tapped focus point. */
    fun autoFocus() {
        manualFocus = null
        val camera = camera ?: return
        Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
        camera.cameraControl.cancelFocusAndMetering()
    }

    /**
     * Meters exposure on [point] and, unless focus is held, focuses there. [onFocused] gets
     * whether the lens found focus; it isn't called if a newer tap replaced this one.
     */
    fun focusOn(point: MeteringPoint, onFocused: (Boolean) -> Unit) {
        val camera = camera ?: return
        val flags = if (manualFocus == null) {
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        } else {
            FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        }
        val result = camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, flags).build())
        result.addListener({ runCatching { result.get().isFocusSuccessful }.onSuccess(onFocused) }, ContextCompat.getMainExecutor(context))
    }

    /** Pinch zoom: multiplies the zoom by [factor], within what the camera allows. */
    fun zoomBy(factor: Float) {
        val camera = camera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        // Tracked here rather than read back, since the camera reports new zoom levels a frame late.
        zoomRatio = (zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera.cameraControl.setZoomRatio(zoomRatio)
    }

    private companion object {
        val FOUR_THREE: AspectRatioStrategy = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        /** Original: the camera's best JPEG at its highest resolution. Standard: up to 12 MP, captured quickly. */
        fun imageCapture(quality: ScanQuality): ImageCapture {
            val original = quality == ScanQuality.Original
            return ImageCapture.Builder()
                .setCaptureMode(if (original) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(FOUR_THREE)
                        .setResolutionStrategy(
                            if (original) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                            else ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
                .build()
        }
    }
}
