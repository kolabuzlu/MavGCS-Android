package com.mavgcs.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.mavgcs.app.mavlink.VehicleState
import java.util.Locale

/** Where the page is served from, so it has a real origin rather than file://. */
private const val FPV_ORIGIN = "https://appassets.androidplatform.net"

/**
 * The world ahead, seen from the aircraft.
 *
 * CesiumJS in a WebView, with the camera flown from ATTITUDE. Cesium handles
 * what is genuinely hard — streaming level-of-detail terrain and imagery — so
 * the camera is all this has to drive.
 *
 * It replaces the artificial horizon and nothing else: every HUD overlay is
 * drawn over it by the caller, so the two views cannot drift apart.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FpvView(
    vehicle: VehicleState,
    token: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> createWebView(ctx, loader, token) },
        update = { web ->
            // Nothing to fly to until there is a fix. Cesium keeps the last
            // view rather than snapping to the middle of the ocean.
            val lat = vehicle.lat ?: return@AndroidView
            val lon = vehicle.lon ?: return@AndroidView
            if (lat == 0.0 && lon == 0.0) return@AndroidView
            // Both heights go over: the page prefers to measure up from
            // Cesium's own terrain and keeps AMSL only as the fallback for
            // ground it has not streamed yet. See cameraHeight() there.
            val amsl = vehicle.altMslM ?: vehicle.altRelM ?: 0f
            val agl = vehicle.altRelM ?: 0f
            val yaw = vehicle.headingDeg ?: vehicle.yawDeg ?: 0f
            web.evaluateJavascript(
                "setPose(%.7f,%.7f,%.1f,%.1f,%.2f,%.2f,%.2f);".format(
                    Locale.ROOT,
                    lat,
                    lon,
                    amsl,
                    agl,
                    yaw,
                    vehicle.pitchDeg ?: 0f,
                    vehicle.rollDeg ?: 0f,
                ),
                null,
            )
        },
        // Switching back to the drawn horizon takes this view away, and a
        // WebView that is merely dropped keeps its renderer process and its GL
        // context. Toggled a few times, that is a few of them.
        onRelease = { web ->
            web.loadUrl("about:blank")
            web.destroy()
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    loader: WebViewAssetLoader,
    token: String,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // Deliberately left alone: allowUniversalAccessFromFileURLs would be the
    // quick way to let the page fetch tiles, and it would also hand every page
    // this view ever loads the run of the filesystem. Serving the assets from
    // a real origin below means ordinary CORS applies instead, which is what
    // the tile servers already expect.
    setBackgroundColor(android.graphics.Color.BLACK)
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun onPageFinished(view: WebView, url: String) {
            if (token.isBlank()) {
                view.evaluateJavascript("needToken();", null)
            } else {
                // Quoted as a JSON string so nothing in the token can close
                // the literal early.
                view.evaluateJavascript(
                    "start(${org.json.JSONObject.quote(token)});",
                    null,
                )
            }
        }
    }
    loadUrl("$FPV_ORIGIN/assets/fpv.html")
}
