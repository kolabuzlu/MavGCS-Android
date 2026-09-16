package com.mavgcs.app.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/**
 * Where a picture can come from.
 *
 * [DEVICE] is here and deliberately inert on this hardware. A USB capture card
 * is a video-class device the kernel binds its own driver to, and Android only
 * republishes that as a camera an app can open when the tablet declares support
 * for external cameras. The 12.7in Lenovo does not: plugging a capture card in
 * creates video nodes owned by the camera service and nothing an app is allowed
 * to touch. Reaching it needs the USB host API and a userspace implementation
 * of the video class, which is a piece of work of its own rather than a source
 * to add to a list. The entry stays visible so the reason is visible with it.
 */
enum class VideoSource(val label: String) {
    DEVICE("Video In"),
    RTSP("RTSP"),
}

/**
 * One live picture, and where it is being shown.
 *
 * Held above both the dialog and the map so the stream survives being moved
 * between them. Tearing the player down and building another would drop the
 * connection and start the handshake again, which on a camera that takes a
 * second or two to negotiate is a visible stall for no reason.
 */
@Stable
class LiveVideo {
    var source by mutableStateOf(VideoSource.RTSP)
    var address by mutableStateOf("rtsp://")

    /** Showing over the map rather than inside the dialog. */
    var floating by mutableStateOf(false)

    /** What went wrong, or what is being waited for. Null when it is playing. */
    var message by mutableStateOf<String?>(null)
        private set

    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    val running: Boolean get() = player != null

    @OptIn(UnstableApi::class)
    fun start(context: Context) {
        stop()
        if (source == VideoSource.DEVICE) {
            message = "This tablet does not offer the capture device to apps. " +
                "Use RTSP for now."
            return
        }
        val uri = address.trim()
        if (!uri.startsWith("rtsp://", ignoreCase = true)) {
            message = "An RTSP address starts with rtsp://"
            return
        }
        message = "Connecting to $uri"
        val media = RtspMediaSource.Factory()
            // Over TCP rather than UDP. RTP over UDP needs the camera to reach
            // back to an arbitrary port on the tablet, which a phone hotspot or
            // any ordinary router will drop; interleaving it on the connection
            // already open costs a little latency and always arrives.
            .setForceUseRtpTcp(true)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(uri))
        val created = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    message = "Could not play $uri — ${reasonFor(error)}"
                }

                override fun onRenderedFirstFrame() {
                    message = null
                }
            })
            setMediaSource(media)
            prepare()
            playWhenReady = true
        }
        player = created
    }

    fun stop() {
        player?.release()
        player = null
        message = null
    }

    /**
     * The short version of why a stream would not play.
     *
     * The library's own text is a stack of nested causes; this panel has room
     * for a line.
     */
    private fun reasonFor(error: PlaybackException): String {
        val text = generateSequence(error.cause) { it.cause }
            .lastOrNull()?.message.orEmpty().ifBlank { error.errorCodeName }
        return when {
            text.contains("ECONNREFUSED", true) -> "nothing is listening there"
            text.contains("timed out", true) || text.contains("timeout", true) -> "timed out"
            text.contains("EHOSTUNREACH", true) || text.contains("ENETUNREACH", true) ->
                "no route to that address"
            text.contains("401") || text.contains("Unauthorized", true) ->
                "the camera wants a user name and password"
            text.contains("404") || text.contains("Not Found", true) -> "no stream at that path"
            else -> text.take(60)
        }
    }

    private companion object {
        /** Long enough for a camera that negotiates slowly, short of a hang. */
        const val RTSP_TIMEOUT_MS = 8_000L
    }
}

/** The picture itself, with none of the player's own furniture. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                // Fit rather than fill: a stretched horizon is worse than a
                // letterbox on something being used to judge attitude.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}

/**
 * The window: pick a source, start it, and send it to the map.
 */
@Composable
fun VideoDialog(video: LiveVideo, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live video", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedChoice(
                    options = VideoSource.entries.map { it to it.label },
                    selected = video.source,
                    onSelect = { video.source = it },
                )
                when (video.source) {
                    VideoSource.RTSP -> OutlinedTextField(
                        value = video.address,
                        onValueChange = { video.address = it },
                        label = { Text("Address", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VideoSource.DEVICE -> Text(
                        text = "A USB capture card is not offered to apps by this tablet: " +
                            "Android only republishes one as a camera where the device " +
                            "supports external cameras, and this one does not. Reaching it " +
                            "directly over USB is possible but is its own piece of work.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                video.message?.let { note ->
                    Text(note, fontSize = 11.sp, color = scheme.onSurfaceVariant)
                }
                // The picture stays in the dialog until it is sent to the map,
                // so Start shows something immediately rather than asking for a
                // second press to find out whether it worked.
                video.player?.takeIf { !video.floating }?.let { running ->
                    VideoSurface(
                        player = running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (video.running) {
                    OutlinedButton(onClick = {
                        video.floating = true
                        onDismiss()
                    }) {
                        Text("Floating window", fontSize = 13.sp)
                    }
                }
                TextButton(onClick = {
                    if (video.running) video.stop() else video.start(context)
                }) {
                    Text(if (video.running) "Stop" else "Start", fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) }
            }
        },
    )
}

/**
 * The same picture, over the map, draggable by its bar.
 *
 * Kept inside the map rather than floated over the whole panel: the
 * instruments and the controls are what the pilot is reading, and a video
 * window is not allowed to cover them.
 */
@Composable
fun FloatingVideo(video: LiveVideo, modifier: Modifier = Modifier) {
    val running = video.player ?: return
    val scheme = MaterialTheme.colorScheme
    var offsetX by androidx.compose.runtime.remember { mutableStateOf(0f) }
    var offsetY by androidx.compose.runtime.remember { mutableStateOf(0f) }
    Column(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(FloatingWidth)
            // Opaque. At anything less the map's own toolbar reads straight
            // through the title bar, and two sets of words on one strip is
            // worse than either.
            .background(Color.Black, RoundedCornerShape(6.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(6.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        offsetX += drag.x
                        offsetY += drag.y
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Live video",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 8.dp),
            )
            Box(Modifier.weight(1f))
            IconButton(
                onClick = { video.floating = false },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInFull,
                    contentDescription = "Back to the window",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
            }
            IconButton(
                onClick = {
                    video.floating = false
                    video.stop()
                },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        VideoSurface(
            player = running,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
    }
}

/** Releases the player with the screen, so a stream cannot outlive the app. */
@Composable
fun VideoLifecycle(video: LiveVideo) {
    DisposableEffect(Unit) {
        onDispose { video.stop() }
    }
}

private val FloatingWidth = 360.dp
