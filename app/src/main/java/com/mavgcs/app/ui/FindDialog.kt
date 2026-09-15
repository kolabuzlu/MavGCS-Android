package com.mavgcs.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.net.LinkScanner

/**
 * What the network has to offer, so an address need not be typed.
 *
 * The scan starts the moment this opens: the pilot pressed Find, which is the
 * whole instruction, and a dialog with a second button to begin would just be
 * a step in the way.
 */
@Composable
fun FindDialog(
    onDismiss: () -> Unit,
    onPick: (LinkScanner.Found) -> Unit,
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var stage by remember { mutableStateOf("Starting") }
    var results by remember { mutableStateOf(emptyList<LinkScanner.Found>()) }

    // Tied to the dialog: dismissing cancels the scan with it, rather than
    // leaving a few hundred sockets to finish opening into nothing.
    LaunchedEffect(Unit) {
        // One socket failing must not take the scan down with it. The passes
        // run as siblings, so an exception in any of them cancels the rest and
        // comes out here -- and an exception out of a LaunchedEffect is not a
        // failed scan, it is a crashed composition. Cancellation is left to
        // propagate, because that one means the dialog was dismissed.
        results = try {
            LinkScanner.scan(context) { stage = it }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        scanning = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (scanning) {
                    Text(
                        text = "Looking for vehicles and simulators on this network.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stage,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (results.isEmpty()) {
                    Text(
                        text = "Nothing answered.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "This looks on the tablet's own network only. A " +
                            "simulator on a laptop needs to be reachable from " +
                            "here — same Wi-Fi, and streaming to the network " +
                            "rather than to its own loopback address.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Tap one to fill in the connection.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    results.forEachIndexed { index, found ->
                        if (index > 0) HorizontalDivider()
                        FoundRow(found) { onPick(found) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (scanning) "Cancel" else "Close") }
        },
    )
}

@Composable
private fun FoundRow(found: LinkScanner.Found, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (found.type == LinkType.TCP) "TCP" else "UDP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = found.label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = found.detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
