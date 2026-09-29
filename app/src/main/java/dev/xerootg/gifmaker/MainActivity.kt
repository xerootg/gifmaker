package dev.xerootg.gifmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.xerootg.gifmaker.ui.GifMakerApp
import dev.xerootg.gifmaker.ui.theme.GifMakerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GifMakerTheme {
                GifMakerApp()
            }
        }
    }
}
