package dev.xerootg.gifmaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.xerootg.gifmaker.model.CropRect
import dev.xerootg.gifmaker.model.FrameItem
import dev.xerootg.gifmaker.model.ScaleMode
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Plays the frame sequence from the preview thumbnails at the chosen rate, applying the crop and
 * scale mode the same way the encoder will. The box should already have the output aspect ratio.
 */
@Composable
fun LivePreview(
    frames: List<FrameItem>,
    previews: Map<Long, ImageBitmap>,
    crop: CropRect,
    delayMs: Long,
    scaleMode: ScaleMode,
    modifier: Modifier = Modifier,
) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames.size, delayMs) {
        index = 0
        if (frames.size <= 1) return@LaunchedEffect
        while (true) {
            delay(delayMs.coerceAtLeast(16))
            index = (index + 1) % frames.size
        }
    }
    val frame = frames.getOrNull(index.coerceIn(0, max(frames.size - 1, 0)))
    val image = frame?.let { previews[it.id] }

    Box(modifier.background(Color.Black).clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            if (image == null) return@Canvas
            val iw = image.width
            val ih = image.height
            val srcLeft = (crop.left * iw).roundToInt().coerceIn(0, iw - 1)
            val srcTop = (crop.top * ih).roundToInt().coerceIn(0, ih - 1)
            val srcW = ((crop.right * iw).roundToInt() - srcLeft).coerceIn(1, iw - srcLeft)
            val srcH = ((crop.bottom * ih).roundToInt() - srcTop).coerceIn(1, ih - srcTop)

            val sx = size.width / srcW
            val sy = size.height / srcH
            val (scaleX, scaleY) = when (scaleMode) {
                ScaleMode.FILL -> max(sx, sy).let { it to it }
                ScaleMode.FIT -> min(sx, sy).let { it to it }
                ScaleMode.STRETCH -> sx to sy
            }
            val dstW = (srcW * scaleX).roundToInt().coerceAtLeast(1)
            val dstH = (srcH * scaleY).roundToInt().coerceAtLeast(1)
            drawImage(
                image = image,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset(((size.width - dstW) / 2f).roundToInt(), ((size.height - dstH) / 2f).roundToInt()),
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.Medium,
            )
        }
        if (frames.isNotEmpty()) {
            Text(
                text = "${index + 1} / ${frames.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
