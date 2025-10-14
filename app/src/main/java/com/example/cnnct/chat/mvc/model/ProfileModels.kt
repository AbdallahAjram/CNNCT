package com.example.cnnct.chat.model

data class UserProfile(
    val uid: String,
    val displayName: String,
    val about: String?,
    val phoneNumber: String?,
    val photoUrl: String?
)

data class GroupInfo(
    val chatId: String,
    val groupName: String,
    val groupDescription: String?,
    val groupPhotoUrl: String?,
    val members: List<String>,
    val adminIds: List<String>
)
