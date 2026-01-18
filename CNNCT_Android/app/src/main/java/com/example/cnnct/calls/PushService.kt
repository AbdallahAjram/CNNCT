package com.example.cnnct.calls

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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
import com.example.cnnct.notifications.MuteStore
import com.example.cnnct.notifications.NotificationHelper
import com.example.cnnct.notifications.NotificationsStore
import com.example.cnnct.notifications.SettingsCache
import com.example.cnnct.notifications.TokenRegistrar
import com.google.android.gms.tasks.Tasks // <-- use Tasks.await for blocking fetch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PushService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Warm mute cache so background FCM can drop muted chats.
        FirebaseAuth.getInstance().currentUser?.let { MuteStore.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNewToken(token: String) {
        scope.launch {
            try { TokenRegistrar.upsertToken(applicationContext, token) } catch (_: Exception) {}
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onMessageReceived(remoteMessage: RemoteMessage) { // <-- NOT suspend
        NotificationHelper.ensureChannels(this)

        val data = remoteMessage.data
        val type = data["type"] ?: remoteMessage.notification?.tag ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return

        val prefs = SettingsCache.load(this)
        if (!prefs.notificationsEnabled) return

        Log.d("FCM_RAW", "type=$type data=$data notif=${remoteMessage.notification}")

        when (type) {
            // ---------------- CHAT / MESSAGE ----------------
            "message", "chat" -> {
                if (!prefs.chatNotificationsEnabled) return

                val chatId = data["chatId"] ?: return

                // Fast-path mute via local cache
                if (MuteStore.isMuted(chatId)) {
                    Log.d("FCM_MUTE", "Muted (cache) chatId=$chatId → drop")
                    NotificationManagerCompat.from(this).cancel(chatId.hashCode())
                    return
                }

                // Cold-start single fetch if cache might be cold (blocking okay on FCM thread)
                val mutedFallback = try {
                    val doc = Tasks.await(
                        FirebaseFirestore.getInstance()
                            .collection("userChats").document(user.uid)
                            .collection("chats").document(chatId)
                            .get()
                    )
                    val until = doc.getTimestamp("mutedUntil")?.toDate()?.time
                    val now  = System.currentTimeMillis()
                    until != null && now < until
                } catch (e: Exception) {
                    Log.w("FCM_MUTE", "Fallback mute fetch failed: $e")
                    false
                }
                if (mutedFallback) {
                    Log.d("FCM_MUTE", "Muted (fallback) chatId=$chatId → drop")
                    NotificationManagerCompat.from(this).cancel(chatId.hashCode())
                    return
                }

                // Suppress while chat is open
                if (ForegroundTracker.getCurrentChat() == chatId) return

                val senderName     = data["senderName"] ?: data["title"] ?: "New message"
                val text           = data["text"] ?: data["body"] ?: ""
                val senderPhotoUrl = data["senderPhotoUrl"]

                // Build/update local history for MessagingStyle
                val now = System.currentTimeMillis()
                NotificationsStore.appendMessage(this, chatId, senderName, text, now)
                val history = NotificationsStore.history(this, chatId)

                val open = android.app.PendingIntent.getActivity(
                    this, chatId.hashCode(),
                    Intent(this, ChatActivity::class.java)
                        .putExtra("chatId", chatId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val me: Person = Person.Builder()
                    .setName(user.displayName ?: user.email ?: "Me")
                    .build()

                // Compat MessagingStyle (typed, not Unit)
                val style: NotificationCompat.MessagingStyle =
                    NotificationCompat.MessagingStyle(me).also { s ->
                        for (m in history) {
                            val sender = Person.Builder().setName(m.sender).build()
                            s.addMessage(
                                NotificationCompat.MessagingStyle.Message(
                                    m.text, m.ts, sender
                                )
                            )
                        }
                    }

                // Compat Builder (not framework)
                val builder = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_message)
                    .setContentTitle(senderName)
                    .setStyle(style)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setGroup("chat_$chatId")

                if (!senderPhotoUrl.isNullOrBlank()) {
                    scope.launch {
                        try { loadBitmap(senderPhotoUrl)?.let { builder.setLargeIcon(it) } } catch (_: Exception) {}
                        NotificationManagerCompat.from(this@PushService)
                            .notify(chatId.hashCode(), builder.build())
                    }
                } else {
                    NotificationManagerCompat.from(this)
                        .notify(chatId.hashCode(), builder.build())
                }
            }

            // ---------------- CALLS ----------------
            "incoming_call" -> {
                if (!prefs.callNotificationsEnabled) return

                val callId     = data["callId"] ?: return
                val callerId   = data["callerId"] ?: return
                val callerName = data["callerDisplayName"] ?: "Incoming call"

                val full = android.app.PendingIntent.getActivity(
                    this, callId.hashCode(),
                    Intent(this, IncomingCallActivity::class.java)
                        .putExtra("callId", callId)
                        .putExtra("callerId", callerId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val accept = android.app.PendingIntent.getActivity(
                    this, ("acc_$callId").hashCode(),
                    Intent(this, CallActionActivity::class.java)
                        .putExtra(CallActionActivity.EXTRA_ACTION, CallActionActivity.ACTION_ACCEPT)
                        .putExtra(CallActionActivity.EXTRA_CALL_ID, callId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val decline = android.app.PendingIntent.getActivity( this, ("dec_$callId").hashCode(), Intent(this, CallActionActivity::class.java) .putExtra(CallActionActivity.EXTRA_ACTION, CallActionActivity.ACTION_DECLINE) .putExtra(CallActionActivity.EXTRA_CALL_ID, callId) .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE )

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

            "call_update" -> {
                val status = data["status"] ?: return
                if (status != "ringing") {
                    NotificationManagerCompat.from(this).cancel(CALL_NOTIF_ID)
                }
            }
        }
    }

    // Run inside coroutine when used (we call it from scope.launch)
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
