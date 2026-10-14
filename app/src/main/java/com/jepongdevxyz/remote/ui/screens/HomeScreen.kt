package com.jepongdevxyz.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.Backend
import com.jepongdevxyz.remote.core.DeviceIdManager
import com.jepongdevxyz.remote.core.Prefs
import com.jepongdevxyz.remote.ui.components.AppCard
import com.jepongdevxyz.remote.ui.components.BottomNav
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.components.QrDialog
import com.jepongdevxyz.remote.ui.components.SectionHeader
import com.jepongdevxyz.remote.ui.nav.Routes
import com.jepongdevxyz.remote.ui.theme.Accept
import com.jepongdevxyz.remote.ui.theme.Muted
import com.jepongdevxyz.remote.ui.theme.Neon
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val online by Prefs.online.collectAsState(initial = true)
    var deviceId by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    val signalingOk = remember { Backend.hasFirebase(ctx) }

    LaunchedEffect(Unit) {
        deviceId = DeviceIdManager.getDeviceId(ctx)
    }

    if (showQr && deviceId.isNotEmpty()) {
        QrDialog(deviceId = deviceId, onDismiss = { showQr = false })
    }

    Scaffold(bottomBar = { BottomNav(navController) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp)
        ) {
            // ---- status ----
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(if (online) Accept else Muted, CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (online) "Online" else "Offline",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (online) "Ready to receive connections"
                            else "You won't receive calls while offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (signalingOk) "Signaling: Firebase ready"
                            else "Signaling: not configured",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (signalingOk) Accept else Muted
                        )
                    }
                    Switch(
                        checked = online,
                        onCheckedChange = {
                            scope.launch { Prefs.setOnline(ctx, it) }
                        }
                    )
                }
            }

            SectionHeader("Your device")
            // ---- device id ----
            AppCard {
                Text(
                    "DEVICE ID",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    deviceId.ifEmpty { "…" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    color = Neon
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                        Toast.makeText(ctx, "Device ID copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy device ID")
                    }
                    IconButton(onClick = { showQr = true }) {
                        Icon(Icons.Filled.QrCode, contentDescription = "Show QR code")
                    }
                    IconButton(onClick = { showQr = true }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share device ID")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            PillButton(
                text = "Remote Control",
                onClick = { navController.navigate(Routes.CONNECT) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = "Share My ID",
                onClick = { showQr = true },
                modifier = Modifier.fillMaxWidth(),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
