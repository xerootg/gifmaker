package dev.xerootg.gifmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xerootg.gifmaker.GifMakerViewModel
import dev.xerootg.gifmaker.model.CropRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class AspectPreset(val label: String, val value: Float?)

private val presets = listOf(
    AspectPreset("Free", null),
    AspectPreset("1:1", 1f),
    AspectPreset("4:3", 4f / 3f),
    AspectPreset("3:2", 3f / 2f),
    AspectPreset("16:9", 16f / 9f),
    AspectPreset("3:4", 3f / 4f),
    AspectPreset("2:3", 2f / 3f),
    AspectPreset("9:16", 9f / 16f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropEditorScreen(vm: GifMakerViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reference by vm.cropReference.collectAsStateWithLifecycle()
    val ref = state.referenceFrame

    LaunchedEffect(ref?.id) { vm.ensureCropReference() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = vm::resetCrop) { Icon(Icons.Default.Refresh, "Reset crop") }
                    IconButton(onClick = onDone) { Icon(Icons.Default.Check, "Done") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val originalAspect = ref?.let { it.width.toFloat() / it.height }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val allPresets = if (originalAspect != null) {
                    listOf(presets[0], AspectPreset("Original", originalAspect)) + presets.drop(1)
                } else {
                    presets
                }
                allPresets.forEach { preset ->
                    val selected = if (preset.value == null) state.cropAspect == null
                    else state.cropAspect?.let { abs(it - preset.value) < 1e-3f } == true
                    FilterChip(
                        selected = selected,
                        onClick = { vm.setCropAspect(preset.value) },
                        label = { Text(preset.label) },
                    )
                }
            }

            val image = reference?.takeIf { it.first == ref?.id }?.second
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                if (image == null || ref == null) {
                    CircularProgressIndicator()
                } else {
                    CropEditor(
                        image = image,
                        crop = state.crop,
                        aspect = state.cropAspect,
                        onCropChange = vm::setCrop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (ref != null) {
                val c = state.crop
                val px = (c.width * ref.width).roundToInt()
                val py = (c.height * ref.height).roundToInt()
                Text(
                    text = "Crop ${px}×${py} px of ${ref.width}×${ref.height} · offset " +
                        "${(c.left * ref.width).roundToInt()},${(c.top * ref.height).roundToInt()} · " +
                        "aspect ${"%.3f".format(state.cropPixelAspect)} · drag corners, edges or inside",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private enum class Handle { MOVE, TL, TR, BL, BR, L, R, T, B }

/**
 * Interactive crop rectangle over [image]. [crop] is normalised; [aspect] (pixel w/h) locks corner
 * resizing to a fixed ratio, in which case edge handles are disabled.
 */
@Composable
fun CropEditor(
    image: ImageBitmap,
    crop: CropRect,
    aspect: Float?,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentAspect by rememberUpdatedState(aspect)
    val density = LocalDensity.current
    val handleRadius = with(density) { 22.dp.toPx() }
    val minSize = with(density) { 24.dp.toPx() }

    BoxWithConstraints(modifier) {
        val canvasW = constraints.maxWidth.toFloat()
        val canvasH = constraints.maxHeight.toFloat()
        val scale = min(canvasW / image.width, canvasH / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val ox = (canvasW - dw) / 2f
        val oy = (canvasH - dh) / 2f
        val bounds = Rect(ox, oy, ox + dw, oy + dh)

        fun toCanvas(c: CropRect) = Rect(ox + c.left * dw, oy + c.top * dh, ox + c.right * dw, oy + c.bottom * dh)
        fun toNormalized(r: Rect) = CropRect((r.left - ox) / dw, (r.top - oy) / dh, (r.right - ox) / dw, (r.bottom - oy) / dh)

        var active by remember { mutableStateOf<Handle?>(null) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image, bounds) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            active = hitTest(pos, toCanvas(currentCrop), handleRadius, allowEdges = currentAspect == null)
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                        onDrag = { change, drag ->
                            val handle = active ?: return@detectDragGestures
                            change.consume()
                            val next = applyDrag(toCanvas(currentCrop), handle, drag, bounds, currentAspect, minSize)
                            onCropChange(toNormalized(next).clamped())
                        },
                    )
                },
        ) {
            drawImage(
                image = image,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            val r = toCanvas(crop)
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(bounds.left, bounds.top), Size(dw, r.top - bounds.top))
            drawRect(shade, Offset(bounds.left, r.bottom), Size(dw, bounds.bottom - r.bottom))
            drawRect(shade, Offset(bounds.left, r.top), Size(r.left - bounds.left, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(bounds.right - r.right, r.height))

            val line = Color.White.copy(alpha = 0.6f)
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f
                val y = r.top + r.height * i / 3f
                drawLine(line, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = 1f)
                drawLine(line, Offset(r.left, y), Offset(r.right, y), strokeWidth = 1f)
            }
            drawRect(Color.White, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(width = 2f))

            val cornerLen = min(handleRadius, min(r.width, r.height) / 3f)
            val cornerStroke = 6f
            val accent = Color(0xFFF2C14E)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(accent, Offset(x, y), Offset(x + dx * cornerLen, y), strokeWidth = cornerStroke)
                drawLine(accent, Offset(x, y), Offset(x, y + dy * cornerLen), strokeWidth = cornerStroke)
            }
            corner(r.left, r.top, 1f, 1f)
            corner(r.right, r.top, -1f, 1f)
            corner(r.left, r.bottom, 1f, -1f)
            corner(r.right, r.bottom, -1f, -1f)

            if (aspect == null) {
                val edgeLen = min(handleRadius, min(r.width, r.height) / 4f)
                val cx = r.left + r.width / 2f
                val cy = r.top + r.height / 2f
                drawLine(accent, Offset(cx - edgeLen / 2, r.top), Offset(cx + edgeLen / 2, r.top), strokeWidth = cornerStroke)
                drawLine(accent, Offset(cx - edgeLen / 2, r.bottom), Offset(cx + edgeLen / 2, r.bottom), strokeWidth = cornerStroke)
                drawLine(accent, Offset(r.left, cy - edgeLen / 2), Offset(r.left, cy + edgeLen / 2), strokeWidth = cornerStroke)
                drawLine(accent, Offset(r.right, cy - edgeLen / 2), Offset(r.right, cy + edgeLen / 2), strokeWidth = cornerStroke)
            }
        }
    }
}

private fun hitTest(p: Offset, r: Rect, radius: Float, allowEdges: Boolean): Handle? {
    val corners = listOf(
        Handle.TL to r.topLeft, Handle.TR to r.topRight,
        Handle.BL to r.bottomLeft, Handle.BR to r.bottomRight,
    )
    val nearest = corners.minByOrNull { (p - it.second).getDistance() }
    if (nearest != null && (p - nearest.second).getDistance() <= radius) return nearest.first
    if (allowEdges) {
        val withinX = p.x in (r.left - radius)..(r.right + radius)
        val withinY = p.y in (r.top - radius)..(r.bottom + radius)
        if (withinY && abs(p.x - r.left) <= radius) return Handle.L
        if (withinY && abs(p.x - r.right) <= radius) return Handle.R
        if (withinX && abs(p.y - r.top) <= radius) return Handle.T
        if (withinX && abs(p.y - r.bottom) <= radius) return Handle.B
    }
    return if (r.contains(p)) Handle.MOVE else null
}

private fun applyDrag(r: Rect, h: Handle, d: Offset, bounds: Rect, aspect: Float?, minSize: Float): Rect = when (h) {
    Handle.MOVE -> {
        val l = (r.left + d.x).coerceIn(bounds.left, max(bounds.left, bounds.right - r.width))
        val t = (r.top + d.y).coerceIn(bounds.top, max(bounds.top, bounds.bottom - r.height))
        Rect(l, t, l + r.width, t + r.height)
    }
    Handle.L -> Rect((r.left + d.x).coerceIn(bounds.left, r.right - minSize), r.top, r.right, r.bottom)
    Handle.R -> Rect(r.left, r.top, (r.right + d.x).coerceIn(r.left + minSize, bounds.right), r.bottom)
    Handle.T -> Rect(r.left, (r.top + d.y).coerceIn(bounds.top, r.bottom - minSize), r.right, r.bottom)
    Handle.B -> Rect(r.left, r.top, r.right, (r.bottom + d.y).coerceIn(r.top + minSize, bounds.bottom))
    Handle.TL, Handle.TR, Handle.BL, Handle.BR -> resizeCorner(r, h, d, bounds, aspect, minSize)
}

private fun resizeCorner(r: Rect, h: Handle, d: Offset, bounds: Rect, aspect: Float?, minSize: Float): Rect {
    val movesLeft = h == Handle.TL || h == Handle.BL
    val movesTop = h == Handle.TL || h == Handle.TR
    val ax = if (movesLeft) r.right else r.left
    val ay = if (movesTop) r.bottom else r.top
    val sx = if (movesLeft) -1f else 1f
    val sy = if (movesTop) -1f else 1f
    val mx = (if (movesLeft) r.left else r.right) + d.x
    val my = (if (movesTop) r.top else r.bottom) + d.y
    val availW = if (sx > 0) bounds.right - ax else ax - bounds.left
    val availH = if (sy > 0) bounds.bottom - ay else ay - bounds.top

    var w = ((mx - ax) * sx).coerceIn(min(minSize, availW), availW)
    var hh = ((my - ay) * sy).coerceIn(min(minSize, availH), availH)
    if (aspect != null && aspect > 0f) {
        w = max(w, hh * aspect)
        hh = w / aspect
        if (w > availW) { w = availW; hh = w / aspect }
        if (hh > availH) { hh = availH; w = hh * aspect }
    }
    val x2 = ax + w * sx
    val y2 = ay + hh * sy
    return Rect(min(ax, x2), min(ay, y2), max(ax, x2), max(ay, y2))
}
