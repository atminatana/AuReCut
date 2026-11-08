package org.aurecut.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.aurecut.app.R

/**
 * Keeps the recording alive while the app is minimized or the screen is locked.
 * Android requires a visible notification for a microphone foreground service.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RecordingController.stop()
            return START_NOT_STICKY
        }
        if (intent == null || RecordingController.recorder != null) return START_NOT_STICKY

        val config = RecConfig(
            source = RecSource.values().getOrElse(intent.getIntExtra(EXTRA_SOURCE, 0)) { RecSource.MIC },
            sampleRate = intent.getIntExtra(EXTRA_RATE, AudioCaps.DEFAULT_RATE),
            channels = intent.getIntExtra(EXTRA_CHANNELS, 1).coerceIn(1, 2),
        )

        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: SecurityException) {
            fail(RecFailure.PERMISSION)
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            fail(RecFailure.START)
            return START_NOT_STICKY
        }

        val recorder = Recorder(WorkDir.newFile(this, "rec", "pcm"), config) { state ->
            RecordingController.publish(state)
            if (state !is RecState.Active) {
                RecordingController.recorder = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        val failure = recorder.start()
        if (failure != null) {
            fail(failure)
        } else {
            RecordingController.recorder = recorder
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RecordingController.recorder?.stop() // the system is ending the service, finish the file properly
        super.onDestroy()
    }

    private fun fail(reason: RecFailure) {
        RecordingController.publish(RecState.Failed(reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, flags) }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), flags,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RATE = "rate"
        const val EXTRA_CHANNELS = "channels"
        const val ACTION_STOP = "org.aurecut.app.STOP_RECORDING"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}
