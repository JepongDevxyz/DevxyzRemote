package com.jepongdevxyz.remote.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.DeviceIdManager
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.core.SessionState
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.nav.Routes

/** Prefill target set by DevicesScreen before navigating here. */
internal object ConnectPrefill {
    var id: String? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(navController: NavController) {
    val ctx = LocalContext.current
    val state by SessionCoordinator.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var idText by remember {
        mutableStateOf(ConnectPrefill.id?.also { ConnectPrefill.id = null } ?: "")
    }
    val entry = navController.currentBackStackEntry
    val scanned by (entry?.savedStateHandle?.getStateFlow("scanned_id", "")?.collectAsState()
        ?: remember { mutableStateOf("") })

    LaunchedEffect(scanned) {
        if (scanned.isNotEmpty()) {
            idText = scanned
            entry?.savedStateHandle?.set("scanned_id", "")
        }
    }

    val failed = state as? SessionState.Failed
    LaunchedEffect(failed) {
        failed?.let { snackbar.showSnackbar(it.reason) }
    }
    val dialing = state as? SessionState.Dialing
    val valid = DeviceIdManager.isValidDeviceId(idText)
    val showError = idText.isNotEmpty() && !valid

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Connect to Device") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = idText,
                    onValueChange = { idText = DeviceIdManager.formatInput(it) },
                    label = { Text("Device ID") },
                    placeholder = { Text("DEVXYZ-XXXX-XXXX") },
                    singleLine = true,
                    isError = showError,
                    supportingText = {
                        if (showError) Text("Enter a valid ID like DEVXYZ-XXXX-XXXX")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(16.dp))
                PillButton(
                    text = "Connect",
                    onClick = { SessionCoordinator.startOutgoing(ctx, idText) },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "— or —",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { navController.navigate(Routes.QRSCAN) }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan QR code")
                }
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(listOf("1","2","3","4","5","6","7","8","9","0")) { digit ->
                        Surface(
                            onClick = { idText = DeviceIdManager.formatInput(idText + digit) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.aspectRatio(1.6f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(digit, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                    item {
                        Surface(
                            onClick = {
                                if (idText.isNotEmpty()) {
                                    idText = DeviceIdManager.formatInput(idText.dropLast(1))
                                }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.aspectRatio(1.6f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Backspace, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }

            // ---- dialing overlay ----
            if (dialing != null) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Calling…", style = MaterialTheme.typography.titleLarge)
                        Text(
                            dialing.peerId,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(24.dp))
                        PillButton(
                            text = "Cancel",
                            onClick = { SessionCoordinator.endSession() },
                            container = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
