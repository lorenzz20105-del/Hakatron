package dev.devstudio.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.devstudio.ide.ui.HakatronApp

/**
 * One activity, one composition.
 *
 * There is no second window, no dialog theme and no fragment stack: [HakatronApp]
 * decides what is on screen and composes exactly one route. That is what makes
 * "two UIs at once" structurally impossible rather than merely avoided.
 *
 * Nothing here talks to the Termux app — the userland lives inside this APK.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HakatronApp() }
    }
}
