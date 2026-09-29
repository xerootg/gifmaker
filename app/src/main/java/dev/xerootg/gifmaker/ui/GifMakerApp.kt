package dev.xerootg.gifmaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.xerootg.gifmaker.GifMakerViewModel

private enum class Screen { MAIN, CROP }

@Composable
fun GifMakerApp(vm: GifMakerViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    when (screen) {
        Screen.MAIN -> MainScreen(vm = vm, onEditCrop = { screen = Screen.CROP })
        Screen.CROP -> {
            BackHandler { screen = Screen.MAIN }
            CropEditorScreen(vm = vm, onDone = { screen = Screen.MAIN })
        }
    }
}
