package com.example.cnnct.calls

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.cnnct.R
import com.example.cnnct.notifications.NotificationHelper

class ActiveCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra("callId") ?: "unknown"
        val peerName = intent?.getStringExtra("peerName") ?: "Connected"

        val notification = NotificationHelper.builder(this, "ongoing_call")
            .setContentTitle("Call in progress")
            .setContentText("Speaking with $peerName")
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .build()

        // Service type microphone is required for Android 14+ specific VoIP usage
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(ONGOING_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }

    companion object {
        const val ONGOING_NOTIFICATION_ID = 9999
    }
}
