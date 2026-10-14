package com.jepongdevxyz.remote.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jepongdevxyz.remote.core.DeviceIdManager

@Composable
fun QrDialog(deviceId: String, onDismiss: () -> Unit) {
    val bitmap = remember(deviceId) { DeviceIdManager.qrBitmap(deviceId, 640) }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Scan to connect", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Device ID QR code",
                    modifier = Modifier.size(240.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    deviceId,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
