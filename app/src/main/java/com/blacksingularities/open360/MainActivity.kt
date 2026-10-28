package com.blacksingularities.open360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.blacksingularities.open360.data.Settings
import com.blacksingularities.open360.ui.CaptureScreen
import com.blacksingularities.open360.ui.HomeScreen
import com.blacksingularities.open360.ui.Open360Theme
import com.blacksingularities.open360.ui.ViewerScreen

/** Ekrany aplikacji. */
sealed interface Screen {
    data object Home : Screen
    data object Capture : Screen
    data class Viewer(val uri: Uri, val own: Boolean, val fromCapture: Boolean = false) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        val settings = Settings(this)
        handleIntent(intent)

        setContent {
            Open360Theme {
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        screen = Screen.Capture
                    } else {
                        Toast.makeText(this@MainActivity, "Bez dostępu do aparatu nie da się zrobić panoramy.", Toast.LENGTH_LONG).show()
                    }
                }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it::class },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(
                            settings,
                            refresh,
                            onCapture = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    screen = Screen.Capture
                                } else {
                                    permission.launch(Manifest.permission.CAMERA)
                                }
                            },
                            onOpen = { uri, own -> screen = Screen.Viewer(uri, own) },
                        )
                        Screen.Capture -> CaptureScreen(
                            settings,
                            onClose = { screen = Screen.Home },
                            onSaved = { uri ->
                                refresh++
                                screen = Screen.Viewer(uri, own = true, fromCapture = true)
                            },
                        )
                        is Screen.Viewer -> ViewerScreen(
                            settings, s.uri, s.own,
                            onClose = { screen = Screen.Home },
                            onDeleted = {
                                refresh++
                                screen = Screen.Home
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        screen = Screen.Viewer(uri, own = false)
    }
}
