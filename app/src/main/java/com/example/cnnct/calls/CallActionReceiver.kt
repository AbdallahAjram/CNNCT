package com.example.cnnct.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.cnnct.calls.controller.CallsController

class CallActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra("callId") ?: return
        val controller = CallsController(context)
        when(action) {
            "ACTION_ACCEPT_CALL" -> controller.acceptCall(callId)
            "ACTION_REJECT_CALL" -> controller.rejectCall(callId)
        }
    }
}
