package com.mavgcs.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavgcs.app.mavlink.HealthState
import com.mavgcs.app.mavlink.SystemHealth
import com.mavgcs.app.mavlink.VehicleState

private val CellBorder = Color(0xFF3A3D42)

/**
 * Colours per state, the same ones the EKF and vibration flags use elsewhere,
 * so a red here means what a red there means.
 */
private data class CellColours(val text: Color, val background: Color)

private fun coloursFor(state: HealthState): CellColours = when (state) {
    // Not reported present at all: nothing fitted, or this autopilot does not
    // say. Dim, because it is not a fault.
    HealthState.ABSENT -> CellColours(Color(0xFF5C6066), Color(0xFF1C1E21))
    // Fitted but switched off. Cool rather than warm on purpose -- amber is
    // reserved for something actually going wrong, and a disabled airspeed
    // sensor is a decision, not a warning.
    HealthState.OFF -> CellColours(Color(0xFF7D8EA0), Color(0xFF1A1F24))
    HealthState.OK -> CellColours(Color(0xFF5CCF5C), Color(0xFF172117))
    HealthState.WARN -> CellColours(Color(0xFFD8A23A), Color(0xFF241F16))
    HealthState.FAILED -> CellColours(Color(0xFFFF5555), Color(0xFF2A1616))
}

/**
 * Every subsystem the autopilot reports on, side by side.
 *
 * Sits under the messages log, and takes only the height its labels need:
 * every row it takes is one that log does not get.
 */
@Composable
fun SystemsPanel(
    vehicle: VehicleState,
    fontSize: TextUnit = 10.sp,
    modifier: Modifier = Modifier,
) {
    val cells = SystemHealth.cells(vehicle)
    GroupBox(title = "Systems", modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            cells.forEach { cell ->
                val colours = coloursFor(cell.state)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colours.background)
                        .border(1.dp, CellBorder, RoundedCornerShape(3.dp))
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cell.label,
                        color = colours.text,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
