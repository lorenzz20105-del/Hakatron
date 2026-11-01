package dev.devstudio.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.devstudio.ide.ui.HakatronApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // RUN_COMMAND is a runtime permission: declaring it is not enough.
        try {
            if (checkSelfPermission("com.termux.permission.RUN_COMMAND") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 4217)
            }
        } catch (_: Exception) {
        }
        setContent { HakatronApp() }
    }
}
