package com.jepongdevxyz.remote.ui.screens

import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.RemotePermission
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.core.SessionState
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.theme.Accept
import com.jepongdevxyz.remote.ui.theme.Decline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingScreen(navController: NavController) {
    val ctx = LocalContext.current
    val state by SessionCoordinator.state.collectAsState()
    val incoming = state as? SessionState.Incoming

    BackHandler { SessionCoordinator.declineIncoming() }

    if (incoming == null) {
        // State already moved on; NavGraph will pop us away.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var granted by remember {
        mutableStateOf(
            setOf(
                RemotePermission.VIEW,
                RemotePermission.CONTROL,
                RemotePermission.FILES,
                RemotePermission.VOICE
            )
        )
    }

    val mpm = remember {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        SessionCoordinator.acceptIncoming(ctx, result.resultCode, result.data, granted)
    }

    fun toggle(p: RemotePermission) {
        granted = if (p in granted) granted - p else granted + p
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                incoming.peerName.take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            incoming.peerName.ifBlank { incoming.peerId },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            incoming.peerId,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "wants to connect to your device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Grant for this session",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RemotePermission.entries.forEach { p ->
                PermissionRow(
                    permission = p,
                    checked = p in granted,
                    onToggle = { toggle(p) }
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PillButton(
                text = "Accept",
                onClick = { captureLauncher.launch(mpm.createScreenCaptureIntent()) },
                container = Accept,
                modifier = Modifier.fillMaxWidth()
            )
            PillButton(
                text = "Decline",
                onClick = { SessionCoordinator.declineIncoming() },
                container = Decline,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "Accepting starts screen sharing immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

private fun permissionLabel(p: RemotePermission): String = when (p) {
    RemotePermission.VIEW -> "View Screen"
    RemotePermission.CONTROL -> "Control Device"
    RemotePermission.FILES -> "File Transfer"
    RemotePermission.VOICE -> "Voice Chat"
}

private fun permissionIcon(p: RemotePermission): ImageVector = when (p) {
    RemotePermission.VIEW -> Icons.Filled.ScreenShare
    RemotePermission.CONTROL -> Icons.Filled.TouchApp
    RemotePermission.FILES -> Icons.Filled.Folder
    RemotePermission.VOICE -> Icons.Filled.Call
}

@Composable
private fun PermissionRow(
    permission: RemotePermission,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                permissionIcon(permission),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            permissionLabel(permission),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
