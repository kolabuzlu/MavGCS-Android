package com.mavgcs.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mavgcs.app.ui.FixedCanvas
import com.mavgcs.app.ui.GcsScreen
import com.mavgcs.app.ui.MasterHeightPx
import com.mavgcs.app.ui.MasterWidthPx
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

        // The panel is built at the master's size and the view holding it is
        // scaled to the screen. Scaling here rather than inside the layout is
        // the whole point: this is an ordinary view transform, and Android
        // puts a touch through the inverse of it before handing the event
        // down. Compose's own graphicsLayer does not do that for the views it
        // borrows from the platform -- the map and the 3D view -- so scaling
        // there left the map believing every tap was nearer its top left
        // corner than the finger really was.
        val panel = ComposeView(this).apply {
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
                            // Composes against the master's density and
                            // configuration, so what it builds here is what
                            // the master builds.
                            FixedCanvas { GcsScreen() }
                        }
                    }
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(panel, FrameLayout.LayoutParams(MasterWidthPx, MasterHeightPx))
        setContentView(root)

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                // From the corner, so the scaled panel starts there rather
                // than spreading about its own middle.
                panel.pivotX = 0f
                panel.pivotY = 0f
                panel.scaleX = width.toFloat() / MasterWidthPx
                panel.scaleY = height.toFloat() / MasterHeightPx
            }
        }
        root.visibility = View.VISIBLE
    }
}
