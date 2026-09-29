package dev.xerootg.gifmaker

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.xerootg.gifmaker.media.BitmapLoader
import dev.xerootg.gifmaker.media.FrameMetadata
import dev.xerootg.gifmaker.media.GifExporter
import dev.xerootg.gifmaker.model.CropRect
import dev.xerootg.gifmaker.model.ExportSettings
import dev.xerootg.gifmaker.model.ExportState
import dev.xerootg.gifmaker.model.FrameItem
import dev.xerootg.gifmaker.model.LoopMode
import dev.xerootg.gifmaker.model.PaletteMode
import dev.xerootg.gifmaker.model.ScaleMode
import dev.xerootg.gifmaker.model.SortOrder
import dev.xerootg.gifmaker.model.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class GifMakerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Small oriented thumbnails keyed by frame id, used by the frame strip and the live preview. */
    private val _previews = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val previews: StateFlow<Map<Long, ImageBitmap>> = _previews.asStateFlow()

    /** Larger decode of the reference frame for the crop editor. */
    private val _cropReference = MutableStateFlow<Pair<Long, ImageBitmap>?>(null)
    val cropReference: StateFlow<Pair<Long, ImageBitmap>?> = _cropReference.asStateFlow()

    private var nextId = 1L
    private var pickCounter = 0
    private var exportJob: Job? = null
    private var cropRefJob: Job? = null
    private val decodeGate = Semaphore(3)

    // ---- frames -------------------------------------------------------------------------------

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val known = _state.value.frames.map { it.uri }.toSet()
        val fresh = uris.filter { it !in known }.distinct()
        if (fresh.isEmpty()) {
            postMessage("Those photos are already in the list")
            return
        }
        val ctx = getApplication<Application>()
        _state.update { it.copy(loadingCount = it.loadingCount + fresh.size, export = ExportState.Idle) }
        for (uri in fresh) {
            val id = nextId++
            val pickIndex = pickCounter++
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // Photo Picker grants are not persistable; the transient grant is enough.
                }
                val item = decodeGate.withPermit { FrameMetadata.load(ctx, uri, id, pickIndex) }
                if (item == null) {
                    _state.update { it.copy(loadingCount = it.loadingCount - 1) }
                    postMessage("Skipped a file that could not be decoded")
                    return@launch
                }
                _state.update { st ->
                    withAspect(st.copy(frames = st.frames + item, loadingCount = st.loadingCount - 1))
                }
                loadPreview(item)
            }
        }
    }

    fun removeFrame(id: Long) {
        _state.update { st -> withAspect(st.copy(frames = st.frames.filterNot { it.id == id }, export = ExportState.Idle)) }
        _previews.update { it - id }
        if (_cropReference.value?.first == id) _cropReference.value = null
    }

    fun clearFrames() {
        exportJob?.cancel()
        _state.update { UiState(settings = it.settings) }
        _previews.value = emptyMap()
        _cropReference.value = null
    }

    fun setSortOrder(order: SortOrder) {
        _state.update { withAspect(it.copy(sortOrder = order, export = ExportState.Idle)) }
    }

    private fun loadPreview(item: FrameItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = decodeGate.withPermit {
                BitmapLoader.loadOriented(getApplication(), item, PREVIEW_MAX_DIM, Bitmap.Config.RGB_565)
            } ?: return@launch
            // The frame may have been removed while decoding.
            if (_state.value.frames.none { it.id == item.id }) {
                bmp.recycle()
                return@launch
            }
            _previews.update { it + (item.id to bmp.asImageBitmap()) }
        }
    }

    /** Ensures [cropReference] holds the current reference frame at editor resolution. */
    fun ensureCropReference() {
        val ref = _state.value.referenceFrame ?: return
        if (_cropReference.value?.first == ref.id) return
        cropRefJob?.cancel()
        cropRefJob = viewModelScope.launch(Dispatchers.IO) {
            val bmp = BitmapLoader.loadOriented(getApplication(), ref, CROP_EDITOR_MAX_DIM, Bitmap.Config.ARGB_8888)
                ?: return@launch
            _cropReference.value = ref.id to bmp.asImageBitmap()
        }
    }

    // ---- crop ---------------------------------------------------------------------------------

    fun setCrop(crop: CropRect) {
        _state.update { withAspect(it.copy(crop = crop.clamped(), export = ExportState.Idle)) }
    }

    /** Locks the editor to [aspect] (pixel w/h) and recentres the crop, or unlocks with null. */
    fun setCropAspect(aspect: Float?) {
        _state.update { st ->
            val ref = st.referenceFrame
            val crop = if (aspect != null && ref != null) CropRect.centered(ref.width, ref.height, aspect) else st.crop
            withAspect(st.copy(cropAspect = aspect, crop = crop, export = ExportState.Idle))
        }
    }

    fun resetCrop() {
        _state.update { withAspect(it.copy(crop = CropRect(), cropAspect = null, export = ExportState.Idle)) }
    }

    // ---- settings -----------------------------------------------------------------------------

    fun updateSettings(transform: (ExportSettings) -> ExportSettings) {
        _state.update { withAspect(it.copy(settings = transform(it.settings), export = ExportState.Idle)) }
    }

    fun setFps(fps: Float) = updateSettings { it.copy(fps = fps.coerceIn(1f, 50f)) }
    fun setLoopMode(mode: LoopMode) = updateSettings { it.copy(loopMode = mode) }
    fun setLoopCount(n: Int) = updateSettings { it.copy(loopCount = n.coerceIn(1, 65535)) }
    fun setBoomerang(on: Boolean) = updateSettings { it.copy(boomerang = on) }
    fun setFrameStep(step: Int) = updateSettings { it.copy(frameStep = step.coerceIn(1, 20)) }
    fun setScaleMode(mode: ScaleMode) = updateSettings { it.copy(scaleMode = mode) }
    fun setPaletteMode(mode: PaletteMode) = updateSettings { it.copy(paletteMode = mode) }
    fun setDither(on: Boolean) = updateSettings { it.copy(dither = on) }
    fun setMaxColors(n: Int) = updateSettings { it.copy(maxColors = n.coerceIn(2, 256)) }

    fun setOutputWidth(w: Int) = updateSettings { it.copy(outWidth = w) }
    fun setOutputHeight(h: Int) = updateSettings { it.copy(outHeight = h, lockAspect = false) }
    fun setLockAspect(on: Boolean) = updateSettings { it.copy(lockAspect = on) }

    /** With the aspect lock on, height follows width through the crop's pixel aspect. */
    private fun withAspect(st: UiState): UiState {
        if (!st.settings.lockAspect) return st
        val aspect = st.cropPixelAspect
        if (aspect <= 0f || aspect.isNaN()) return st
        val h = (st.settings.outWidth / aspect).roundToInt().coerceAtLeast(1)
        return if (h == st.settings.outHeight) st else st.copy(settings = st.settings.copy(outHeight = h))
    }

    // ---- export -------------------------------------------------------------------------------

    fun export() {
        val snapshot = _state.value
        val frames = snapshot.exportFrames
        if (frames.isEmpty() || snapshot.isExporting) return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _state.update { it.copy(export = ExportState.Running("Preparing", 0f)) }
            val dir = File(getApplication<Application>().cacheDir, "export")
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "gif_$stamp.gif")
            try {
                val result = withContext(Dispatchers.Default) {
                    GifExporter(getApplication()).export(frames, snapshot.crop, snapshot.settings, outFile) { stage, p ->
                        _state.update { it.copy(export = ExportState.Running(stage, p)) }
                    }
                }
                _state.update {
                    it.copy(export = ExportState.Done(result.file, result.bytes, result.frameCount, result.durationMs))
                }
            } catch (e: CancellationException) {
                outFile.delete()
                _state.update { it.copy(export = ExportState.Idle) }
                throw e
            } catch (e: Throwable) {
                outFile.delete()
                _state.update { it.copy(export = ExportState.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    /** Copies the finished GIF to a document URI chosen through the system file picker. */
    fun saveTo(target: Uri) {
        val done = _state.value.export as? ExportState.Done ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(target, "wt")?.use { out ->
                    done.file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Could not open destination")
                postMessage("Saved ${formatBytes(done.bytes)}")
            } catch (e: Exception) {
                postMessage("Save failed: ${e.message}")
            }
        }
    }

    fun postMessage(text: String) = _state.update { it.copy(message = text) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val PREVIEW_MAX_DIM = 360
        const val CROP_EDITOR_MAX_DIM = 1600

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
