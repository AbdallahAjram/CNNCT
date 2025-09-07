// file: PushService.kt
package com.example.cnnct.calls

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.cnnct.R

class PushService : FirebaseMessagingService() {
    companion object {
        const val CHANNEL_ID = "calls_channel"
        const val NOTIF_ID = 1001
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Expect data payload: callId, callerId, channelId
        val data = message.data
        val callId = data["callId"] ?: return
        val callerId = data["callerId"] ?: "Unknown"
        val channelId = data["channelId"] ?: ""

        createChannelIfNeeded()

        // Full-screen intent to open IncomingCallActivity
        val incoming = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("callId", callId)
            putExtra("callerId", callerId)
            putExtra("channelId", channelId)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            incoming,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Accept / Reject actions as broadcast intents
        val acceptIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = "ACTION_ACCEPT_CALL"
            putExtra("callId", callId)
        }
        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = "ACTION_REJECT_CALL"
            putExtra("callId", callId)
        }
        val acceptPending = PendingIntent.getBroadcast(this, callId.hashCode() + 1, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val rejectPending = PendingIntent.getBroadcast(this, callId.hashCode() + 2, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call) // your icon
            .setContentTitle("Incoming call")
            .setContentText("Call from $callerId")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(NotificationCompat.Action(0, "Reject", rejectPending))
            .addAction(NotificationCompat.Action(0, "Accept", acceptPending))
            .setOngoing(true)
            .setColor(Color.WHITE)

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIF_ID + (callId.hashCode() and 0xffff), builder.build())
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming call notifications"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(chan)
        }
    }
}
