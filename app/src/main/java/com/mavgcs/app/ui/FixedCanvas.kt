package com.mavgcs.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The master: the 12.7in tablet this ground station was drawn for, exactly as
 * it reports itself.
 *
 * Read off the device rather than worked out from the specification, because
 * Android's own rounding is what the layout actually sees -- 1840 pixels at
 * 2.125 is 865.88dp, and the configuration says 866.
 */
private const val MasterWidthPx = 2944
private const val MasterHeightPx = 1840
private const val MasterDensity = 2.125f
private const val MasterDensityDpi = 340
private const val MasterWidthDp = 1385
private const val MasterHeightDp = 866

/**
 * Draw the master's picture at whatever size the screen can hold it.
 *
 * The layout is a fixed composition and is meant to stay one. Anything that
 * adapts rearranges it: a column wraps, a panel slides under its neighbour, an
 * instrument shrinks while the one beside it does not. So nothing inside is
 * allowed to know it is anywhere else. It is measured at the master's
 * proportions and told the master's configuration, and composes exactly what
 * the master composes.
 *
 * The size comes from the density rather than from a transform, and that
 * distinction is the whole of this file.
 *
 * A graphicsLayer scale is the obvious way to shrink a finished picture, and
 * it works for everything Compose draws itself. It does not work for a view
 * borrowed from the platform, and this panel has two of them: the map and the
 * 3D view. Compose delivers a touch to such a view by moving the event by the
 * view's position on screen -- a translation, with no scale in it -- so on a
 * scaled panel the map was told a point much nearer its top left corner than
 * the finger actually was. Tapping to fly somewhere dropped the waypoint a
 * couple of hundred pixels up and to the left, by an amount that grew with the
 * distance from the corner.
 *
 * Changing the density instead means there is no transform to miss. Every
 * element is laid out and drawn at real screen pixels, so a borrowed view gets
 * its own coordinates and the text is rasterised at the size it is shown
 * rather than resampled from a larger one.
 *
 * The cost is that a density is one number, so both axes must share it. A
 * screen of a different shape keeps the master's proportions and takes a bar
 * at two edges instead of a stretch. That is not a preference -- it is the
 * price of the map knowing where it was touched.
 *
 * The master itself takes a path that changes nothing at all.
 */
@Composable
fun FixedCanvas(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val screenWidth = constraints.maxWidth
        val screenHeight = constraints.maxHeight
        val isMaster = screenWidth == MasterWidthPx &&
            screenHeight == MasterHeightPx &&
            LocalDensity.current.density == MasterDensity

        if (isMaster) {
            // The panel it was drawn for. Straight through, so that tablet
            // does not differ by a single pixel.
            content()
            return@BoxWithConstraints
        }

        val scale = min(
            screenWidth.toFloat() / MasterWidthPx,
            screenHeight.toFloat() / MasterHeightPx,
        )
        // The same composition, drawn smaller: at this density the master's
        // 1385.4 x 865.9dp comes out exactly the size below.
        val density = MasterDensity * scale
        val width = (MasterWidthPx * scale).roundToInt()
        val height = (MasterHeightPx * scale).roundToInt()

        val configuration = LocalConfiguration.current
        val masterConfiguration = remember(configuration) {
            Configuration(configuration).apply {
                screenWidthDp = MasterWidthDp
                screenHeightDp = MasterHeightDp
                smallestScreenWidthDp = MasterHeightDp
                densityDpi = MasterDensityDpi
            }
        }

        Box(
            Modifier.layout { measurable, incoming ->
                val placeable = measurable.measure(Constraints.fixed(width, height))
                val left = ((incoming.maxWidth - width) / 2f).roundToInt()
                val top = ((incoming.maxHeight - height) / 2f).roundToInt()
                layout(incoming.maxWidth, incoming.maxHeight) {
                    placeable.place(left, top)
                }
            },
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(density, 1f),
                LocalConfiguration provides masterConfiguration,
            ) {
                content()
            }
        }
    }
}
