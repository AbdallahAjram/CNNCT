// app/src/main/java/com/example/cnnct/App.kt
package com.example.cnnct

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.cnnct.notifications.MuteStore
import com.example.cnnct.notifications.NotificationHelper
import com.example.cnnct.notifications.TokenRegistrar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application(), LifecycleEventObserver {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null

    override fun onCreate() {
        super.onCreate()

        // Create notification channels once
        NotificationHelper.ensureChannels(this)

        // Observe process lifecycle for heartbeat
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Manage token + heartbeat + MuteStore with auth changes
        val auth = FirebaseAuth.getInstance()
        authListener = FirebaseAuth.AuthStateListener { fa ->
            val user = fa.currentUser
            if (user != null) {
                // Start/refresh per-chat mute listener as soon as the user is signed in
                MuteStore.start()

                // Upsert current FCM token
                appScope.launch {
                    runCatching {
                        val token = FirebaseMessaging.getInstance().token.await()
                        TokenRegistrar.upsertToken(applicationContext, token)
                    }
                }

                // If app is foreground, ensure heartbeat running
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startHeartbeat(user.uid)
                }
            } else {
                stopHeartbeat()
                MuteStore.stop()
            }
        }
        auth.addAuthStateListener(authListener!!)
    }

    override fun onTerminate() {
        super.onTerminate()
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        stopHeartbeat()
        MuteStore.stop()
    }

    // Lifecycle → start/stop heartbeat based on foreground/background
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                FirebaseAuth.getInstance().currentUser?.uid?.let { startHeartbeat(it) }
            }
            Lifecycle.Event.ON_STOP -> stopHeartbeat()
            else -> Unit
        }
    }

    private fun startHeartbeat(uid: String) {
        if (heartbeatJob != null) {
            if (FirebaseAuth.getInstance().currentUser?.uid == uid) return
            stopHeartbeat()
        }
        heartbeatJob = appScope.launch {
            val users = Firebase.firestore.collection("users").document(uid)
            while (true) {
                runCatching {
                    users.set(
                        mapOf("lastOnlineAt" to FieldValue.serverTimestamp()),
                        SetOptions.merge()
                    )
                }
                delay(20_000) // 20s
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
