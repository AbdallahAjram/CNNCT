// app/src/main/java/com/example/cnnct/notifications/DeviceId.kt
package com.abdallah.cnnct.notifications

import android.content.Context
import java.util.UUID

object DeviceId {
    private const val PREF = "cnnct_device_prefs"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY, null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        sp.edit().putString(KEY, id).apply()
        return id
    }
}
