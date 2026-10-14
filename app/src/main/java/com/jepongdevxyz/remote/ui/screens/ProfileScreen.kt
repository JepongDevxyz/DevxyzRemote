package com.jepongdevxyz.remote.ui.screens

import android.content.Context
import android.media.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.Prefs
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.ui.components.AppCard
import com.jepongdevxyz.remote.ui.components.BottomNav
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.components.SectionHeader
import com.jepongdevxyz.remote.ui.nav.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceName by Prefs.deviceName.collectAsState(initial = "")
    val themeMode by Prefs.themeMode.collectAsState(initial = "SYSTEM")
    val quality by Prefs.quality.collectAsState(initial = "1080p")

    var nameEdit by remember { mutableStateOf<String?>(null) }
    var nameSavedTick by remember { mutableStateOf(0) }

    val mpm = remember {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    // Loopback self-test: startLoopbackTest arms the pipeline, then the
    // capture result is forwarded once the internal coroutine has armed it.
    val loopLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        scope.launch {
            SessionCoordinator.startLoopbackTest(ctx)
            delay(800)
            SessionCoordinator.onLoopbackCaptureResult(result.resultCode, result.data)
        }
    }

    LaunchedEffect(nameSavedTick) { /* refresh trigger */ }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Profile") }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp)
        ) {
            SectionHeader("Device")
            AppCard {
                Text(
                    "DEVICE NAME",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nameEdit ?: deviceName,
                    onValueChange = { nameEdit = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                PillButton(
                    text = "Save name",
                    onClick = {
                        val v = (nameEdit ?: deviceName).ifBlank { deviceName }
                        scope.launch {
                            Prefs.setDeviceName(ctx, v)
                            nameEdit = null
                            nameSavedTick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionHeader("Appearance")
            AppCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("SYSTEM", "LIGHT", "DARK").forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { scope.launch { Prefs.setThemeMode(ctx, mode) } },
                            label = { Text(mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            SectionHeader("Stream quality")
            AppCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("720p", "1080p").forEach { q ->
                        FilterChip(
                            selected = quality == q,
                            onClick = { scope.launch { Prefs.setQuality(ctx, q) } },
                            label = { Text(q) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Applies to the next session you share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionHeader("Diagnostics")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.padding(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Loopback self-test", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tests the full pipeline on this phone alone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    text = "Run loopback test",
                    onClick = { loopLauncher.launch(mpm.createScreenCaptureIntent()) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionHeader("More")
            AppCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Routes.HISTORY) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Session history",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "DevxyzRemote 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
