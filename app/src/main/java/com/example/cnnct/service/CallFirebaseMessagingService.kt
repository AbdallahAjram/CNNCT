// app/src/main/java/com/example/cnnct/service/AppFirebaseMessagingService.kt
package com.example.cnnct.service

import android.Manifest
import android.R
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.cnnct.R as AppR
import com.example.cnnct.calls.CallActionReceiver
import com.example.cnnct.calls.view.CallsActivity
import com.example.cnnct.notifications.MuteStore
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AppFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Your existing token registrar
        com.example.cnnct.messaging.FcmTokenManager.registerToken(token)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        android.util.Log.d("FCM_RAW", "Notification payload = ${message.notification}, Data = ${message.data}")

        val data = message.data
        when (data["type"]) {
            "call" -> handleCallPush(data)
            "chat" -> handleChatPush(data)     // ✅ add chat handling with mute guard
            // ignore unknown types
        }
    }

    // ---------------- CALLS ----------------
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleCallPush(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: data["callerId"] ?: "Caller"

        // Build incoming call intent
        val incomingIntent = Intent(this, CallsActivity::class.java).apply {
            putExtra("callId", callId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIncoming = PendingIntent.getActivity(
            this, 0, incomingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept / Reject broadcast intents
        val accept = Intent(this, CallActionReceiver::class.java).apply {
            action = "ACTION_ACCEPT_CALL"; putExtra("callId", callId)
        }
        val reject = Intent(this, CallActionReceiver::class.java).apply {
            action = "ACTION_REJECT_CALL"; putExtra("callId", callId)
        }
        val acceptPI = PendingIntent.getBroadcast(
            this, 0, accept,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPI = PendingIntent.getBroadcast(
            this, 1, reject,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, "call_channel")
            .setContentTitle(getString(AppR.string.app_name))
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

    // ---------------- CHATS ----------------
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleChatPush(data: Map<String, String>) {
        val chatId = data["chatId"] ?: return
        val title  = data["title"] ?: getString(AppR.string.app_name)
        val body   = data["body"] ?: ""

        // ⛔️ Per-chat mute guard (fast path: cached)
        if (MuteStore.isMuted(chatId)) return

        // If cache not warm (app cold start), do a quick Firestore check.
        // onMessageReceived runs on a background thread, so blocking fetch is OK.
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null && looksCacheMiss(chatId)) {
            try {
                val ref = FirebaseFirestore.getInstance()
                    .collection("userChats").document(user.uid)
                    .collection("chats").document(chatId)
                val snap = Tasks.await(ref.get())
                val untilMs = snap.getTimestamp("mutedUntil")?.toDate()?.time
                if (untilMs != null && System.currentTimeMillis() < untilMs) {
                    return // muted → drop
                }
            } catch (_: Exception) {
                // network error? fall through and notify to avoid message loss
            }
        }

        // Not muted → show notification (simple example)
        val builder = NotificationCompat.Builder(this, "messages")
            .setSmallIcon(AppR.drawable.defaultpp) // ensure you have this
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        NotificationManagerCompat.from(this)
            .notify(chatId.hashCode(), builder.build())
    }

    private fun looksCacheMiss(chatId: String): Boolean {
        // If the store says NOT muted, it could still be correct. We only attempt fetch
        // to cover the cold start case quickly; this cheap heuristic always tries once.
        // You can make this smarter by tracking a "lastSyncedAt".
        return true
    }
}
