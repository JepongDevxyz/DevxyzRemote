package com.jepongdevxyz.remote.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import com.jepongdevxyz.remote.core.SessionCoordinator
import org.webrtc.ScreenCapturerAndroid

/**
 * Foreground service that captures this device's screen into the WebRTC
 * session (controlled side, and the sharing half of the loopback test).
 *
 * The MediaProjection permission result (resultCode + data) comes from the
 * UI via [start]; it is handed to [ScreenCapturerAndroid], which builds its
 * own MediaProjection and VirtualDisplay internally and feeds the capturer
 * registered on the sharing RtcManager.
 *
 * Manifest must declare foregroundServiceType="mediaProjection" (Worker 1).
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.jepongdevxyz.remote.action.STOP_CAPTURE"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA = "extra_data"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "screen_share"

        /**
         * Capture geometry, set by SessionCoordinator from Prefs.quality
         * before [start] is called.
         */
        @Volatile var captureWidth: Int = 1280
        @Volatile var captureHeight: Int = 720
        @Volatile var captureFps: Int = 30

        /** Starts screen sharing. [data] is the MediaProjection result Intent. */
        fun start(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    private var capturer: ScreenCapturerAndroid? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SessionCoordinator.endSession()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode =
            intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        val rtc = SessionCoordinator.getSharingRtc()
        if (resultCode != Activity.RESULT_OK || data == null || rtc == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }

        try {
            val capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        // User revoked capture from the system UI — hang up.
                        SessionCoordinator.endSession()
                    }
                }
            )
            this.capturer = capturer
            rtc.startCapture(capturer, captureWidth, captureHeight, captureFps)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            capturer?.dispose()
        } catch (_: Exception) {
        }
        capturer = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Sharing your screen")
            .setContentText("DevxyzRemote")
            // Red-dot style persistent indicator (framework drawable; Worker 1
            // may swap in a branded small icon from res/).
            .setSmallIcon(android.R.drawable.presence_video_busy)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopIntent
                ).build()
            )
            .build()
    }
}
