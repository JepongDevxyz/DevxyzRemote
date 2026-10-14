package com.jepongdevxyz.remote.ui.screens

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.Protocol
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.core.SessionState
import com.jepongdevxyz.remote.ui.components.ConfirmDialog
import com.jepongdevxyz.remote.ui.nav.Routes
import com.jepongdevxyz.remote.ui.theme.Accept
import com.jepongdevxyz.remote.ui.theme.Decline
import com.jepongdevxyz.remote.ui.theme.Neon
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.SurfaceViewRenderer

/**
 * Direct-touch + trackpad input over the remote SurfaceViewRenderer.
 *
 * Direct mode: tap = click, press-and-drag (or long-press-drag) = swipe,
 * two fingers = scroll. Trackpad mode: drag moves the cursor dot, tap clicks
 * at the cursor. Coordinates are normalized 0..1 before sending.
 */
private fun Modifier.remoteInputGestures(
    trackpad: Boolean,
    cursor: MutableState<Offset>,
    onTap: (Offset) -> Unit,
    onSwipe: (Offset, Offset, Int) -> Unit,
    onScroll: (Float, Float) -> Unit
): Modifier = pointerInput(trackpad) {
    while (true) {
        awaitEachGesture {
            val w = size.width.toFloat().coerceAtLeast(1f)
            val h = size.height.toFloat().coerceAtLeast(1f)
            fun norm(p: Offset) =
                Offset((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))

            if (!trackpad) {
                val down = awaitFirstDown()
                // Second finger within 150ms -> two-finger scroll mode.
                // A quick lift inside the window is an instant tap.
                var second: PointerInputChange? = null
                var quickUp = false
                withTimeoutOrNull(150) {
                    var ev = awaitPointerEvent()
                    while (true) {
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            second = pressed.first { it.id != down.id }
                            break
                        }
                        if (ev.changes.any { it.id == down.id && !it.pressed }) {
                            quickUp = true
                            break
                        }
                        ev = awaitPointerEvent()
                    }
                }
                if (second != null) {
                    var lastY = second!!.position.y
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size < 2) break
                        val y = pressed.map { it.position.y }.average().toFloat()
                        val dy = (y - lastY) / h
                        lastY = y
                        if (dy != 0f) onScroll(0f, dy)
                        ev.changes.forEach { it.consume() }
                    }
                } else if (quickUp) {
                    onTap(norm(down.position))
                } else {
                    val t0 = System.currentTimeMillis()
                    val up = withTimeoutOrNull(400) { waitForUpOrCancellation() }
                    if (up != null) {
                        onTap(norm(down.position))
                    } else {
                        var last = down.position
                        val slop = awaitTouchSlopOrCancellation(down.id) { c, _ ->
                            last = c.position
                            c.consume()
                        }
                        if (slop != null) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                last = c.position
                                c.consume()
                            }
                        }
                        val dur = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(60)
                        if ((last - down.position).getDistance() > 12f) {
                            onSwipe(norm(down.position), norm(last), dur)
                        } else {
                            onTap(norm(down.position))
                        }
                    }
                }
            } else {
                val down = awaitFirstDown()
                val up = withTimeoutOrNull(350) { waitForUpOrCancellation() }
                if (up != null) {
                    onTap(cursor.value)
                } else {
                    val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                    if (slop != null) {
                        var cur = Offset(cursor.value.x * w, cursor.value.y * h)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            cur += c.positionChange()
                            cur = Offset(
                                cur.x.coerceIn(0f, w),
                                cur.y.coerceIn(0f, h)
                            )
                            cursor.value = Offset(cur.x / w, cur.y / h)
                            c.consume()
                        }
                    } else {
                        onTap(cursor.value)
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteScreen(navController: NavController) {
    val ctx = LocalContext.current
    val state by SessionCoordinator.state.collectAsState()
    val rtt by SessionCoordinator.rttMs.collectAsState()
    val active = state as? SessionState.Active

    var trackpad by remember { mutableStateOf(false) }
    var cursor = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showDisconnect by remember { mutableStateOf(false) }

    BackHandler { showDisconnect = true }

    if (showDisconnect) {
        ConfirmDialog(
            title = "Disconnect?",
            message = "End the remote session with ${active?.peerId ?: "the device"}?",
            confirmText = "Disconnect",
            destructive = true,
            onConfirm = { SessionCoordinator.endSession() },
            onDismiss = { showDisconnect = false }
        )
    }
    if (showKeyboard) KeyboardDialog(onDismiss = { showKeyboard = false })
    if (showVoice) VoiceOverlay(onDismiss = { showVoice = false })

    if (active == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    fun tap(p: Offset) = SessionCoordinator.sendInput(Protocol.tap(p.x, p.y))
    fun swipe(a: Offset, b: Offset, dur: Int) =
        SessionCoordinator.sendInput(Protocol.swipe(a.x, a.y, b.x, b.y, dur))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                SurfaceViewRenderer(c).also { SessionCoordinator.attachRemoteRenderer(it) }
            },
            onRelease = { SessionCoordinator.detachRemoteRenderer() },
            modifier = Modifier
                .fillMaxSize()
                .remoteInputGestures(
                    trackpad = trackpad,
                    cursor = cursor,
                    onTap = ::tap,
                    onSwipe = ::swipe,
                    onScroll = { dx, dy -> SessionCoordinator.sendInput(Protocol.scroll(dx, dy)) }
                )
        )

        // trackpad cursor dot
        if (trackpad) {
            CanvasCursor(cursor = cursor.value)
        }

        // ---- top overlay ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(Accept, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                active.peerId,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.15f)
            ) {
                Text(
                    "1080p HD",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${rtt}ms",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // ---- right-edge vertical toolbar ----
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF0B1D33).copy(alpha = 0.92f)
            ) {
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ToolButton(Icons.Filled.Keyboard, "Keyboard") { showKeyboard = true }
                    ToolButton(Icons.Filled.ArrowBack, "Back") {
                        SessionCoordinator.sendInput(Protocol.action("back"))
                    }
                    ToolButton(Icons.Filled.Home, "Home") {
                        SessionCoordinator.sendInput(Protocol.action("home"))
                    }
                    ToolButton(Icons.Filled.Apps, "Recents") {
                        SessionCoordinator.sendInput(Protocol.action("recents"))
                    }
                    ToolButton(Icons.Filled.Folder, "Files") {
                        navController.navigate(Routes.FILES)
                    }
                    ToolButton(Icons.Filled.Chat, "Chat") {
                        navController.navigate(Routes.CHAT)
                    }
                    ToolButton(Icons.Filled.Mic, "Voice") { showVoice = true }
                    ToolButton(Icons.Filled.CallEnd, "Disconnect", tint = Decline) {
                        showDisconnect = true
                    }
                }
            }
        }

        // ---- bottom status ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (trackpad) "Trackpad" else "Touch",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = trackpad, onCheckedChange = { trackpad = it })
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Accept, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Connected • Real-time",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
private fun CanvasCursor(cursor: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        val x = cursor.x * size.width
        val y = cursor.y * size.height
        drawCircle(Neon, radius = 14f, center = Offset(x, y))
        drawCircle(Color.White, radius = 5f, center = Offset(x, y))
    }
}

@Composable
private fun DialpadDialog(onDismiss: () -> Unit) {
    val toneGen = remember {
        try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        } catch (e: Exception) {
            null
        }
    }

    fun dtmf(c: Char) {
        val tone = when (c) {
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '0' -> ToneGenerator.TONE_DTMF_0
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        try {
            toneGen?.startTone(tone, 150)
        } catch (e: Exception) {
        }
    }

    AlertDialog(
        onDismissRequest = {
            try { toneGen?.release() } catch (e: Exception) {}
            onDismiss()
        },
        title = { Text("Dialpad") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    listOf('1', '2', '3'),
                    listOf('4', '5', '6'),
                    listOf('7', '8', '9'),
                    listOf('*', '0', '#')
                ).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { c ->
                            Surface(
                                onClick = { dtmf(c) },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        c.toString(),
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { toneGen?.release() } catch (e: Exception) {}
                onDismiss()
            }) { Text("Close") }
        }
    )
}

@Composable
private fun KeyboardDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    fun send() {
        if (text.isNotEmpty()) {
            SessionCoordinator.sendInput(Protocol.text(text))
            text = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on remote device") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type here…") },
                singleLine = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { send(); onDismiss() }),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { send(); onDismiss() }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun VoiceOverlay(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var seconds by remember { mutableStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds++
        }
    }

    fun fmt(s: Int) = "%02d:%02d".format(s / 60, s % 60)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Voice call", style = MaterialTheme.typography.titleLarge)
                Text(
                    fmt(seconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                muted = !muted
                                SessionCoordinator.setMicrophoneEnabled(!muted)
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (muted) Decline.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (muted) "Unmute" else "Mute",
                                tint = if (muted) Decline else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(if (muted) "Unmute" else "Mute", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                speaker = !speaker
                                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                am.mode = AudioManager.MODE_IN_COMMUNICATION
                                am.isSpeakerphoneOn = speaker
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (speaker) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                if (speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = "Speaker",
                                tint = if (speaker) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("Speaker", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        var showDialpad by remember { mutableStateOf(false) }
                        if (showDialpad) {
                            DialpadDialog(onDismiss = { showDialpad = false })
                        }
                        IconButton(
                            onClick = { showDialpad = true },
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Filled.Dialpad,
                                contentDescription = "Dialpad",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("Dialpad", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(56.dp)
                                .background(Decline, CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.CallEnd,
                                contentDescription = "End voice",
                                tint = Color.White
                            )
                        }
                        Text("End", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
