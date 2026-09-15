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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
 * Draw the panel as the master draws it, then scale that picture to the screen.
 *
 * The layout is a fixed composition and is meant to stay one. Anything that
 * adapts rearranges it: a column wraps, a panel slides under its neighbour,
 * an instrument shrinks while the one beside it does not. So nothing inside is
 * allowed to know it is anywhere else.
 *
 * Constraining the size is not enough on its own. The screen tells a layout
 * about itself in three ways -- the space it is given, the density that turns
 * dp into pixels, and the configuration it can ask for directly -- and this
 * panel reads the third. Given 1385dp of room on a screen the configuration
 * called 1007dp wide, it sized its columns for the small number and drew a
 * different picture, which was then scaled down again. So all three are
 * replaced here: the content is measured at the master's pixel size, with the
 * master's density, and told the master's configuration. What it composes is
 * then identical to what the master composes, down to the rounding.
 *
 * One scale for both axes, never two, so the proportions are the master's
 * proportions. A screen of a different shape gets bars rather than a stretch.
 * The artificial horizon is reason enough on its own: an attitude read off a
 * squashed one is wrong by however much it was squashed.
 *
 * The master itself takes a path with no transform on it at all.
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

        val configuration = LocalConfiguration.current
        val masterConfiguration = remember(configuration) {
            Configuration(configuration).apply {
                screenWidthDp = MasterWidthDp
                screenHeightDp = MasterHeightDp
                smallestScreenWidthDp = min(MasterWidthDp, MasterHeightDp)
                densityDpi = MasterDensityDpi
            }
        }

        Box(
            Modifier
                // Measure at the master's pixel size and centre what comes
                // back, while telling the parent this fills the screen.
                //
                // By hand rather than with requiredSize, because a child
                // larger than its parent is centred on the way in -- which put
                // a quarter of the panel off the top and left and left the
                // opposite corner empty.
                .layout { measurable, incoming ->
                    val placeable = measurable.measure(
                        Constraints.fixed(MasterWidthPx, MasterHeightPx),
                    )
                    val left = ((incoming.maxWidth - MasterWidthPx * scale) / 2f).roundToInt()
                    val top = ((incoming.maxHeight - MasterHeightPx * scale) / 2f).roundToInt()
                    layout(incoming.maxWidth, incoming.maxHeight) {
                        placeable.place(left, top)
                    }
                }
                // Inside the placement, so it scales the drawing without
                // changing the measured size that placement depends on.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // From the top left, so the picture grows from where it
                    // was put rather than about its own middle.
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(MasterDensity, 1f),
                LocalConfiguration provides masterConfiguration,
            ) {
                content()
            }
        }
    }
}
