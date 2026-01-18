// app/src/main/java/com/example/cnnct/calls/CallActionReceiver.kt
package com.example.cnnct.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CallActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra("callId") ?: return

        scope.launch {
            try {
                val calls = FirebaseFirestore.getInstance().collection("calls").document(callId)
                when (action) {
                    ACTION_ACCEPT -> {
                        calls.update(
                            mapOf(
                                "status" to "in-progress",
                                "startedAt" to Timestamp.now(),
                                "updatedAt" to Timestamp.now()
                            )
                        ).await()

                        // Launch InCallActivity from a receiver
                        val i = Intent(context, InCallActivity::class.java)
                            .putExtra("callId", callId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(i)
                    }
                    ACTION_DECLINE -> {
                        calls.update(
                            mapOf(
                                "status" to "rejected",
                                "endedAt" to Timestamp.now(),
                                "endedReason" to "rejected",
                                "updatedAt" to Timestamp.now()
                            )
                        ).await()
                    }
                }
            } catch (e: Exception) {
                Log.e("CallActionReceiver", "Failed to update call: ${e.message}")
            } finally {
                NotificationManagerCompat.from(context).cancel(PushService.CALL_NOTIF_ID)
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.example.cnnct.calls.ACCEPT"
        const val ACTION_DECLINE = "com.example.cnnct.calls.DECLINE"
    }
}
