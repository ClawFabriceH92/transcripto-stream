package com.transcripto.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * État global de l'enregistrement — partagé entre le ViewModel et le service
 * pour que la notification suive le chrono même écran éteint.
 */
object RecordingState {
    @Volatile var isActive: Boolean = false
    @Volatile var isPaused: Boolean = false
    @Volatile var elapsedSec: Long = 0L
}

/**
 * Service foreground : maintient l'enregistrement actif quand l'écran est éteint
 * ou que l'app passe en arrière-plan. Notification avec chrono.
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val notifManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        RecordingState.isActive = false
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Enregistrement",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indique que l'enregistrement est en cours"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        val mm = RecordingState.elapsedSec / 60
        val ss = RecordingState.elapsedSec % 60
        val label = if (RecordingState.isPaused) "⏸ En pause" else "● Enregistrement en cours"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Transcripto Stream")
            .setContentText("$label — %02d:%02d".format(mm, ss))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        notifManager.notify(NOTIF_ID, buildNotification())
    }
}
