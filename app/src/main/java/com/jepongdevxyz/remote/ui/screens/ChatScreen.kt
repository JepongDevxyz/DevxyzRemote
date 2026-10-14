package com.jepongdevxyz.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.core.SessionState
import com.jepongdevxyz.remote.data.MessageEntity
import com.jepongdevxyz.remote.data.SessionRepository
import com.jepongdevxyz.remote.ui.components.BottomNav
import com.jepongdevxyz.remote.ui.components.EmptyState
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController) {
    val ctx = LocalContext.current
    val state by SessionCoordinator.state.collectAsState()
    val active = state as? SessionState.Active
    val repo = remember { SessionRepository.get(ctx) }
    val sessionId = active?.sessionId

    val messages by remember(sessionId) {
        if (sessionId != null) repo.messages(sessionId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        if (text.isBlank()) return
        SessionCoordinator.sendChat(text)
        text = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (active != null) "Chat • ${active.peerName.ifBlank { active.peerId }}"
                        else "Chat"
                    )
                }
            )
        },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (active == null) {
                EmptyState(
                    icon = Icons.Filled.Chat,
                    title = "No active session",
                    subtitle = "Chat is available while you're connected to another device."
                )
            } else if (messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Chat,
                    title = "No messages yet",
                    subtitle = "Say hello to ${active.peerName.ifBlank { "the other device" }}."
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        ChatBubble(m)
                        Spacer(Modifier.padding(4.dp))
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message…") },
                    singleLine = true,
                    enabled = active != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = ::send,
                    enabled = active != null && text.isNotBlank()
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: MessageEntity) {
    val time = remember(m.ts) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.ts))
    }
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(if (m.mine) Alignment.CenterEnd else Alignment.CenterStart)
                .background(
                    if (m.mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                m.text,
                color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = (if (m.mine) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f)
            )
        }
    }
}
