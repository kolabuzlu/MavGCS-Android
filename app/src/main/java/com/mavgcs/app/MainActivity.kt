package com.mavgcs.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mavgcs.app.ui.FixedCanvas
import com.mavgcs.app.ui.GcsScreen
import com.mavgcs.app.ui.SplashLogo
import com.mavgcs.app.ui.theme.MavGcsTheme
import kotlinx.coroutines.delay

/** How long the logo stays up before the GCS takes over. */
private const val SPLASH_DURATION_MS = 1600L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Full immersive: a ground station wants the whole panel, and on a
            // short tablet the status bar would otherwise sit over the arm row.
            // Both bars stay swipe-reachable via the transient behaviour below.
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MavGcsTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(SPLASH_DURATION_MS)
                    showSplash = false
                }
                Crossfade(targetState = showSplash, label = "splash") { splash ->
                    if (splash) {
                        SplashLogo()
                    } else {
                        // Composed at the size it was designed for and scaled
                        // to whatever panel it lands on, as one object. A
                        // tablet of the intended size gets no transform.
                        FixedCanvas { GcsScreen() }
                    }
                }
            }
        }
    }
}
