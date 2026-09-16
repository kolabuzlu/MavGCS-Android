package com.mavgcs.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.util.Locale

/**
 * A place on the ground, as a link a phone will navigate to.
 *
 * The directions form rather than a plain pin: the point of walking out to a
 * landed aircraft is being taken to it, and `dir` with a destination is the
 * documented Google Maps URL that starts that on both Android and iOS. Six
 * decimals is a tenth of a metre, which is finer than the aircraft's own fix
 * and matches what the box on the map is showing.
 */
internal fun mapsDirectionsUrl(lat: Double, lon: Double): String =
    "https://www.google.com/maps/dir/?api=1&destination=" +
        "%.6f,%.6f".format(Locale.ROOT, lat, lon)

/**
 * A QR code drawn one pixel per module, to be scaled up without smoothing.
 *
 * Built at its natural size rather than asked for in pixels, so every module
 * is exactly one pixel and no rounding can smear the edges. The quiet zone is
 * added here: the format requires four clear modules around the code, and a
 * scanner will refuse one that runs to the edge of its background.
 */
internal fun qrCode(text: String, quiet: Int = 4): ImageBitmap? = runCatching {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix ?: return null
    val side = matrix.width + quiet * 2
    val pixels = IntArray(side * side) { android.graphics.Color.WHITE }
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix.get(x, y).toInt() == 1) {
                pixels[(y + quiet) * side + (x + quiet)] = android.graphics.Color.BLACK
            }
        }
    }
    Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/**
 * The aircraft's position, offered to a phone.
 *
 * The coordinates are the ones that were on the map when the box was tapped,
 * not the ones arriving now. A code that kept up with the telemetry would be
 * a different place by the time it was scanned, and the aircraft this is for
 * is one that has already come down.
 *
 * Drawn black on white whatever the app's theme is doing. A code in the
 * panel's own dark colours is the one thing here that cannot be allowed to
 * look right and fail to scan.
 */
@Composable
internal fun CoordinateQrDialog(lat: Double, lon: Double, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val url = remember(lat, lon) { mapsDirectionsUrl(lat, lon) }
    val code = remember(url) { qrCode(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Go to the aircraft") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "%.6f, %.6f".format(Locale.ROOT, lat, lon),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = scheme.onSurface,
                )
                if (code == null) {
                    Text(
                        text = "The code could not be made for this position.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    Image(
                        bitmap = code,
                        contentDescription = "Scan to open these coordinates in Google Maps",
                        // Nearest neighbour, or the scaling blurs the modules
                        // into each other and a scanner gives up on it.
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .padding(6.dp)
                            .size(QrSize),
                    )
                }
                Text(
                    text = "Scan with a phone to open Google Maps.",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Big enough to scan across a table, small enough to leave the map visible. */
private val QrSize = 240.dp
