package com.macsstop.dialer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Decline button on the incoming-call notification (works from the lock screen). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SipService.ACTION_DECLINE) return
        SipManager.init(context)
        val id = intent.getStringExtra(SipService.EXTRA_CALL) ?: return
        SipManager.decline(id)
        try { context.getSystemService(NotificationManager::class.java).cancel(2) } catch (e: Exception) {}
    }
}
