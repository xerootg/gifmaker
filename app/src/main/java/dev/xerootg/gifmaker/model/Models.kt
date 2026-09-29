package dev.xerootg.gifmaker.model

import android.net.Uri
import java.io.File
import kotlin.math.roundToInt

enum class DateSource(val label: String) {
    EXIF("EXIF"),
    MEDIA_STORE("gallery"),
    FILENAME("filename"),
    MODIFIED("modified"),
    NONE("no date"),
}

/** One input photo plus everything needed to order and decode it without re-reading the file. */
data class FrameItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateMillis: Long?,
    val dateLabel: String,
    val dateSource: DateSource,
    /** Pixel dimensions of the stored (un-rotated) JPEG. */
    val rawWidth: Int,
    val rawHeight: Int,
    /** EXIF orientation decomposed as "flip horizontally, then rotate clockwise". */
    val rotation: Int,
    val flipped: Boolean,
    /** Position in the user's selection, used as the tie-breaker / fallback order. */
    val pickIndex: Int,
) {
    private val swapped get() = rotation == 90 || rotation == 270

    /** Dimensions as displayed (after orientation). */
    val width: Int get() = if (swapped) rawHeight else rawWidth
    val height: Int get() = if (swapped) rawWidth else rawHeight
}

/** Crop rectangle in normalised [0,1] coordinates of the oriented image, applied to every frame. */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** Aspect ratio (w/h) of this crop in pixels of a frame of the given oriented size. */
    fun pixelAspect(frameW: Int, frameH: Int): Float = (width * frameW) / (height * frameH)

    fun clamped(): CropRect = CropRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    companion object {
        /** Largest rectangle of the given pixel aspect (w/h) centred in a frame of the given size. */
        fun centered(frameW: Int, frameH: Int, aspect: Float): CropRect {
            val frameAspect = frameW.toFloat() / frameH
            return if (frameAspect > aspect) {
                val w = (frameH * aspect) / frameW
                CropRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
            } else {
                val h = (frameW / aspect) / frameH
                CropRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
            }
        }
    }
}

enum class ScaleMode(val label: String, val help: String) {
    FILL("Fill", "cover the output, trimming overflow"),
    FIT("Fit", "letterbox on black"),
    STRETCH("Stretch", "ignore aspect ratio"),
}

enum class PaletteMode(val label: String) {
    GLOBAL("Global"),
    PER_FRAME("Per frame"),
}

enum class SortOrder(val label: String) {
    OLDEST_FIRST("Oldest first"),
    NEWEST_FIRST("Newest first"),
}

enum class LoopMode(val label: String) {
    FOREVER("Forever"),
    ONCE("Once"),
    COUNT("Count"),
}

data class ExportSettings(
    val fps: Float = 10f,
    val loopMode: LoopMode = LoopMode.FOREVER,
    val loopCount: Int = 3,
    val boomerang: Boolean = false,
    /** Use every Nth frame of the sorted sequence. */
    val frameStep: Int = 1,
    val outWidth: Int = 640,
    val outHeight: Int = 480,
    /** Keep output height derived from the crop's pixel aspect. */
    val lockAspect: Boolean = true,
    val scaleMode: ScaleMode = ScaleMode.FILL,
    val paletteMode: PaletteMode = PaletteMode.GLOBAL,
    val dither: Boolean = true,
    val maxColors: Int = 256,
) {
    /** GIF delays are in centiseconds, so the requested fps is quantised. */
    val delayCs: Int get() = (100f / fps).roundToInt().coerceIn(1, 65535)
    val effectiveFps: Float get() = 100f / delayCs
    val sizeValid: Boolean get() = outWidth in MIN_DIM..MAX_DIM && outHeight in MIN_DIM..MAX_DIM

    companion object {
        const val MIN_DIM = 8
        const val MAX_DIM = 4096
    }
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val stage: String, val progress: Float) : ExportState
    data class Done(val file: File, val bytes: Long, val frameCount: Int, val durationMs: Long) : ExportState
    data class Failed(val message: String) : ExportState
}

data class UiState(
    val frames: List<FrameItem> = emptyList(),
    val loadingCount: Int = 0,
    val sortOrder: SortOrder = SortOrder.OLDEST_FIRST,
    val crop: CropRect = CropRect(),
    /** Aspect lock used by the crop editor (pixel w/h), null = free. */
    val cropAspect: Float? = null,
    val settings: ExportSettings = ExportSettings(),
    val export: ExportState = ExportState.Idle,
    val message: String? = null,
) {
    /** Frames sorted by date; frames with no date keep selection order and go last. */
    val orderedFrames: List<FrameItem> by lazy {
        val sorted = frames.sortedWith(
            compareBy<FrameItem, Long?>(nullsLast<Long>()) { it.dateMillis }
                .thenBy { it.name }
                .thenBy { it.pickIndex },
        )
        val (dated, undated) = sorted.partition { it.dateMillis != null }
        when (sortOrder) {
            SortOrder.OLDEST_FIRST -> dated + undated
            SortOrder.NEWEST_FIRST -> dated.reversed() + undated
        }
    }

    /** The frame the crop editor shows and whose dimensions define the crop's pixel aspect. */
    val referenceFrame: FrameItem? get() = orderedFrames.firstOrNull()

    /** The exact frame sequence that will be encoded (step + boomerang applied). */
    val exportFrames: List<FrameItem> by lazy {
        val step = settings.frameStep.coerceAtLeast(1)
        val stepped = orderedFrames.filterIndexed { i, _ -> i % step == 0 }
        if (settings.boomerang && stepped.size > 2) {
            stepped + stepped.subList(1, stepped.size - 1).asReversed()
        } else {
            stepped
        }
    }

    val cropPixelAspect: Float
        get() = referenceFrame?.let { crop.pixelAspect(it.width, it.height) } ?: (4f / 3f)

    val outputAspect: Float
        get() = if (settings.sizeValid) settings.outWidth.toFloat() / settings.outHeight else cropPixelAspect

    val isExporting: Boolean get() = export is ExportState.Running
}
