package dev.xerootg.gifmaker.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xerootg.gifmaker.GifMakerViewModel
import dev.xerootg.gifmaker.model.ExportState
import dev.xerootg.gifmaker.model.LoopMode
import dev.xerootg.gifmaker.model.PaletteMode
import dev.xerootg.gifmaker.model.ScaleMode
import dev.xerootg.gifmaker.model.SortOrder
import dev.xerootg.gifmaker.model.UiState
import java.util.Locale
import kotlin.math.roundToInt

private val widthPresets = listOf(240, 320, 480, 640, 800, 1024)
private val colorPresets = listOf(16, 32, 64, 128, 256)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: GifMakerViewModel, onEditCrop: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.addUris(uris)
    }
    val pickDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addUris(uris)
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.consumeMessage()
    }

    val launchPhotoPicker = {
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/jpeg")))
    }
    val launchDocumentPicker = { pickDocuments.launch(arrayOf("image/jpeg")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GIF Maker") },
                actions = {
                    if (state.frames.isNotEmpty()) {
                        IconButton(onClick = vm::clearFrames) { Icon(Icons.Default.Delete, "Clear all frames") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.frames.isEmpty() && state.loadingCount == 0) {
                item { EmptyState(launchPhotoPicker, launchDocumentPicker) }
            } else {
                item { PreviewSection(state, previews) }
            }
            item { FramesSection(vm, state, previews, launchPhotoPicker, launchDocumentPicker) }
            if (state.frames.isNotEmpty()) {
                item { CropSection(vm, state, onEditCrop) }
                item { TimingSection(vm, state) }
                item { OutputSection(vm, state) }
                item { ExportSection(vm, state, onSave = { name -> saveDocument.launch(name) }) }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EmptyState(onPickPhotos: () -> Unit, onPickDocuments: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pick some JPEGs to get started", style = MaterialTheme.typography.titleLarge)
            Text(
                "They are sorted by capture date (EXIF), cropped and scaled with the settings you choose, and encoded into an animated GIF on-device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onPickPhotos, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Pick photos")
            }
            OutlinedButton(onClick = onPickDocuments, modifier = Modifier.fillMaxWidth()) {
                Text("Pick from files (no selection limit)")
            }
        }
    }
}

@Composable
private fun PreviewSection(state: UiState, previews: Map<Long, androidx.compose.ui.graphics.ImageBitmap>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LivePreview(
            frames = state.exportFrames,
            previews = previews,
            crop = state.crop,
            delayMs = state.settings.delayCs * 10L,
            scaleMode = state.settings.scaleMode,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(state.outputAspect.coerceIn(0.2f, 5f))
                .clip(RoundedCornerShape(12.dp)),
        )
        val n = state.exportFrames.size
        val seconds = n * state.settings.delayCs / 100f
        Text(
            "Live preview · $n frames · ${"%.1f".format(Locale.US, seconds)} s at ${"%.1f".format(Locale.US, state.settings.effectiveFps)} fps",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FramesSection(
    vm: GifMakerViewModel,
    state: UiState,
    previews: Map<Long, androidx.compose.ui.graphics.ImageBitmap>,
    onPickPhotos: () -> Unit,
    onPickDocuments: () -> Unit,
) {
    SectionCard("Frames (${state.frames.size})") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPickPhotos) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Photos")
            }
            OutlinedButton(onClick = onPickDocuments) { Text("Files") }
        }
        if (state.loadingCount > 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Reading ${state.loadingCount} file(s)…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.frames.isNotEmpty()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SortOrder.entries.forEachIndexed { i, order ->
                    SegmentedButton(
                        selected = state.sortOrder == order,
                        onClick = { vm.setSortOrder(order) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = SortOrder.entries.size),
                    ) { Text(order.label) }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.orderedFrames, key = { it.id }) { frame ->
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            previews[frame.id]?.let {
                                Image(bitmap = it, contentDescription = frame.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            IconButton(
                                onClick = { vm.removeFrame(frame.id) },
                                modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close, "Remove", tint = Color.White,
                                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(2.dp),
                                )
                            }
                        }
                        Text(frame.dateLabel, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${frame.dateSource.label} · ${frame.width}×${frame.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val undated = state.frames.count { it.dateMillis == null }
            if (undated > 0) {
                Text(
                    "$undated frame(s) have no date and are placed last in selection order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CropSection(vm: GifMakerViewModel, state: UiState, onEditCrop: () -> Unit) {
    SectionCard("Crop") {
        val ref = state.referenceFrame
        val c = state.crop
        val desc = if (ref == null) "—" else if (c.isFull) {
            "Full frame · ${ref.width}×${ref.height} px"
        } else {
            "${(c.width * ref.width).roundToInt()}×${(c.height * ref.height).roundToInt()} px " +
                "at ${(c.left * ref.width).roundToInt()},${(c.top * ref.height).roundToInt()}"
        }
        Text(desc, style = MaterialTheme.typography.bodyMedium)
        Text(
            "The crop is defined as fractions of the first frame and applied to every frame, so it also works when frames differ in size.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onEditCrop) {
                Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Edit crop")
            }
            if (!c.isFull) OutlinedButton(onClick = vm::resetCrop) { Text("Reset") }
        }
    }
}

@Composable
private fun TimingSection(vm: GifMakerViewModel, state: UiState) {
    val s = state.settings
    SectionCard("Timing") {
        Text(
            "Frame rate: ${"%.1f".format(Locale.US, s.effectiveFps)} fps (${s.delayCs * 10} ms per frame)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(value = s.fps, onValueChange = vm::setFps, valueRange = 1f..50f)
        Text(
            "GIF timing is in 10 ms steps, so the rate shown is what viewers will actually play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Use every ${ordinal(s.frameStep)} frame", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = s.frameStep.toFloat(),
            onValueChange = { vm.setFrameStep(it.roundToInt()) },
            valueRange = 1f..20f,
            steps = 18,
        )

        Text("Loop", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LoopMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = s.loopMode == mode,
                    onClick = { vm.setLoopMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = LoopMode.entries.size),
                ) { Text(mode.label) }
            }
        }
        if (s.loopMode == LoopMode.COUNT) {
            var text by remember { mutableStateOf(s.loopCount.toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() }.take(5)
                    text.toIntOrNull()?.let(vm::setLoopCount)
                },
                label = { Text("Repeat count") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ToggleRow("Boomerang (play forward, then backward)", s.boomerang, vm::setBoomerang)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputSection(vm: GifMakerViewModel, state: UiState) {
    val s = state.settings
    var widthText by remember { mutableStateOf(s.outWidth.toString()) }
    var heightText by remember { mutableStateOf(s.outHeight.toString()) }
    LaunchedEffect(s.outWidth) { if (widthText.toIntOrNull() != s.outWidth) widthText = s.outWidth.toString() }
    LaunchedEffect(s.outHeight) { if (heightText.toIntOrNull() != s.outHeight) heightText = s.outHeight.toString() }

    SectionCard("Output") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            widthPresets.forEach { w ->
                FilterChip(selected = s.outWidth == w, onClick = { vm.setOutputWidth(w) }, label = { Text("${w}w") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = widthText,
                onValueChange = { v ->
                    widthText = v.filter { it.isDigit() }.take(4)
                    widthText.toIntOrNull()?.let(vm::setOutputWidth)
                },
                label = { Text("Width") },
                suffix = { Text("px") },
                singleLine = true,
                isError = s.outWidth !in 8..4096,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Text("×")
            OutlinedTextField(
                value = heightText,
                onValueChange = { v ->
                    heightText = v.filter { it.isDigit() }.take(4)
                    heightText.toIntOrNull()?.let(vm::setOutputHeight)
                },
                label = { Text("Height") },
                suffix = { Text("px") },
                singleLine = true,
                isError = s.outHeight !in 8..4096,
                enabled = !s.lockAspect,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        ToggleRow("Lock height to crop aspect (${"%.3f".format(Locale.US, state.cropPixelAspect)})", s.lockAspect, vm::setLockAspect)
        if (!s.sizeValid) {
            Text("Width and height must be between 8 and 4096 px.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text("When the crop and output aspect differ", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScaleMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = s.scaleMode == mode,
                    onClick = { vm.setScaleMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ScaleMode.entries.size),
                ) { Text(mode.label) }
            }
        }
        Text(s.scaleMode.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Palette", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PaletteMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = s.paletteMode == mode,
                    onClick = { vm.setPaletteMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = PaletteMode.entries.size),
                ) { Text(mode.label) }
            }
        }
        Text(
            when (s.paletteMode) {
                PaletteMode.GLOBAL -> "One palette for the whole GIF: no colour flicker between frames, smaller file."
                PaletteMode.PER_FRAME -> "A palette per frame: best colours per frame, may shimmer, larger file."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colorPresets.forEach { n ->
                FilterChip(selected = s.maxColors == n, onClick = { vm.setMaxColors(n) }, label = { Text("$n colours") })
            }
        }
        ToggleRow("Dithering (Floyd–Steinberg)", s.dither, vm::setDither)
    }
}

@Composable
private fun ExportSection(vm: GifMakerViewModel, state: UiState, onSave: (String) -> Unit) {
    val context = LocalContext.current
    SectionCard("Export") {
        when (val e = state.export) {
            is ExportState.Running -> {
                Text(e.stage, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancelExport) { Text("Cancel") }
            }
            is ExportState.Done -> {
                GifResultView(
                    file = e.file,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(state.outputAspect.coerceIn(0.2f, 5f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                )
                Text(
                    "${GifMakerViewModel.formatBytes(e.bytes)} · ${e.frameCount} frames · ${"%.1f".format(Locale.US, e.durationMs / 1000f)} s · " +
                        "${state.settings.outWidth}×${state.settings.outHeight}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(e.file.name) }) { Text("Save…") }
                    OutlinedButton(onClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", e.file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/gif"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share GIF"))
                    }) {
                        Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Share")
                    }
                    OutlinedButton(onClick = vm::export) { Text("Re-encode") }
                }
            }
            is ExportState.Failed -> {
                Text("Export failed: ${e.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::export, enabled = state.settings.sizeValid) { Text("Try again") }
            }
            ExportState.Idle -> {
                val n = state.exportFrames.size
                Text(
                    "$n frame(s) → ${state.settings.outWidth}×${state.settings.outHeight} GIF",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = vm::export,
                    enabled = n > 0 && state.loadingCount == 0 && state.settings.sizeValid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create GIF") }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun ordinal(n: Int): String = when {
    n == 1 -> "1st"
    n % 100 in 11..13 -> "${n}th"
    n % 10 == 1 -> "${n}st"
    n % 10 == 2 -> "${n}nd"
    n % 10 == 3 -> "${n}rd"
    else -> "${n}th"
}
