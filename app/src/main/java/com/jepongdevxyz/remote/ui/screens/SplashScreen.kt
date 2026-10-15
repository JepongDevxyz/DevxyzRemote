package com.jepongdevxyz.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import com.jepongdevxyz.remote.R
import com.jepongdevxyz.remote.core.Prefs
import com.jepongdevxyz.remote.ui.nav.Routes
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(navController: NavController) {
    val firstRun by Prefs.firstRun.collectAsState(initial = null)

    LaunchedEffect(firstRun) {
        firstRun?.let {
            delay(1200)
            navController.navigate(if (it) Routes.WELCOME else Routes.HOME) {
                popUpTo(Routes.SPLASH) { inclusive = true }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.splash_screen),
            contentDescription = "DevxyzRemote",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
