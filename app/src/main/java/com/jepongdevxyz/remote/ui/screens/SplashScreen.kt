package com.jepongdevxyz.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jepongdevxyz.remote.R
import com.jepongdevxyz.remote.core.Prefs
import com.jepongdevxyz.remote.ui.nav.Routes
import com.jepongdevxyz.remote.ui.theme.Neon
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(navController: NavController) {
    val firstRun by Prefs.firstRun.collectAsState(initial = null)

    LaunchedEffect(firstRun) {
        firstRun?.let {
            delay(600)
            navController.navigate(if (it) Routes.WELCOME else Routes.HOME) {
                popUpTo(Routes.SPLASH) { inclusive = true }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_app_mark),
                contentDescription = "DevxyzRemote",
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                buildAnnotatedString {
                    append("Devxyz")
                    withStyle(SpanStyle(color = Neon)) { append("Remote") }
                },
                style = MaterialTheme.typography.displaySmall
            )
        }
    }
}
