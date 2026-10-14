package com.jepongdevxyz.remote.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jepongdevxyz.remote.core.SessionCoordinator

/**
 * Executes remote-control input on the controlled device.
 *
 * Service configuration (event types, feedback type, canRetrieveWindowContent
 * and canPerformGestures) comes from the @xml/accessibilityservice XML that
 * the manifest meta-data points at (Worker 1 owns both). This class only
 * implements the behavior.
 */
class ControlService : AccessibilityService() {

    companion object {
        /** True when the user has enabled DevxyzRemote in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ControlService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        // DataChannel callbacks arrive on WebRTC threads; marshal to main
        // because dispatchGesture / node actions require it.
        ControlBridge.dispatch = { msg ->
            mainHandler.post {
                if (SessionCoordinator.canControlNow()) {
                    handleControl(msg)
                }
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ControlBridge.dispatch = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handleControl(msg: Map<String, Any?>) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        fun f(key: String): Float = (msg[key] as? Number)?.toFloat() ?: 0f

        when (msg["t"] as? String) {
            "tap" -> {
                val path = Path().apply { moveTo(f("x") * w, f("y") * h) }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                        .build(),
                    null,
                    null
                )
            }
            "swipe" -> {
                val dur = (msg["durMs"] as? Number)?.toLong()?.coerceIn(50, 5000) ?: 400L
                val path = Path().apply {
                    moveTo(f("x1") * w, f("y1") * h)
                    lineTo(f("x2") * w, f("y2") * h)
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                        .build(),
                    null,
                    null
                )
            }
            "scroll" -> {
                // Scroll deltas are normalized; fling from the screen center
                // opposite to the delta direction.
                val dx = f("dx") * w
                val dy = f("dy") * h
                val cx = w / 2f
                val cy = h / 2f
                val path = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx - dx, cy - dy)
                }
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                        .build(),
                    null,
                    null
                )
            }
            "text" -> {
                val v = msg["v"] as? String ?: return
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                try {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            v
                        )
                    }
                    node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } finally {
                    node?.recycle()
                }
            }
            "action" -> when (msg["name"] as? String) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            "clip" -> {
                val v = msg["v"] as? String ?: return
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DevxyzRemote", v))
            }
        }
    }
}

/**
 * Bridge from the WebRTC layer to [ControlService]. The service installs its
 * main-thread handler in [ControlService.onServiceConnected]; the coordinator
 * only ever invokes it after checking [SessionCoordinator.canControlNow].
 */
object ControlBridge {
    /** Incoming control message (a [com.jepongdevxyz.remote.core.Protocol.parse] map). */
    var dispatch: ((Map<String, Any?>) -> Unit)? = null
}
