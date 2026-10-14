package com.jepongdevxyz.remote.core

import android.content.Context

/**
 * Capability probe for the Firebase backend. Reflection-only on purpose:
 * this file must NOT import any com.google.firebase class, so the app still
 * runs (with signaling disabled) on builds where Firebase isn't bundled.
 */
object Backend {

    /**
     * True when Firebase is on the classpath AND a FirebaseApp is
     * initialized (i.e. google-services.json was packaged correctly).
     */
    fun hasFirebase(context: Context): Boolean {
        return try {
            val clazz = Class.forName("com.google.firebase.FirebaseApp")
            val getApps = clazz.getMethod("getApps", Context::class.java)
            @Suppress("UNCHECKED_CAST")
            val apps = getApps.invoke(null, context.applicationContext) as? List<*>
            !apps.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
