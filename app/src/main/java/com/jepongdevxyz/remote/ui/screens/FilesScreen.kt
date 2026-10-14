package com.jepongdevxyz.remote.ui.screens

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.FileTransferProgress
import com.jepongdevxyz.remote.core.RemotePermission
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.core.SessionState
import com.jepongdevxyz.remote.ui.components.AppCard
import com.jepongdevxyz.remote.ui.components.BottomNav
import com.jepongdevxyz.remote.ui.components.EmptyState
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.nav.Routes
import com.jepongdevxyz.remote.ui.theme.Accept
import com.jepongdevxyz.remote.ui.theme.Decline
import java.io.File

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun queryNameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = "file"
    var size = 0L
    val cursor: Cursor? = ctx.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val n = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = it.getColumnIndex(OpenableColumns.SIZE)
            if (n >= 0) name = it.getString(n) ?: name
            if (s >= 0) size = it.getLong(s)
        }
    }
    return name to size
}

private fun openReceivedFile(ctx: Context, path: String) {
    try {
        val file = File(path)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Open file"))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Can't open this file", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(navController: NavController) {
    val ctx = LocalContext.current
    val state by SessionCoordinator.state.collectAsState()
    val transfers by SessionCoordinator.fileTransfers.collectAsState()
    val active = state as? SessionState.Active
    val filesAllowed = active != null && RemotePermission.FILES in active.granted

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null || active == null) return@rememberLauncherForActivityResult
        val (name, size) = queryNameAndSize(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val id = SessionCoordinator.sendFile(ctx, uri, name, size, mime)
        if (id == null) {
            Toast.makeText(ctx, "Can't send right now", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Files") }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            if (active == null) {
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = "No active session",
                    subtitle = "File transfer is available while you're connected to another device."
                )
                return@Column
            }
            PillButton(
                text = "Send file",
                onClick = { picker.launch("*/*") },
                enabled = filesAllowed,
                modifier = Modifier.fillMaxWidth()
            )
            if (!filesAllowed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "File transfer wasn't granted for this session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            if (transfers.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.AttachFile,
                    title = "No transfers yet",
                    subtitle = "Files you send or receive in this session will appear here."
                )
            } else {
                LazyColumn {
                    items(transfers, key = { it.id }) { t ->
                        TransferRow(t, onOpen = { openReceivedFile(ctx, it) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: FileTransferProgress, onOpen: (String) -> Unit) {
    val progress = if (t.size > 0) t.transferred.toFloat() / t.size.toFloat() else 0f
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    t.failed -> Icons.Filled.Error
                    t.done -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    t.failed -> Decline
                    t.done -> Accept
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    "${if (t.incoming) "Received" else "Sent"} • ${formatBytes(t.transferred)} / ${formatBytes(t.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!t.done && !t.failed) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (t.done && t.incoming && t.savedPath != null) {
                IconButton(onClick = { onOpen(t.savedPath) }) {
                    Icon(Icons.Filled.Download, contentDescription = "Open file")
                }
            }
        }
    }
}
