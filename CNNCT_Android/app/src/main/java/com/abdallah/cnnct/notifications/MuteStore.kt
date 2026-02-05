package com.abdallah.cnnct.notifications

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Firestore-backed per-chat mute cache.
 * Path: /userChats/{me}/chats/{chatId} with field "mutedUntil" (Timestamp).
 */
object MuteStore {
    private val db = FirebaseFirestore.getInstance()
    private var reg: ListenerRegistration? = null
    private val mutedUntilByChat = ConcurrentHashMap<String, Long>() // epoch ms

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private fun notifyChanged() {
        listeners.forEach { l -> runCatching { l() } }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun start() {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        reg?.remove()
        reg = db.collection("userChats").document(me)
            .collection("chats")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val map = ConcurrentHashMap<String, Long>()

                for (d in snap.documents) {
                    val raw = d.get("mutedUntil")
                    val untilMs: Long = when (raw) {
                        is com.google.firebase.Timestamp -> raw.toDate().time
                        is Long -> raw
                        is Int -> raw.toLong()
                        is Double -> raw.toLong()
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull() ?: 0L
                        null -> 0L
                        else -> 0L
                    }
                    if (untilMs > 0L) map[d.id] = untilMs
                }

                mutedUntilByChat.clear()
                mutedUntilByChat.putAll(map)
                notifyChanged()
            }

    }

    fun stop() {
        reg?.remove()
        reg = null
        mutedUntilByChat.clear()
        notifyChanged()
    }

    /** Return true if now < mutedUntilMs */
    fun isMuted(chatId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = mutedUntilByChat[chatId] ?: return false
        return nowMs < until
    }

    /** Optimistic local update so UI reflects immediately. */
    fun prime(chatId: String, untilMs: Long) {
        mutedUntilByChat[chatId] = untilMs
        notifyChanged()
    }

    /** Optimistic local clear. */
    fun clearLocal(chatId: String) {
        mutedUntilByChat.remove(chatId)
        notifyChanged()
    }
}
