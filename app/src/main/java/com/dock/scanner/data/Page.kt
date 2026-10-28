package com.dock.scanner.data

import com.dock.scanner.core.PageFilter
import com.dock.scanner.core.Point2
import com.dock.scanner.core.Quad
import java.io.File

data class Page(
    val id: String,
    /** Orders pages by when they were added, even if their processing finishes out of order. */
    val sequence: Long,
    /** Original photo. May carry an EXIF orientation; [sourceWidth]/[sourceHeight] are upright. */
    val source: File,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val quad: Quad,
    /** What edge detection found, so the user can go back to it after manual edits. */
    val detectedQuad: Quad?,
    val filter: PageFilter,
    /** Clockwise, applied after cropping: 0, 90, 180 or 270. */
    val rotation: Int,
    /** -1..1, 0 = unchanged. Applied after the filter, so they're cheap to tweak. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    /** Eraser and pen strokes, drawn on top in order. */
    val strokes: List<InkStroke> = emptyList(),
    /** Cropped, straightened, filtered and rotated image, before adjustments and strokes. */
    val base: File? = null,
    /** The settings [base] was rendered from; when they change, the base must be re-rendered. */
    val baseKey: String? = null,
    /** Finished page image (base + adjustments + strokes); null until the first render completes. */
    val output: File? = null,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    /** Size in points of the PDF page this came from (unrotated); keeps edited PDFs at their paper size. */
    val pdfPageSize: Pair<Float, Float>? = null,
    /** Paper sheet this page is laid out on, or null to export the image as its own page size. */
    val layout: SheetLayout? = null,
    /** Set when this page is placed on another page's sheet instead of being a PDF page itself. */
    val hostId: String? = null,
    val processing: Boolean = true,
    val revision: Int = 0,
    /** Resolution and compression of the finished page. */
    val quality: ScanQuality = ScanQuality.Standard,
) {
    val renderKey: String get() = "${source.name}|$quad|$filter|$rotation|$quality"

    val aspect: Float get() = if (outputWidth > 0 && outputHeight > 0) outputWidth.toFloat() / outputHeight else 1 / 1.414f
}

enum class StrokeKind { ERASER, PEN }

/**
 * A freehand stroke in normalized page coordinates. [width] is a fraction of the page width,
 * so strokes keep their look at any render resolution. Opacity lives in [color]'s alpha.
 */
data class InkStroke(val kind: StrokeKind, val points: List<Point2>, val color: Int, val width: Float)

enum class Paper(val label: String, val shortSidePt: Float, val longSidePt: Float) {
    A4("A4", 595.28f, 841.89f),
    A3("A3", 841.89f, 1190.55f),
    A5("A5", 419.53f, 595.28f),
    LETTER("Letter", 612f, 792f),
    LEGAL("Legal", 612f, 1008f),
}

/** Where a page image sits on a sheet: center and width as fractions of the sheet. */
data class Placement(val pageId: String, val centerX: Float, val centerY: Float, val width: Float)

data class SheetLayout(val paper: Paper, val landscape: Boolean, val items: List<Placement>) {
    val widthPt: Float get() = if (landscape) paper.longSidePt else paper.shortSidePt
    val heightPt: Float get() = if (landscape) paper.shortSidePt else paper.longSidePt
    val aspect: Float get() = widthPt / heightPt

    fun placementOf(pageId: String) = items.firstOrNull { it.pageId == pageId }

    /** Replaces the placement for the same page, or adds it on top. */
    fun with(placement: Placement): SheetLayout = copy(
        items = if (items.any { it.pageId == placement.pageId }) {
            items.map { if (it.pageId == placement.pageId) placement else it }
        } else {
            items + placement
        },
    )

    fun without(pageId: String): SheetLayout = copy(items = items.filterNot { it.pageId == pageId })

    /** Keeps a page of [imageAspect] entirely on the sheet: limits its size, then its position. */
    fun clamp(placement: Placement, imageAspect: Float): Placement {
        val width = placement.width.coerceIn(0.05f, minOf(1f, imageAspect / aspect))
        val halfW = width / 2
        val halfH = width * aspect / imageAspect / 2
        return placement.copy(
            width = width,
            centerX = placement.centerX.coerceIn(halfW, 1f - halfW),
            centerY = placement.centerY.coerceIn(halfH, 1f - halfH),
        )
    }

    /** Clamps every placement again after pages changed shape; [aspectOf] is null for a page whose shape isn't known yet. */
    fun clampAll(aspectOf: (String) -> Float?): SheetLayout =
        copy(items = items.map { p -> aspectOf(p.pageId)?.let { clamp(p, it) } ?: p })

    companion object {
        /** Largest width fraction at which an image of [imageAspect] fits the sheet with a margin. */
        fun fitWidth(imageAspect: Float, sheetAspect: Float, margin: Float = 0.92f): Float =
            minOf(margin, margin * imageAspect / sheetAspect)
    }
}
