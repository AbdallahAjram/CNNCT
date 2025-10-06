package com.example.cnnct.service

import android.Manifest
import android.R
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.cnnct.calls.CallActionReceiver
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.cnnct.calls.view.CallsActivity
import com.example.cnnct.messaging.FcmTokenManager

class AppFirebaseMessagingService: FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmTokenManager.registerToken(token)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] == "call") {
            val callId = data["callId"] ?: return
            val callerName = data["callerName"] ?: data["callerId"] ?: "Caller"

            // Build incoming call intent
            val incomingIntent = Intent(this, CallsActivity::class.java).apply {
                putExtra("callId", callId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIncoming = PendingIntent.getActivity(this, 0, incomingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            // Accept / Reject broadcast intents
            val accept = Intent(this, CallActionReceiver::class.java).apply {
                action = "ACTION_ACCEPT_CALL"; putExtra("callId", callId)
            }
            val reject = Intent(this, CallActionReceiver::class.java).apply {
                action = "ACTION_REJECT_CALL"; putExtra("callId", callId)
            }
            val acceptPI = PendingIntent.getBroadcast(this, 0, accept, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val rejectPI = PendingIntent.getBroadcast(this, 1, reject, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val n = NotificationCompat.Builder(this, "call_channel")
                .setContentTitle("Incoming call")
                .setContentText(callerName)
                .setSmallIcon(R.drawable.sym_call_incoming)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                .setContentIntent(pendingIncoming)
                .addAction(R.drawable.sym_action_call, "Accept", acceptPI)
                .addAction(R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPI)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()

            NotificationManagerCompat.from(this).notify(callId.hashCode(), n)
        }
    }
}
