// app/src/main/java/com/example/cnnct/notifications/NotificationHelper.kt
package com.abdallah.cnnct.notifications

import android.app.*
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.abdallah.cnnct.R

object NotificationHelper {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_CALLS = "calls_channel_v2" // Changed to force update on device

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Messages
        val msg = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Chat messages"
        }
        nm.createNotificationChannel(msg)

        // Calls (Ringing)
        val calls = NotificationChannel(
            CHANNEL_CALLS,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming voice/video calls"
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            
            val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()
            setSound(soundUri, audioAttributes)
        }
        nm.createNotificationChannel(calls)

        // Active Call (Ongoing - Low Importance)
        val ongoing = NotificationChannel(
            "ongoing_call",
            "Active Calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for active calls"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ongoing)
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun builder(context: Context, channelId: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_message) // overridden by caller if needed
            .setColorized(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
    }
}
