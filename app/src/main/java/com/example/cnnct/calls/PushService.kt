// app/src/main/java/com/example/cnnct/calls/PushService.kt
package com.example.cnnct.calls

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.example.cnnct.R
import com.example.cnnct.chat.view.ChatActivity
import com.example.cnnct.notifications.ForegroundTracker
import com.example.cnnct.notifications.NotificationHelper
import com.example.cnnct.notifications.NotificationsStore
import com.example.cnnct.notifications.TokenRegistrar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try { TokenRegistrar.upsertToken(applicationContext, token) } catch (_: Exception) {}
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        NotificationHelper.ensureChannels(this)

        val data = remoteMessage.data
        val type = data["type"] ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return

        when (type) {

            // ----------------------------- MESSAGES -----------------------------
            "message" -> {
                val chatId = data["chatId"] ?: return
                val senderName = data["senderName"] ?: "New message"
                val text = data["text"] ?: ""
                val senderPhotoUrl = data["senderPhotoUrl"] // may be null

                // Suppress if chat is foreground
                if (ForegroundTracker.getCurrentChat() == chatId) return

                // Append + build history
                val now = System.currentTimeMillis()
                NotificationsStore.appendMessage(this, chatId, senderName, text, now)
                val history = NotificationsStore.history(this, chatId)

                val open = PendingIntent.getActivity(
                    this, chatId.hashCode(),
                    Intent(this, ChatActivity::class.java)
                        .putExtra("chatId", chatId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Build MessagingStyle without using RestrictedApi setters
                val me = Person.Builder()
                    .setName(user.displayName ?: user.email ?: "Me")
                    .build()

                val style = NotificationCompat.MessagingStyle(me)
                for (m in history) {
                    val sender = Person.Builder().setName(m.sender).build()
                    val msg = NotificationCompat.MessagingStyle.Message(m.text, m.ts, sender)
                    style.addMessage(msg)
                }

                val builder = NotificationHelper
                    .builder(this, NotificationHelper.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_message)
                    .setContentTitle(senderName) // title here instead of conversationTitle
                    .setStyle(style)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setGroup("chat_$chatId")

                if (!senderPhotoUrl.isNullOrBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try { loadBitmap(senderPhotoUrl)?.let { builder.setLargeIcon(it) } } catch (_: Exception) {}
                        NotificationManagerCompat.from(this@PushService).notify(chatId.hashCode(), builder.build())
                    }
                } else {
                    NotificationManagerCompat.from(this).notify(chatId.hashCode(), builder.build())
                }
            }

            // ----------------------------- CALLS -----------------------------
            "incoming_call" -> {
                val callId = data["callId"] ?: return
                val callerId = data["callerId"] ?: return
                val callerName = data["callerDisplayName"] ?: "Incoming call"

                val full = PendingIntent.getActivity(
                    this, callId.hashCode(),
                    Intent(this, IncomingCallActivity::class.java)
                        .putExtra("callId", callId)
                        .putExtra("callerId", callerId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Activity-based intents for actions to avoid BroadcastReceiver launch issues
                val accept = PendingIntent.getActivity(
                    this, ("acc_$callId").hashCode(),
                    Intent(this, CallActionActivity::class.java)
                        .putExtra(CallActionActivity.EXTRA_ACTION, CallActionActivity.ACTION_ACCEPT)
                        .putExtra(CallActionActivity.EXTRA_CALL_ID, callId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val decline = PendingIntent.getActivity(
                    this, ("dec_$callId").hashCode(),
                    Intent(this, CallActionActivity::class.java)
                        .putExtra(CallActionActivity.EXTRA_ACTION, CallActionActivity.ACTION_DECLINE)
                        .putExtra(CallActionActivity.EXTRA_CALL_ID, callId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notif = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_CALLS)
                    .setSmallIcon(R.drawable.ic_call)
                    .setColor(Color.parseColor("#10B981"))
                    .setContentTitle("Incoming call")
                    .setContentText("from $callerName")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setFullScreenIntent(full, true)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .addAction(R.drawable.ic_call_end, "Decline", decline)
                    .addAction(R.drawable.ic_call, "Accept", accept)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()

                NotificationManagerCompat.from(this).notify(CALL_NOTIF_ID, notif)
            }

            // Auto-cancel ring when status changes elsewhere
            "call_update" -> {
                val status = data["status"] ?: return
                if (status != "ringing") {
                    NotificationManagerCompat.from(this).cancel(CALL_NOTIF_ID)
                }
            }
        }
    }

    private suspend fun loadBitmap(url: String): Bitmap? {
        val loader = ImageLoader(this)
        val request = ImageRequest.Builder(this)
            .data(url)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .build()
        return (loader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }

    companion object {
        const val CALL_NOTIF_ID = 9981
    }
}
