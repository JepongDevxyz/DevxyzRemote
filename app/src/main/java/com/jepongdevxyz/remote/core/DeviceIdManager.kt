package com.jepongdevxyz.remote.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

/**
 * Owns this device's AnyDesk-style identity: "DEVXYZ-" + 8 chars drawn from
 * "ABCDEFGHJKMNPQRSTUVWXYZ23456789" (ambiguous chars I/L/O/0/1 removed).
 * The id is generated once and persisted in DataStore.
 */
object DeviceIdManager {

    const val PREFIX = "DEVXYZ-"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LEN = 8
    private val ID_REGEX = Regex("^DEVXYZ[$ALPHABET]{$CODE_LEN}\$")

    private val Context.deviceStore by preferencesDataStore(name = "devxyz_device")
    private val KEY_ID = stringPreferencesKey("device_id")
    private val random = SecureRandom()

    /** Returns the persisted id, generating and storing one on first run. */
    suspend fun getDeviceId(context: Context): String {
        val app = context.applicationContext
        val existing = app.deviceStore.data.map { it[KEY_ID].orEmpty() }.first()
        if (isValidDeviceId(existing)) return canonical(existing)
        val fresh = generate()
        app.deviceStore.edit { it[KEY_ID] = fresh }
        return fresh
    }

    /** True for "DEVXYZ-XXXXXXXX" (also tolerates the "DEVXYZ-XXXX-XXXX" display form). */
    fun isValidDeviceId(id: String): Boolean {
        val norm = id.filter { it.isLetterOrDigit() }.uppercase()
        return ID_REGEX.matches(norm)
    }

    /**
     * Normalizes raw user input: strips non-alphanumerics, uppercases, keeps
     * at most 8 code chars after the prefix, and renders as "DEVXYZ-XXXX-XXXX".
     */
    fun formatInput(raw: String): String {
        var s = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (s.startsWith("DEVXYZ")) s = s.removePrefix("DEVXYZ")
        s = s.take(CODE_LEN)
        val grouped = if (s.length > 4) s.substring(0, 4) + "-" + s.substring(4) else s
        return PREFIX + grouped
    }

    /** Black-on-white QR code of the device id, sizePx x sizePx. */
    fun qrBitmap(id: String, sizePx: Int): Bitmap {
        require(sizePx > 0) { "sizePx must be positive" }
        val matrix = QRCodeWriter().encode(id, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun generate(): String {
        val sb = StringBuilder(PREFIX)
        repeat(CODE_LEN) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private fun canonical(id: String): String {
        val s = id.filter { it.isLetterOrDigit() }.uppercase().removePrefix("DEVXYZ").take(CODE_LEN)
        return PREFIX + s
    }
}
