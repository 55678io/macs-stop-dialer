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
import androidx.core.app.Person
import androidx.core.app.ServiceCompat

/** Keeps the SIP account registered in the background so incoming calls ring. */
class SipService : Service() {

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_CALLS = "incoming_calls_v2"
        private const val CH_CALLS_OLD = "incoming_calls"
        const val ACTION_ANSWER = "com.macsstop.dialer.ANSWER"
        const val ACTION_DECLINE = "com.macsstop.dialer.DECLINE"
        const val EXTRA_CALL = "call_id"
        private const val ID_STATUS = 1
        private const val ID_INCOMING = 2
        private const val ID_MISSED = 3
        private const val CH_MISSED = "missed_calls"
        /** Missed calls since the app was last opened (the notification shows the count). */
        @Volatile var missedCount = 0
        fun clearMissed(ctx: android.content.Context) {
            missedCount = 0
            try { ctx.getSystemService(NotificationManager::class.java).cancel(ID_MISSED) } catch (e: Exception) {}
        }
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
        SipManager.onIncoming = { id, who -> showIncoming(id, who) }
        SipManager.onCallsChanged = { if (!SipManager.hasRingingCall()) nm.cancel(ID_INCOMING) }
        SipManager.onMissed = { who -> showMissed(who) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        SipManager.onRegChanged = null
        SipManager.onIncoming = null
        SipManager.onCallsChanged = null
        SipManager.onMissed = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels() {
        val status = NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW)
        // the app plays the ringtone and vibration itself (RingtonePlayer), so the channel stays quiet
        try { nm.deleteNotificationChannel(CH_CALLS_OLD) } catch (e: Exception) {}
        val calls = NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
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

    private fun showMissed(who: String) {
        missedCount++
        val n = NotificationCompat.Builder(this, CH_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(if (missedCount > 1) "$missedCount missed calls" else "Missed call")
            .setContentText(who)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try { nm.notify(ID_MISSED, n) } catch (e: Exception) {}
    }

    /**
     * Incoming call: a full-screen call notification with Answer / Decline.
     * Answer opens the call screen on top of the lock screen, so the agent can talk without unlocking.
     */
    private fun showIncoming(id: String, who: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val full = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val answer = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_CALL, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val decline = PendingIntent.getBroadcast(this, 3,
            Intent(this, CallActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_CALL, id), flags)
        val caller = Person.Builder().setName(who).setImportant(true).build()
        fun base() = NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(who)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
        val n = try {
            base().setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer)).build()
        } catch (e: Exception) {
            Log.w("MacsSip", "call style", e)
            base().addAction(android.R.drawable.sym_call_missed, "Decline", decline)
                .addAction(android.R.drawable.sym_call_incoming, "Answer", answer).build()
        }
        try { nm.notify(ID_INCOMING, n) } catch (e: Exception) { Log.e("MacsSip", "incoming notification", e) }