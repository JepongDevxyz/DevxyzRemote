package com.jepongdevxyz.remote

import android.app.Application
import com.jepongdevxyz.remote.core.Prefs
import com.jepongdevxyz.remote.core.SessionCoordinator

class RemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // Lets this device RECEIVE calls even before any screen opens.
        SessionCoordinator.ensureOnline(this)
    }
}
