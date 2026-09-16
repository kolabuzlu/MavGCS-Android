package com.mavgcs.app.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mavgcs.app.video.UvcDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    STREAM("Stream"),
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
    /**
     * For the capture card, which is read with blocking USB calls.
     *
     * Those take seconds -- a negotiation and then a measured read, for each
     * format the card offers -- and on the main thread that is not slow, it is
     * an unresponsive app: Android put up "Application Not Responding" the
     * first time this ran on a tablet where the card stayed silent.
     */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var source by mutableStateOf(VideoSource.STREAM)
    var address by mutableStateOf("udp://0.0.0.0:5000")

    /** Showing over the map rather than inside the dialog. */
    var floating by mutableStateOf(false)

    /** What went wrong, or what is being waited for. Null when it is playing. */
    var message by mutableStateOf<String?>(null)
        private set

    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    /**
     * The newest decoded picture, for the MJPEG path.
     *
     * Motion JPEG has no player behind it and needs none: every frame is a
     * whole picture, so there is nothing to buffer, nothing to synchronise and
     * no stream state to keep. That is also why it recovers instantly from a
     * dropped packet, which matters more on a video being watched while flying
     * than the bandwidth it costs.
     */
    var frame by mutableStateOf<android.graphics.Bitmap?>(null)
        private set

    private var mjpeg: Job? = null

    /**
     * Whether the Motion JPEG reader is running, as state rather than as a
     * question asked of the job.
     *
     * A plain field would have been true at the right moments and still wrong:
     * nothing recomposes when it changes, so the window went on offering Start
     * over a picture that was already playing, and the control that sends it to
     * the map never appeared at all.
     */
    private var streaming by mutableStateOf(false)

    /** What the capture card said about itself, line by line. */
    var deviceReport by mutableStateOf<List<String>>(emptyList())
        private set

    /** Set when the camera permission is what is standing in the way. */
    var needsCameraPermission by mutableStateOf(false)
        private set

    val running: Boolean get() = player != null || streaming

    @OptIn(UnstableApi::class)
    fun start(context: Context) {
        stop()
        if (source == VideoSource.DEVICE) {
            openCapture(context)
            return
        }
        val uri = address.trim()
        val rtsp = uri.startsWith("rtsp://", ignoreCase = true)
        val udp = uri.startsWith("udp://", ignoreCase = true)
        val http = uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true)
        if (http) {
            startMjpeg(uri)
            return
        }
        if (!rtsp && !udp) {
            message = "An address starts with http://, rtsp:// or udp://"
            return
        }
        message = "Opening $uri"
        val media = if (rtsp) {
            RtspMediaSource.Factory()
                // Over TCP rather than UDP. Interleaving the video on the
                // connection already open costs a little latency; the
                // alternative asks the camera to reach back to an arbitrary
                // port on the tablet, which an ordinary router will drop.
                .setForceUseRtpTcp(true)
                .setTimeoutMs(RTSP_TIMEOUT_MS)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            // A plain transport stream arriving on a port. Nothing is
            // negotiated and nothing is requested: the sender pushes and this
            // end listens, which is why the address is a port on this tablet
            // rather than somewhere to dial. Lower latency than RTSP, and a
            // lost packet costs a blemish rather than a stall -- the right
            // trade for a picture being watched while flying.
            ProgressiveMediaSource.Factory(
                { UdpDataSource(UDP_RECEIVE_BYTES, UDP_TIMEOUT_MS) },
                DefaultExtractorsFactory(),
            ).createMediaSource(MediaItem.fromUri(uri))
        }
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

    /**
     * Find the capture card, ask for it, and see what it will give us.
     *
     * Permission is the user's to grant and arrives on a broadcast, so the work
     * continues there rather than returning an answer here.
     */
    fun openCapture(context: Context) {
        // Android will not grant USB access to a video-class device to an app
        // without camera permission, and refuses before showing any prompt.
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            needsCameraPermission = true
            message = "Android treats a capture card as a camera, so it needs " +
                "camera permission before it will hand this one over."
            return
        }
        needsCameraPermission = false
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val found = UvcDevice.attached(manager)
        if (found.isEmpty()) {
            message = "No capture device is plugged in."
            deviceReport = emptyList()
            return
        }
        val device = found.first()
        if (manager.hasPermission(device)) {
            inspect(manager, device)
            return
        }
        message = "Waiting for permission to use ${device.productName ?: "the capture device"}"
        val action = "com.mavgcs.app.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { ctx.unregisterReceiver(this) }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    inspect(manager, device)
                } else {
                    message = "Permission for the capture device was refused."
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        manager.requestPermission(device, pending)
    }

    /**
     * Open the card and write down what it offers.
     *
     * Reported rather than acted on for now: what a capture card advertises
     * depends on what is plugged into its input and on how fast the cable it
     * arrived through is, and both have to be right before a picture is
     * possible. Saying which one is wrong is more use than failing silently.
     */
    private fun inspect(manager: UsbManager, device: UsbDevice) {
        message = "Opening the capture card"
        deviceReport = emptyList()
        streaming = true
        mjpeg = work.launch {
            try {
                withContext(Dispatchers.IO) { capture(manager, device) }
            } finally {
                streaming = false
                frame = null
            }
        }
    }

    /**
     * Read the capture card and show whatever it gives us.
     *
     * Frame rate is not the point and is not chased: the card insists on 60 a
     * second and the cable will carry a couple, so most of what it sends is
     * dropped on the floor by the link and the ones that arrive whole are the
     * picture. Each is drawn as it lands.
     */
    private suspend fun capture(manager: UsbManager, device: UsbDevice) {
        val opened = UvcDevice.open(manager, device)
        if (opened == null) {
            withContext(Dispatchers.Main) { message = "Could not claim the capture card." }
            return
        }
        try {
            val formats = opened.formats()
            // NV12 first: turning it into something drawable is a byte swap,
            // where the packed and planar layouts both need gathering.
            val wanted = formats.firstOrNull { it.fourcc.equals("NV12", true) }
                ?: formats.firstOrNull()
            if (wanted == null) {
                withContext(Dispatchers.Main) {
                    message = "The card is advertising no video formats, which " +
                        "usually means nothing is plugged into its input."
                }
                return
            }
            val agreed = opened.negotiate(wanted)
            if (agreed == null) {
                withContext(Dispatchers.Main) { message = "The card would not agree a format." }
                return
            }
            withContext(Dispatchers.Main) {
                deviceReport = listOf(
                    opened.name,
                    "%s %dx%d".format(agreed.fourcc, agreed.width, agreed.height),
                )
                message = "Waiting for a frame"
            }
            val buffer = ByteArray(agreed.maxFrameBytes.coerceAtLeast(1 shl 20))
            val luma = agreed.width * agreed.height
            // Exactly what one frame is, rather than what the card says its
            // buffer might need: NV12 and its planar cousin are twelve bits a
            // pixel, and the packed one is sixteen.
            val frameBytes = when (agreed.fourcc.uppercase()) {
                "YUY2", "YUYV" -> luma * 2
                else -> luma + luma / 2
            }
            var shown = 0
            var biggest = 0
            var began = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                val got = opened.readFrame(buffer, frameBytes, FRAME_WAIT_MS)
                if (got > biggest) biggest = got
                // The whole picture is not insisted on. A frame that arrives
                // with its colour cut short still shows what the camera sees,
                // and one picture is worth more here than a perfect one that
                // never comes.
                if (got < frameBytes) {
                    // Nothing is assembling. Say what the stream looks like
                    // instead of waiting silently on it.
                    val shape = opened.describeStream(2_000)
                    withContext(Dispatchers.Main) {
                        message = "No frame yet. Largest %.1f MB of %.1f MB."
                            .format(biggest / 1e6, agreed.maxFrameBytes / 1e6)
                        deviceReport = listOf(
                            opened.name,
                            "%s %dx%d".format(agreed.fourcc, agreed.width, agreed.height),
                            "%d reads, %.1f MB in 2s, %d short".format(
                                shape.reads, shape.bytes / 1e6, shape.shortReads,
                            ),
                            "%d looked like headers, %d said end-of-frame".format(
                                shape.validHeaders, shape.endOfFrames,
                            ),
                            "read sizes: " + shape.sizes.joinToString(", "),
                        )
                    }
                    continue
                }
                val bitmap = opened.toBitmap(buffer, got, agreed) ?: continue
                shown++
                val seconds = (System.currentTimeMillis() - began) / 1000.0
                val rate = if (seconds > 0) shown / seconds else 0.0
                withContext(Dispatchers.Main) {
                    frame = bitmap
                    message = null
                    deviceReport = listOf(
                        opened.name,
                        "%s %dx%d — %d frames, %.1f a second".format(
                            agreed.fourcc, agreed.width, agreed.height, shown, rate,
                        ),
                    )
                }
                if (shown == 1) {
                    began = System.currentTimeMillis()
                    shown = 0
                }
            }
        } finally {
            opened.close()
        }
    }

    private fun gather(manager: UsbManager, device: UsbDevice): List<String> {
        val opened = UvcDevice.open(manager, device)
        if (opened == null) {
            return listOf("Could not claim the capture device.")
        }
        val lines = mutableListOf<String>()
        try {
            lines += opened.name
            val formats = opened.formats()
            if (formats.isEmpty()) {
                lines += "It is advertising no video formats, which usually means " +
                    "nothing is plugged into its input."
            }
            formats.forEach { f ->
                lines += "%s %dx%d at %.0f fps — %.0f MB/s".format(
                    f.fourcc, f.width, f.height, f.fps, f.bytesPerSecond / 1e6,
                )
            }
            // Each format at the rate it advertises, and again at 30 -- the
            // rate actually wanted. A card lists discrete intervals but the
            // negotiation lets the host propose another, and many will honour
            // one they never advertised. Halving the rate halves what the
            // cable has to carry, which is the whole question here.
            var best = 0.0
            for (want in formats) {
                for (target in listOf(0, 30)) {
                    val asked = if (target == 0) want else want.copy(
                        frameIntervalNs = 10_000_000L / target,
                    )
                    val agreed = opened.negotiate(asked)
                    if (agreed == null) {
                        lines += "%s: the card would not agree it.".format(want.fourcc)
                        continue
                    }
                    val buffer = ByteArray(agreed.maxFrameBytes.coerceAtLeast(1 shl 20))
                    val began = System.currentTimeMillis()
                    var bytes = 0L
                    var frames = 0
                    while (System.currentTimeMillis() - began < MEASURE_MS) {
                        val got = opened.readFrame(buffer, agreed.maxFrameBytes, 900)
                        if (got > 0) {
                            bytes += got
                            if (got >= agreed.maxFrameBytes) frames++
                        }
                    }
                    val seconds = (System.currentTimeMillis() - began) / 1000.0
                    val rate = bytes / seconds / 1e6
                    if (rate > best) best = rate
                    lines += "%s at %.0f fps: %.1f MB/s, %d whole frames (%.1f fps of picture)"
                        .format(
                            agreed.fourcc, agreed.fps, rate, frames,
                            rate * 1e6 / agreed.maxFrameBytes.coerceAtLeast(1),
                        )
                }
            }
            lines += when {
                best <= 0.1 -> "Nothing is arriving at all."
                else -> "Best %.1f MB/s. A 1080p frame is about 3.1 MB, so that is %.1f fps."
                    .format(best, best * 1e6 / 3_110_400)
            }
        } finally {
            opened.close()
        }
        return lines
    }

    /**
     * Read Motion JPEG from an HTTP stream, frame by frame.
     *
     * The frames are found by their own markers rather than by reading the
     * multipart headers around them: a JPEG begins FF D8 and ends FF D9, which
     * is unambiguous, and it means the reader does not care how the sender
     * spells its boundaries.
     */
    private fun startMjpeg(uri: String) {
        message = "Opening $uri"
        streaming = true
        mjpeg = work.launch {
            try {
                val result = withContext(Dispatchers.IO) { readMjpeg(uri) }
                if (result != null) message = result
            } finally {
                streaming = false
                frame = null
            }
        }
    }

    private suspend fun readMjpeg(uri: String): String? {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = (java.net.URL(uri).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doInput = true
            }
            if (connection.responseCode !in 200..299) {
                return "Could not play $uri — the server answered ${connection.responseCode}"
            }
            // 565 rather than full colour: half the memory and half the work
            // per frame, and the difference is invisible on a video overlay.
            val options = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val input = java.io.BufferedInputStream(connection.inputStream, READ_CHUNK)
            val buffer = java.io.ByteArrayOutputStream(READ_CHUNK)
            val chunk = ByteArray(READ_CHUNK)
            var seenStart = false
            var previous = 0
            while (currentCoroutineContext().isActive) {
                val read = input.read(chunk)
                if (read < 0) return "The video stream ended."
                for (i in 0 until read) {
                    val byte = chunk[i].toInt() and 0xFF
                    if (!seenStart) {
                        if (previous == 0xFF && byte == 0xD8) {
                            seenStart = true
                            buffer.reset()
                            buffer.write(0xFF)
                            buffer.write(0xD8)
                        }
                    } else {
                        buffer.write(byte)
                        if (previous == 0xFF && byte == 0xD9) {
                            val bytes = buffer.toByteArray()
                            val bitmap = android.graphics.BitmapFactory
                                .decodeByteArray(bytes, 0, bytes.size, options)
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    frame = bitmap
                                    message = null
                                }
                            }
                            seenStart = false
                        }
                    }
                    previous = byte
                }
            }
            return null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return "Could not play $uri — ${error.message ?: error.javaClass.simpleName}"
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    fun stop() {
        player?.release()
        player = null
        mjpeg?.cancel()
        mjpeg = null
        streaming = false
        frame = null
        message = null
        work.coroutineContext.cancelChildren()
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

        /** How long to read the capture card for, when weighing the cable. */
        const val MEASURE_MS = 3_000L

        /**
         * How long to wait for one whole frame off the card.
         *
         * Generous. The link delivers a couple of frames a second at best, and
         * a frame that takes a moment is worth far more than a timeout.
         */
        const val FRAME_WAIT_MS = 4_000

        /** How long to wait on an HTTP video stream before giving up. */
        const val HTTP_TIMEOUT_MS = 8_000

        /** Read size for the Motion JPEG stream. */
        const val READ_CHUNK = 64 * 1024

        /** Room for a burst of transport stream packets between reads. */
        const val UDP_RECEIVE_BYTES = 64 * 1024

        /** How long a silent port waits before saying nothing is coming. */
        const val UDP_TIMEOUT_MS = 8_000
    }
}

/**
 * Whichever picture is live, drawn the same way in the window and on the map.
 *
 * Two sources with nothing in common behind them -- a player holding a decoded
 * stream, and a bare bitmap replaced twenty times a second -- and one place
 * that decides which is showing, so neither caller has to know.
 */
@Composable
fun VideoPicture(video: LiveVideo, modifier: Modifier = Modifier) {
    val player = video.player
    val picture = video.frame
    when {
        player != null -> VideoSurface(player, modifier)
        picture != null -> Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = "Live video",
            contentScale = ContentScale.Fit,
            modifier = modifier.background(Color.Black),
        )
        else -> Box(modifier.background(Color.Black))
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
    // Asked for here because a permission prompt needs an activity to belong
    // to, and carried straight through to the capture attempt that wanted it.
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) video.openCapture(context) }
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
                    VideoSource.STREAM -> OutlinedTextField(
                        value = video.address,
                        onValueChange = { video.address = it },
                        label = { Text("http:// or rtsp:// to dial out, udp:// to listen", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VideoSource.DEVICE -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Press Start to ask the tablet for the capture card and " +
                                "read what it is offering.",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                        )
                        video.deviceReport.forEach { line ->
                            Text(line, fontSize = 12.sp, color = scheme.onSurface)
                        }
                    }
                }
                video.message?.let { note ->
                    Text(note, fontSize = 11.sp, color = scheme.onSurfaceVariant)
                }
                // The picture stays in the dialog until it is sent to the map,
                // so Start shows something immediately rather than asking for a
                // second press to find out whether it worked.
                if (video.running && !video.floating) {
                    VideoPicture(
                        video = video,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
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
                    when {
                        video.running -> video.stop()
                        video.needsCameraPermission ->
                            askCamera.launch(android.Manifest.permission.CAMERA)
                        else -> video.start(context)
                    }
                }) {
                    Text(
                        text = when {
                            video.running -> "Stop"
                            video.needsCameraPermission -> "Allow camera"
                            else -> "Start"
                        },
                        fontSize = 13.sp,
                    )
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
    if (!video.running) return
    val scheme = MaterialTheme.colorScheme
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var width by remember { mutableStateOf(FloatingWidth) }
    // The room the map is giving it, so the corner cannot be dragged out to a
    // size the window has nowhere to be.
    BoxWithConstraints(
        modifier = modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) },
    ) {
        val room = maxOf(maxWidth, MinFloatingWidth)
        val shown = width.coerceIn(MinFloatingWidth, room)
        Column(
            modifier = Modifier
                .width(shown)
                // Opaque. At anything less the map's own toolbar reads straight
                // through the title bar, and two sets of words on one strip is
                // worse than either.
                .background(Color.Black, RoundedCornerShape(6.dp))
                .border(1.dp, scheme.outline, RoundedCornerShape(6.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TitleBarHeight)
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
            VideoPicture(
                video = video,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
        }
        // The grip. The picture's shape is fixed, so the corner is not free to
        // go wherever the finger does -- it can only travel along the window's
        // own diagonal. Putting the finger on the nearest point of that line
        // rather than following one axis is what stops a diagonal drag, which
        // is how anyone grabs a corner, from doing half of nothing.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(ResizeGrip)
                .pointerInput(room) {
                    detectDragGestures { _, drag ->
                        // Read the width back out of the state on every event.
                        // Taken from the value composition handed down instead,
                        // each event would measure from where the window was
                        // when the finger landed, so a drag of a hundred small
                        // steps would end up applying only the last one -- a
                        // three hundred pixel pull moved the corner by six.
                        val now = width.coerceIn(MinFloatingWidth, room)
                        val tall = (TitleBarHeight.value + now.value * 9f / 16f) / now.value
                        val step = (drag.x + tall * drag.y) / (1f + tall * tall)
                        width = (now + step.toDp()).coerceIn(MinFloatingWidth, room)
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val edge = size.minDimension
                val ink = Color.White.copy(alpha = 0.7f)
                // Two strokes across the corner, longer one outside, which is
                // the grip every desktop window uses.
                listOf(0.34f to 0.90f, 0.58f to 0.90f).forEach { (from, to) ->
                    drawLine(
                        color = ink,
                        start = Offset(edge * from, edge * to),
                        end = Offset(edge * to, edge * from),
                        strokeWidth = edge * 0.07f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
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

/** Small enough to tuck away, still big enough to read the picture. */
private val MinFloatingWidth = 200.dp

private val TitleBarHeight = 26.dp

/** A finger's worth of corner, larger than the marks drawn inside it. */
private val ResizeGrip = 30.dp
