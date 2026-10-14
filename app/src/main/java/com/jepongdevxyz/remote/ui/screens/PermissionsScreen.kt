package com.jepongdevxyz.remote.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.jepongdevxyz.remote.service.ControlService
import com.jepongdevxyz.remote.ui.components.AppCard
import com.jepongdevxyz.remote.ui.components.ConfirmDialog
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.nav.Routes
import com.jepongdevxyz.remote.ui.theme.Accept
import com.jepongdevxyz.remote.ui.theme.Amber
import com.jepongdevxyz.remote.ui.theme.Decline

@Composable
fun PermissionsScreen(navController: NavController) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var accessOn by remember { mutableStateOf(ControlService.isEnabled(ctx)) }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33)
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    var warnAccess by remember { mutableStateOf(false) }

    // Re-check the accessibility toggle every time we come back from Settings.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) accessOn = ControlService.isEnabled(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifGranted = it }

    fun goHome() {
        navController.navigate(Routes.HOME) {
            popUpTo(Routes.PERMISSIONS) { inclusive = true }
        }
    }

    if (warnAccess) {
        ConfirmDialog(
            title = "Accessibility is off",
            message = "Without the accessibility service, other devices cannot control this phone. You can still share your screen and receive view-only sessions.",
            confirmText = "Continue anyway",
            onConfirm = { warnAccess = false; goHome() },
            onDismiss = { warnAccess = false }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "DevxyzRemote needs these to work properly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        AppCard {
            PermRow(
                title = "Screen Capture",
                subtitle = "Requested when a session starts, so the other side can see your screen.",
                pill = "At session start",
                pillColor = Amber,
                actionLabel = null,
                onAction = null
            )
        }
        Spacer(Modifier.height(12.dp))
        AppCard {
            PermRow(
                title = "Accessibility Service",
                subtitle = "Lets an accepted remote session tap, swipe and type on this device.",
                pill = if (accessOn) "Enabled" else "Required",
                pillColor = if (accessOn) Accept else Decline,
                actionLabel = if (accessOn) null else "Open Settings",
                onAction = {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        AppCard {
            PermRow(
                title = "Notifications",
                subtitle = "Shows incoming call alerts even when the app is in the background.",
                pill = if (notifGranted) "Granted" else "Required",
                pillColor = if (notifGranted) Accept else Decline,
                actionLabel = if (notifGranted || Build.VERSION.SDK_INT < 33) null else "Allow",
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        PillButton(
            text = "Continue",
            onClick = { if (accessOn) goHome() else warnAccess = true },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PermRow(
    title: String,
    subtitle: String,
    pill: String,
    pillColor: androidx.compose.ui.graphics.Color,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = pillColor.copy(alpha = 0.15f)
            ) {
                Text(
                    pill,
                    style = MaterialTheme.typography.labelSmall,
                    color = pillColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}
