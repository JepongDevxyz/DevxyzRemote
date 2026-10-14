package com.jepongdevxyz.remote.core

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * App settings backed by DataStore ("devxyz_prefs").
 *
 * The Flows are context-free vals, so [init] must run once — call
 * Prefs.init(application) in Application.onCreate. SessionCoordinator and
 * SessionRepository also call init() defensively on every public entry that
 * receives a Context, so the app keeps working even if the Application hook
 * is missed.
 */
object Prefs {

    private val Context.store by preferencesDataStore(name = "devxyz_prefs")

    @Volatile
    private var appContext: Context? = null

    /** Call once from Application.onCreate (idempotent). */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun ctx(): Context = appContext
        ?: error("Prefs.init(context) has not been called. Call Prefs.init(application) in Application.onCreate.")

    private val KEY_FIRST_RUN = booleanPreferencesKey("first_run")
    private val KEY_ONLINE = booleanPreferencesKey("online")
    private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_QUALITY = stringPreferencesKey("quality")

    val firstRun: Flow<Boolean>
        get() = ctx().store.data.map { it[KEY_FIRST_RUN] ?: true }

    suspend fun setFirstRunDone(c: Context) {
        init(c)
        c.applicationContext.store.edit { it[KEY_FIRST_RUN] = false }
    }

    val online: Flow<Boolean>
        get() = ctx().store.data.map { it[KEY_ONLINE] ?: true }

    suspend fun setOnline(c: Context, v: Boolean) {
        init(c)
        c.applicationContext.store.edit { it[KEY_ONLINE] = v }
    }

    val deviceName: Flow<String>
        get() = ctx().store.data.map { it[KEY_DEVICE_NAME] ?: Build.MODEL }

    suspend fun setDeviceName(c: Context, v: String) {
        init(c)
        c.applicationContext.store.edit { it[KEY_DEVICE_NAME] = v }
    }

    val themeMode: Flow<String>
        get() = ctx().store.data.map { it[KEY_THEME_MODE] ?: "SYSTEM" }

    suspend fun setThemeMode(c: Context, v: String) {
        init(c)
        c.applicationContext.store.edit { it[KEY_THEME_MODE] = v }
    }

    val quality: Flow<String>
        get() = ctx().store.data.map { it[KEY_QUALITY] ?: "1080p" }

    suspend fun setQuality(c: Context, v: String) {
        init(c)
        c.applicationContext.store.edit { it[KEY_QUALITY] = v }
        cachedQuality = v
    }

    /**
     * Last-known quality for hot paths that cannot suspend (CaptureService).
     * Primed from DataStore by [prime]; safe fallback "1080p".
     */
    var cachedQuality: String = "1080p"
        internal set

    /** Reads the current DataStore values into the in-memory cache. */
    internal suspend fun prime(c: Context) {
        init(c)
        cachedQuality = try {
            quality.first()
        } catch (_: Exception) {
            "1080p"
        }
    }
}
