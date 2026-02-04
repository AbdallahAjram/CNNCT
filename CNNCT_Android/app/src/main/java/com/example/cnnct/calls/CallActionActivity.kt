package com.example.cnnct.calls

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.cnnct.calls.view.InCallActivity
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Transparent one-shot activity launched from notification actions.
 * Writes the Firestore change, then routes, then finishes.
 */
class CallActionActivity : Activity() {

    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra(EXTRA_ACTION)
        val callId = intent.getStringExtra(EXTRA_CALL_ID)

        if (action == null || callId == null) {
            finish()
            return
        }

        ioScope.launch {
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

                        // Launch the in-call UI on the acceptor device
                        val i = Intent(this@CallActionActivity, InCallActivity::class.java)
                            .putExtra("callId", callId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(i)
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
                        // No UI on decline
                    }
                }
            } catch (e: Exception) {
                Log.e("CallActionActivity", "Call action failed: ${e.message}")
            } finally {
                // Close immediately
                finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "accept"
        const val ACTION_DECLINE = "decline"
        const val EXTRA_ACTION = "action"
        const val EXTRA_CALL_ID = "callId"
    }
}
