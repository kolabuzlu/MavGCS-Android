package com.mavgcs.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The master: the 12.7in tablet this ground station was drawn for, exactly as
 * it reports itself.
 *
 * Read off the device rather than worked out from the specification, because
 * Android's own rounding is what the layout actually sees -- 1840 pixels at
 * 2.125 is 865.88dp, and the configuration says 866.
 */
const val MasterWidthPx = 2944
const val MasterHeightPx = 1840
private const val MasterDensity = 2.125f
private const val MasterDensityDpi = 340
private const val MasterWidthDp = 1385
private const val MasterHeightDp = 866

/**
 * Compose the panel as the master composes it, whatever screen it is on.
 *
 * The layout is a fixed composition and is meant to stay one. Anything that
 * adapts rearranges it: a column wraps, a panel slides under its neighbour, an
 * instrument shrinks while the one beside it does not. So nothing inside is
 * allowed to know it is anywhere else.
 *
 * Holding the size still is not enough on its own. A screen tells a layout
 * about itself three ways -- the space it is given, the density that turns dp
 * into pixels, and the configuration it can ask for directly -- and this panel
 * reads the third, sizing its columns from LocalConfiguration. Handed 1385dp
 * of room on a tablet whose configuration said 1007dp, it laid out for the
 * small number and drew a different picture. So the density and the
 * configuration are replaced here, and the size is fixed by the view this sits
 * in, which is given the master's pixel dimensions.
 *
 * The scaling itself is deliberately not done here. See MainActivity: it is an
 * ordinary view transform, so that Android puts touches through the inverse of
 * it and the map and the 3D view are told where they were really touched.
 */
@Composable
fun FixedCanvas(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val alreadyMaster = density.density == MasterDensity &&
        density.fontScale == 1f &&
        configuration.screenWidthDp == MasterWidthDp &&
        configuration.screenHeightDp == MasterHeightDp

    if (alreadyMaster) {
        // The panel it was drawn for. Nothing to substitute, and nothing
        // between the layout and the glass.
        content()
        return
    }

    val masterConfiguration = remember(configuration) {
        Configuration(configuration).apply {
            screenWidthDp = MasterWidthDp
            screenHeightDp = MasterHeightDp
            smallestScreenWidthDp = MasterHeightDp
            densityDpi = MasterDensityDpi
        }
    }
    CompositionLocalProvider(
        LocalDensity provides Density(MasterDensity, 1f),
        LocalConfiguration provides masterConfiguration,
    ) {
        content()
    }
}
