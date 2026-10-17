
package com.example.sidebar.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

class SidebarService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): SidebarService = this@SidebarService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")

                stopForeground(STOP_FOREGROUND_REMOVE)

                // onDestroy() sends ACTION_STOPPED.
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            else -> {
                // A foreground-service start must be promoted promptly.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification()
                )

                Log.i(TAG, "Service started in foreground")

                sendServiceEvent(ACTION_STARTED)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        sendServiceEvent(ACTION_STOPPED)
        super.onDestroy()
    }

    private fun sendServiceEvent(action: String) {
        val intent = Intent(action).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sidebar service",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sidebar")
            .setContentText("Sidebar service is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SidebarService"
        private const val CHANNEL_ID = "sidebar_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START =
            "com.example.sidebar.ACTION_START"

        const val ACTION_STARTED =
            "com.example.sidebar.ACTION_STARTED"

        const val ACTION_STOP =
            "com.example.sidebar.ACTION_STOP"

        const val ACTION_STOPPED =
            "com.example.sidebar.ACTION_STOPPED"

        fun start(context: Context) {
            val intent = Intent(context, SidebarService::class.java)
                .setAction(ACTION_START)

            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SidebarService::class.java)
                .setAction(ACTION_STOP)

            context.startService(intent)
        }
    }
}
