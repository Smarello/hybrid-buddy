package it.techreloaded.hybridbuddy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a live acquisition running with the app in background or the screen off:
 * foreground service with a persistent notification and a "Termina" action, plus a partial wake lock.
 */
class AcquisitionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (intent?.action == ACTION_STOP || address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hybridbuddy:acquisition")
                .apply { acquire() }
        }
        AcquisitionController.startLive(this, address, intent.getStringExtra(EXTRA_NAME) ?: "OBDLink")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AcquisitionController.stopLive()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Acquisizione", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AcquisitionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Hybrid Buddy · acquisizione attiva")
            .setContentText("Lettura dall'OBDLink LX in corso")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Termina", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "acquisition"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, address: String, name: String) {
            context.startForegroundService(
                Intent(context, AcquisitionService::class.java).putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_NAME, name),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AcquisitionService::class.java))
        }
    }
}
