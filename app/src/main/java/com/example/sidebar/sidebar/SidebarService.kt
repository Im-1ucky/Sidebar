
package com.example.sidebar.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class SidebarService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Sidebar service created")
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        Log.i(TAG, "Received action: ${intent?.action}, startId: $startId")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                Log.i(TAG, "Restart requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Sidebar service started")

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Sidebar service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sidebar service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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

        const val ACTION_START = "com.example.sidebar.ACTION_START"
        const val ACTION_STOP = "com.example.sidebar.ACTION_STOP"

        const val ACTION_RESTART = "com.example.sidebar.ACTION_RESTART"

        fun start(context: Context) {
            val intent = Intent(context, SidebarService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SidebarService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
