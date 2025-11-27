// app/src/main/java/com/example/cnnct/notifications/NotificationsStore.kt
package com.example.cnnct.notifications

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object NotificationsStore {
    private const val PREF = "notif_store"

    fun appendMessage(ctx: Context, chatId: String, sender: String, text: String, timestampMs: Long) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(chatId, "[]"))
        arr.put(JSONObject().apply {
            put("sender", sender)
            put("text", text)
            put("ts", timestampMs)
        })
        // keep last 5
        val trimmed = JSONArray()
        val start = (arr.length() - 5).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.getJSONObject(i))
        sp.edit().putString(chatId, trimmed.toString()).apply()
    }

    data class Msg(val sender: String, val text: String, val ts: Long)

    fun history(ctx: Context, chatId: String): List<Msg> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(chatId, "[]"))
        val list = mutableListOf<Msg>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Msg(o.optString("sender"), o.optString("text"), o.optLong("ts")))
        }
        return list
    }

    fun clearHistory(ctx: Context, chatId: String) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().remove(chatId).apply()
    }
}
