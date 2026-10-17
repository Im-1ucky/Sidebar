
package com.example.sidebar.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log

class SidebarService : Service() {

    private var overlayManager: SidebarOverlayManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            else -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification()
                )

                if (Settings.canDrawOverlays(this)) {
                    try {
                        if (overlayManager == null) {
                            overlayManager = SidebarOverlayManager(this)
                        }

                        overlayManager?.show()
                    } catch (exception: Exception) {
                        Log.e(
                            TAG,
                            "Unable to show sidebar overlay",
                            exception
                        )
                    }
                } else {
                    Log.w(TAG, "Overlay permission has not been granted")
                }

                Log.i(TAG, "Service started")
                sendServiceEvent(ACTION_STARTED)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        try {
            overlayManager?.hide()
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to remove sidebar overlay", exception)
        } finally {
            overlayManager = null
        }

        Log.i(TAG, "Service destroyed")
        sendServiceEvent(ACTION_STOPPED)
        super.onDestroy()
    }

    private fun sendServiceEvent(action: String) {
        sendBroadcast(
            Intent(action).setPackage(packageName)
        )
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
