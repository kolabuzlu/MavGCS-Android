package com.mavgcs.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The panel this ground station was drawn for, and the master every other
 * screen is a scaled copy of: a 12.7in tablet at 2944x1840 and 340dpi, which
 * is 1385.4117 x 865.8823 in density independent pixels.
 *
 * Measured on the device rather than worked out from the specification, so
 * that tablet lands exactly on these numbers and takes the untouched path.
 */
val DesignWidth: Dp = 1385.4117.dp
val DesignHeight: Dp = 865.8823.dp

/**
 * How far a screen may differ from the master before it is worth scaling.
 *
 * Half a dp is below anything visible, and the point of it is that a panel of
 * the intended size takes the untouched path -- no transform, no resampling,
 * nothing between the layout and the glass.
 */
private const val TOLERANCE_DP = 0.5f

/**
 * Lay the whole instrument panel out at the master size, then scale that to fit.
 *
 * Every other way of handling a second screen size rearranges something: a
 * column wraps, a panel slides under its neighbour, one instrument shrinks
 * while the one beside it does not. This cannot, because nothing inside ever
 * learns the screen changed. The layout is composed against the master's
 * dimensions and the finished result is scaled as a single object, the way a
 * photograph is scaled: every element keeps its size, position and proportion
 * relative to every other.
 *
 * The two axes scale independently, so a screen of a different shape stretches
 * rather than letterboxing. A deliberate trade -- filling the glass matters
 * more here than keeping circles perfectly round.
 *
 * A screen already the master size gets no transform at all.
 */
@Composable
fun FixedCanvas(
    modifier: Modifier = Modifier,
    designWidth: Dp = DesignWidth,
    designHeight: Dp = DesignHeight,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val needsScaling =
            abs(maxWidth.value - designWidth.value) > TOLERANCE_DP ||
                abs(maxHeight.value - designHeight.value) > TOLERANCE_DP
        if (!needsScaling) {
            // The panel it was drawn for. Hand the content straight through,
            // so that tablet does not differ by one pixel.
            content()
            return@BoxWithConstraints
        }

        val scaleX = maxWidth / designWidth
        val scaleY = maxHeight / designHeight
        Box(
            Modifier
                // Measure the content at the master size and put it in the
                // corner, while reporting to the parent that this fills the
                // screen.
                //
                // Done by hand rather than with requiredSize, because a child
                // larger than its parent gets centred on the way in: that put
                // a quarter of the panel off the top and left, and left the
                // opposite corner empty.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        Constraints.fixed(designWidth.roundToPx(), designHeight.roundToPx()),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(0, 0)
                    }
                }
                // Inside that, so it scales the drawing without changing the
                // measured size the placement above depends on.
                .graphicsLayer {
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                    // From the top left corner, so the result starts there
                    // rather than about its own middle.
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            content()
        }
    }
}
