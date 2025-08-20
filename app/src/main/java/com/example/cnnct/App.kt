package com.example.cnnct

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay

class App : Application(), LifecycleEventObserver {
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> startHeartbeat()
            Lifecycle.Event.ON_STOP  -> stopHeartbeat()
            else -> Unit
        }
    }

    private fun startHeartbeat() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            val users = Firebase.firestore.collection("users").document(uid)
            while (isActive) {
                users.set(mapOf("lastOnlineAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                delay(20_000) // 20s (tune as you like; don’t go too low to avoid quota burn)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
