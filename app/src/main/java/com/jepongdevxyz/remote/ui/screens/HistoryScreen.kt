package com.jepongdevxyz.remote.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.navigation.NavController
import com.jepongdevxyz.remote.data.SessionEntity
import com.jepongdevxyz.remote.data.SessionRepository
import com.jepongdevxyz.remote.ui.components.AppCard
import com.jepongdevxyz.remote.ui.components.ConfirmDialog
import com.jepongdevxyz.remote.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepository.get(ctx) }
    val sessions by repo.sessions().collectAsState(initial = emptyList())
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SessionEntity?>(null) }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear history?",
            message = "Delete all recorded sessions? This can't be undone.",
            confirmText = "Clear all",
            destructive = true,
            onConfirm = {
                confirmClear = false
                scope.launch { repo.clearSessions() }
            },
            onDismiss = { confirmClear = false }
        )
    }
    confirmDelete?.let { s ->
        ConfirmDialog(
            title = "Delete session?",
            message = "Remove the session with ${s.peerName.ifBlank { s.peerId }}?",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = null
                scope.launch { repo.deleteSession(s.id) }
            },
            onDismiss = { confirmDelete = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sessions.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear all")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "No sessions yet",
                    subtitle = "Your past remote sessions will be listed here."
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                items(sessions, key = { it.id }) { s ->
                    SessionRow(s, onDelete = { confirmDelete = s })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun formatDuration(sec: Long): String =
    if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"

@Composable
private fun SessionRow(s: SessionEntity, onDelete: () -> Unit) {
    val whenText = remember(s.startedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(s.startedAt))
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s.peerName.ifBlank { s.peerId }.take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.peerName.ifBlank { s.peerId },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    s.peerId,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$whenText • ${formatDuration(s.durationSec)} • ${s.direction.replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete session")
            }
        }
    }
}
