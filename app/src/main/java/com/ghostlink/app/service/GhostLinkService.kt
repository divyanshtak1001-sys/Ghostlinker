package com.ghostlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ghostlink.app.MainActivity
import com.ghostlink.app.R

/**
 * GhostLinkService — Optional foreground service for background session maintenance.
 *
 * This service is only started when the user explicitly enables
 * "Keep Active Session Connected in Background" in Settings.
 *
 * Privacy requirements enforced:
 * - Notification contains NO message content, peer alias, or connection details.
 * - No messages are written to disk during service operation.
 * - All session data remains volatile in-memory only.
 * - Service stops cleanly when the user disconnects or the session ends.
 *
 * Limitation note: Android and device manufacturers can terminate foreground
 * services under battery restrictions. This service does not guarantee
 * uninterrupted background operation on all devices.
 */
class GhostLinkService : Service() {

    companion object {
        private const val TAG = "GhostLinkService"
        private const val CHANNEL_ID = "ghostlink_session_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.ghostlink.app.action.START_SESSION"
        const val ACTION_STOP  = "com.ghostlink.app.action.STOP_SESSION"

        fun startSession(context: Context) {
            val intent = Intent(context, GhostLinkService::class.java)
                .apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopSession(context: Context) {
            context.stopService(Intent(context, GhostLinkService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Starting foreground session service")
                // startForeground throws on API 34+ when the service is started
                // without a matching foregroundServiceType permission, and on
                // API 33+ when POST_NOTIFICATIONS is missing. Catch it so a
                // refused start stops the service instead of crashing the app.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            buildSessionNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, buildSessionNotification())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enter foreground: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping foreground session service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GhostLinkService destroyed — session data cleared by ViewModel")
        // Session data clearing is handled by GhostViewModel.endSession()
        // This service holds no persistent session data itself.
    }

    /**
     * Builds a generic ongoing notification.
     *
     * Privacy: contains NO message content, peer alias, invitation code,
     * or any other sensitive session information.
     */
    private fun buildSessionNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("GhostLink")
            .setContentText("Secure session active")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // No BigTextStyle — keeps notification minimal and privacy-respecting
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GhostLink Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indicates an active GhostLink secure session"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
