package com.macsstop.dialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the SIP account registered in the background so incoming calls ring. */
class SipService : Service() {

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_CALLS = "incoming_calls"
        private const val ID_STATUS = 1
        private const val ID_INCOMING = 2
    }

    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        SipManager.init(this)
        createChannels()
        startInForeground("Starting…")
        SipManager.onRegChanged = { st ->
            updateStatus(
                when (st) {
                    "Ok", "Refreshing" -> "Registered — ready for calls"
                    "Progress" -> "Registering…"
                    "Failed" -> "Registration failed"
                    else -> "Not registered"
                }
            )
        }
        SipManager.onIncoming = { who -> showIncoming(who) }
        SipManager.onCallsChanged = { if (!SipManager.hasRingingCall()) nm.cancel(ID_INCOMING) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        SipManager.onRegChanged = null
        SipManager.onIncoming = null
        SipManager.onCallsChanged = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels() {
        val status = NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW)
        val calls = NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // the SIP engine plays the ringtone
            enableVibration(true)
        }
        nm.createNotificationChannel(status)
        nm.createNotificationChannel(calls)
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("MACS STOP Dialer")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun startInForeground(text: String) {
        val n = statusNotification(text)
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
            ServiceCompat.startForeground(this, ID_STATUS, n, type)
        } catch (e: Exception) {
            Log.e("MacsSip", "startForeground failed", e)
            try { startForeground(ID_STATUS, n) } catch (e2: Exception) { Log.e("MacsSip", "fallback failed", e2) }
        }
    }

    private fun updateStatus(text: String) {
        try { nm.notify(ID_STATUS, statusNotification(text)) } catch (e: Exception) {}
    }

    private fun showIncoming(who: String) {
        val pi = openAppIntent()
        val n = NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(who)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        try { nm.notify(ID_INCOMING, n) } catch (e: Exception) {}
        // Bring the app forward right away when allowed (screen on / app recently used).
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) {}
    }
}
