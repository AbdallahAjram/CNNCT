package com.example.cnnct.chat.model



/**
 * Note the new `mutedMemberIds`: members listed here cannot send messages
 * (client should enforce in the chat composer; you can also enforce in rules/cloud functions).
 */
// com.example.cnnct.chat.model.GroupInfo
data class GroupInfo(
    val chatId: String,
    val groupName: String,
    val groupDescription: String?,
    val groupPhotoUrl: String?,
    val members: List<String>,
    val adminIds: List<String>,
    // NEW
    val mutedMemberIds: List<String> = emptyList()
)

