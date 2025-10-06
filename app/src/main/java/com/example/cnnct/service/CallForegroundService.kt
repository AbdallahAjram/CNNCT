package com.example.cnnct.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.cnnct.calls.view.CallsActivity

class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra("callId") ?: ""
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, CallsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, "call_channel")
            .setContentTitle("Call in progress")
            .setContentText("Tap to return to call")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }
}
