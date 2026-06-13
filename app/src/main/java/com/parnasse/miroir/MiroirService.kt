package com.parnasse.miroir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * MiroirService — Foreground Service qui maintient le processus en vie
 * et affiche la notification "Capture active".
 *
 * Le service n'a pas d'interface. La capture est gérée par CaptureActivity
 * (la fenêtre focus reçoit les événements stylo). Ce service sert à :
 * - Afficher une notification persistante
 * - Maintenir le processus en vie si l'Activity est recyclée
 * - (Futur) Recevoir les strokes via Binder/local broadcast
 *
 * Arrêt : notification dismiss ou `adb shell am force-stop`
 */
class MiroirService : Service() {

    companion object {
        private const val TAG = "Miroir/Service"
        private const val CHANNEL_ID = "miroir_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MiroirService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MiroirService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service créé")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== MIROIR SERVICE DÉMARRÉ ===")

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Notification affichée — capture active")

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "=== MIROIR SERVICE ARRÊTÉ ===")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ──────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Miroir Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Capture stylo Boox active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Miroir")
            .setContentText("Capture stylo active")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }
}
