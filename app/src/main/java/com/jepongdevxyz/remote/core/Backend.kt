package com.jepongdevxyz.remote.core

import android.content.Context

/**
 * Capability probe for the Firebase backend. Reflection-only on purpose:
 * this file must NOT import any com.google.firebase class, so the app still
 * runs (with signaling disabled) on builds where Firebase isn't bundled.
 */
object Backend {

    /**
     * True when Firebase is on the classpath (i.e. google-services.json was
     * packaged correctly and the Firebase SDK initialized).
     */
    fun hasFirebase(context: Context): Boolean {
        return try {
            Class.forName("com.google.firebase.FirebaseApp")
            true
        } catch (e: Exception) {
            false
        }
    }
}
