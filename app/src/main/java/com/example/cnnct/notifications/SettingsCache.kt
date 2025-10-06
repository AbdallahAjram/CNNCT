package com.example.cnnct.notifications

import android.content.Context

data class NotifPrefs(
    val notificationsEnabled: Boolean = true,
    val chatNotificationsEnabled: Boolean = true,
    val callNotificationsEnabled: Boolean = true
)

object SettingsCache {
    private const val PREF = "notif_prefs"

    fun save(ctx: Context, p: NotifPrefs) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("notificationsEnabled", p.notificationsEnabled)
            .putBoolean("chatNotificationsEnabled", p.chatNotificationsEnabled)
            .putBoolean("callNotificationsEnabled", p.callNotificationsEnabled)
            .apply()
    }

    fun load(ctx: Context): NotifPrefs {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return NotifPrefs(
            notificationsEnabled = sp.getBoolean("notificationsEnabled", true),
            chatNotificationsEnabled = sp.getBoolean("chatNotificationsEnabled", true),
            callNotificationsEnabled = sp.getBoolean("callNotificationsEnabled", true)
        )
    }
}
