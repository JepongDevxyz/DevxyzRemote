package com.jepongdevxyz.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.R
import com.jepongdevxyz.remote.core.Prefs
import com.jepongdevxyz.remote.ui.components.PillButton
import com.jepongdevxyz.remote.ui.nav.Routes
import com.jepongdevxyz.remote.ui.theme.Neon
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.ic_app_mark),
            contentDescription = "DevxyzRemote",
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Devxyz") }
                withStyle(SpanStyle(color = Neon)) { append("Remote") }
            },
            style = MaterialTheme.typography.displaySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Fast \u2022 Secure \u2022 Reliable",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Control another phone or share your screen with a simple device ID. No account needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        PillButton(
            text = "Get Started",
            onClick = {
                scope.launch {
                    Prefs.setFirstRunDone(ctx)
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
