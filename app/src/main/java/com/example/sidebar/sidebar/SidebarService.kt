
package com.example.sidebar.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

class SidebarService : Service() {

    private var overlayManager: SidebarOverlayManager? = null
    private var screenReceiverRegistered = false
    private var overlayVisible = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> showOverlay()
                Intent.ACTION_SCREEN_OFF -> hideOverlay()
            }
        }
    }

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
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
                putBoolean(KEY_RUNNING, false)
                putBoolean(KEY_VISIBLE, false)
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_RUNNING, true)
        }

        registerScreenReceiver()

        val powerManager =
            getSystemService(POWER_SERVICE) as PowerManager

        if (powerManager.isInteractive) {
            showOverlay()
        } else {
            hideOverlay()
        }

        Log.i(TAG, "Service started")
        sendServiceEvent(ACTION_STARTED)

        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission has not been granted")
            setOverlayVisible(false)
            return
        }

        try {
            if (overlayManager == null) {
                overlayManager = SidebarOverlayManager(this) {
                    val screenshotIntent = Intent(
                        this,
                        com.example.sidebar.screenshot
                            .ScreenshotConsentActivity::class.java
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    startActivity(screenshotIntent)
                }
            }

            overlayManager?.let { manager ->
                manager.setOnOverlayAttachmentChanged { attached ->
                    Log.i(TAG, "Overlay attachment changed: $attached")
                    setOverlayVisible(attached)
                }

                manager.show()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to show sidebar overlay", exception)
            setOverlayVisible(false)
        }
    }

    private fun hideOverlay() {
        try {
            overlayManager?.hide()
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to hide sidebar overlay", exception)
        } finally {
            setOverlayVisible(false)
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible

        getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
            .edit {
                putBoolean(KEY_VISIBLE, visible)
            }

        sendServiceEvent(ACTION_VISIBILITY_CHANGED)

        Log.i(TAG, "Sidebar visibility updated: $visible")
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            runCatching {
                unregisterReceiver(screenReceiver)
            }.onFailure {
                Log.w(TAG, "Unable to unregister screen receiver", it)
            }

            screenReceiverRegistered = false
        }

        hideOverlay()
        overlayManager = null

        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_RUNNING, false)
            putBoolean(KEY_VISIBLE, false)
        }

        sendServiceEvent(ACTION_STOPPED)
        super.onDestroy()
    }

    private fun sendServiceEvent(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sidebar service",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sidebar")
            .setContentText("Sidebar service is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "SidebarService"
        private const val CHANNEL_ID = "sidebar_service"
        private const val NOTIFICATION_ID = 1001
        private const val STATE_PREFS = "sidebar_service_state"
        private const val KEY_RUNNING = "running"
        private const val KEY_VISIBLE = "visible"

        const val ACTION_START = "com.example.sidebar.ACTION_START"
        const val ACTION_STARTED = "com.example.sidebar.ACTION_STARTED"
        const val ACTION_STOP = "com.example.sidebar.ACTION_STOP"
        const val ACTION_STOPPED = "com.example.sidebar.ACTION_STOPPED"
        const val ACTION_VISIBILITY_CHANGED =
            "com.example.sidebar.ACTION_VISIBILITY_CHANGED"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SidebarService::class.java)
                    .setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SidebarService::class.java)
                    .setAction(ACTION_STOP)
            )
        }

        fun isRunning(context: Context): Boolean {
            return context.getSharedPreferences(
                STATE_PREFS,
                MODE_PRIVATE
            ).getBoolean(KEY_RUNNING, false)
        }

        fun isOverlayVisible(context: Context): Boolean {
            return context.getSharedPreferences(
                STATE_PREFS,
                MODE_PRIVATE
            ).getBoolean(KEY_VISIBLE, false)
        }
    }
}
