// app/src/main/java/com/example/cnnct/notifications/NotificationHelper.kt
package com.example.cnnct.notifications

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.cnnct.R

object NotificationHelper {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_CALLS = "calls"

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

        // Calls
        val calls = NotificationChannel(
            CHANNEL_CALLS,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming and ongoing calls"
            setShowBadge(true)
        }
        nm.createNotificationChannel(calls)
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
