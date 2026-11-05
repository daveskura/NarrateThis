package com.example.narratethis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while narrating (so it continues with the screen off) and shows a
 * notification with playback controls.
 */
class NarrationService : LifecycleService() {

    private lateinit var narrator: Narrator

    override fun onCreate() {
        super.onCreate()
        narrator = Narrator.get(this)
        createChannel()
        lifecycleScope.launch {
            narrator.state
                .map { Triple(it.title, it.isPlaying, (it.progress * 100).toInt()) }
                .distinctUntilChanged()
                .collect { (title, playing, pct) -> updateNotification(title, playing, pct) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val s = narrator.state.value
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            buildNotification(s.title, s.isPlaying, (s.progress * 100).toInt()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        // The process may have been killed while the notification was showing.
        narrator.restoreLast()
        when (intent?.action) {
            ACTION_TOGGLE -> narrator.togglePlay()
            ACTION_BACK -> narrator.previousSentence()
            ACTION_FORWARD -> narrator.nextSentence()
            ACTION_STOP -> {
                narrator.stop()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        narrator.pause()
        super.onDestroy()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    private fun updateNotification(title: String, playing: Boolean, pct: Int) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, buildNotification(title, playing, pct))
            }
        }
    }

    private fun buildNotification(title: String, playing: Boolean, pct: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_narration)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(if (playing) "Narrating · $pct%" else "Paused · $pct%")
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_fast_rewind, "Back", action(ACTION_BACK, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                action(ACTION_TOGGLE, 2),
            )
            .addAction(R.drawable.ic_fast_forward, "Forward", action(ACTION_FORWARD, 3))
            .addAction(R.drawable.ic_stop, "Stop", action(ACTION_STOP, 4))
            .build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, NarrationService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Narration", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "narration"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_BACK = "back"
        private const val ACTION_FORWARD = "forward"
        private const val ACTION_STOP = "stop"
    }
}
