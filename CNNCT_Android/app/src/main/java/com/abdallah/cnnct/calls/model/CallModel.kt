package com.abdallah.cnnct.calls.model

import com.google.firebase.Timestamp

data class CallDoc(
    val callId: String = "",
    val callerId: String = "",
    val calleeId: String = "",
    val channelId: String = "",
    val status: String = "ringing", // ringing|accepted|in-progress|ended|rejected|missed
    val type: String = "voice",
    val createdAt: Timestamp? = null,
    val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    val duration: Long? = null,
    val endedReason: String? = null
)

data class UserCallLog(
    val callId: String = "",
    val peerId: String = "",
    val direction: String = "incoming", // incoming|outgoing
    val status: String = "missed", // missed|rejected|completed
    val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    val duration: Long? = null
)