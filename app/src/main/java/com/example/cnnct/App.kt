// app/src/main/java/com/example/cnnct/App.kt
package com.example.cnnct

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.cnnct.notifications.NotificationHelper
import com.example.cnnct.notifications.TokenRegistrar
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

        // Keep FCM token mapped to the signed-in user and manage heartbeat on auth changes
        val auth = FirebaseAuth.getInstance()
        authListener = FirebaseAuth.AuthStateListener { fa ->
            val user = fa.currentUser
            if (user != null) {
                // Upsert current FCM token under /users/{uid}.fcmTokens[deviceId]
                appScope.launch {
                    try {
                        val token = FirebaseMessaging.getInstance().token.await()
                        TokenRegistrar.upsertToken(applicationContext, token)
                    } catch (_: Exception) { /* ignore */ }
                }
                // If app is in foreground, ensure heartbeat running for this uid
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startHeartbeat(user.uid)
                }
            } else {
                stopHeartbeat()
            }
        }
        auth.addAuthStateListener(authListener!!)

        // ✅ NEW: Preload notification prefs into local cache once at startup
        appScope.launch {
            try {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val snap = Firebase.firestore.collection("users").document(uid).get().await()
                val notificationsEnabled = snap.getBoolean("notificationsEnabled") ?: true
                val chatEnabled = snap.getBoolean("chatNotificationsEnabled") ?: true
                val callEnabled = snap.getBoolean("callNotificationsEnabled") ?: true

                com.example.cnnct.notifications.SettingsCache.save(
                    this@App,
                    com.example.cnnct.notifications.NotifPrefs(
                        notificationsEnabled = notificationsEnabled,
                        chatNotificationsEnabled = chatEnabled,
                        callNotificationsEnabled = callEnabled
                    )
                )
            } catch (_: Exception) { /* ignore – best effort */ }
        }
    }


    override fun onTerminate() {
        super.onTerminate()
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        stopHeartbeat()
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
        // Restart for new uid if needed
        if (heartbeatJob != null) {
            // If same uid, keep running
            if (FirebaseAuth.getInstance().currentUser?.uid == uid) return
            stopHeartbeat()
        }
        heartbeatJob = appScope.launch {
            val users = Firebase.firestore.collection("users").document(uid)
            while (true) {
                try {
                    users.set(
                        mapOf("lastOnlineAt" to FieldValue.serverTimestamp()),
                        SetOptions.merge()
                    )
                } catch (_: Exception) { /* ignore transient errors */ }
                delay(20_000) // 20s
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
