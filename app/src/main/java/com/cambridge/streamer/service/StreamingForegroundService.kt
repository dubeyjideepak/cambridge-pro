package com.cambridge.streamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cambridge.streamer.MainActivity
import com.cambridge.streamer.R

/**
 * Foreground Service — keeps the broadcast alive when the phone screen locks.
 *
 * On Android 8+ any background process risks being killed. Running as a
 * foreground service with a persistent notification prevents the OS from
 * terminating our RTSP/RTMP streaming pipeline.
 *
 * Usage:
 *   Start:  StreamingForegroundService.start(context, "🔴 LIVE → OBS Studio")
 *   Stop:   StreamingForegroundService.stop(context)
 */
class StreamingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID   = "cambridgepro_live"
        private const val NOTIF_ID     = 1001
        private const val EXTRA_LABEL  = "stream_label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, StreamingForegroundService::class.java)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Service Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Partial wake lock: keeps CPU alive, screen can turn off
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CamBridgePro::StreamingWakeLock"
            ).apply { acquire(6 * 60 * 60 * 1000L) } // max 6 hours
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle the notification "Stop Stream" button
        if (intent?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "🔴 Live Broadcast Active"
        startForeground(NOTIF_ID, buildNotification(label))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CamBridge Pro — Live Stream",
            NotificationManager.IMPORTANCE_LOW   // no sound, no heads-up
        ).apply {
            description = "Keeps your live broadcast running in the background"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(label: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingForegroundService::class.java).apply {
                action = "STOP"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("CamBridge Pro")
            .setContentText(label)
            .setSubText("Tap to return to app")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Stream", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
