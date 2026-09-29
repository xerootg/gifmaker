package dev.xerootg.gifmaker.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Plays the encoded GIF through the platform decoder so what you see is what a viewer gets. */
@Composable
fun GifResultView(file: File, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Playback preview needs Android 9+; the file is still saved fine.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var drawable by remember(file) { mutableStateOf<Drawable?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        try {
            drawable = withContext(Dispatchers.IO) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            }
        } catch (e: Exception) {
            error = e.message ?: "decode failed"
        }
    }
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            start()
        }
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            error != null -> Text("Preview failed: $error", style = MaterialTheme.typography.bodySmall)
            drawable == null -> CircularProgressIndicator()
            else -> AndroidView(
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
