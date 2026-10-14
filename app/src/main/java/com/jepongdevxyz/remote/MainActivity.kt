package com.jepongdevxyz.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.jepongdevxyz.remote.ui.nav.NavGraph
import com.jepongdevxyz.remote.ui.theme.DevxyzRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            DevxyzRemoteTheme {
                NavGraph()
            }
        }
    }
}
