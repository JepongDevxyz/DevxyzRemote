package com.jepongdevxyz.remote.core

import android.content.Context

/**
 * Capability probe for the Firebase backend. Reflection-only on purpose:
 * this file must NOT import any com.google.firebase class, so the app still
 * runs (with signaling disabled) on builds where Firebase isn't bundled.
 */
object Backend {

    /**
     * True when Firebase is on the classpath AND initialized (i.e.
     * google-services.json was packaged correctly). Checking the class alone
     * is not enough — the SDK is bundled even without configuration.
     */
    fun hasFirebase(context: Context): Boolean {
        return try {
            val clazz = Class.forName("com.google.firebase.FirebaseApp")
            // Throws IllegalStateException when not initialized.
            clazz.getMethod("getInstance").invoke(null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
