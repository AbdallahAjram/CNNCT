
package com.abdallah.cnnct.settings.model

/**
 * Global, app-wide configuration (not per user).
 * - Language: fixed to English for the capstone.
 * - Wallpaper: default for everyone (not user-customizable).
 */
object AppConfig {
    const val APP_LANGUAGE = "en"

    // Option A: use a local drawable for chat wallpaper (recommended)
    // Reference this in Compose with painterResource(AppConfig.DEFAULT_WALLPAPER_RES)
    // and place an image in res/drawable, e.g., res/drawable/chat_wallpaper.png
    val DEFAULT_WALLPAPER_RES: Int? = null // e.g., R.drawable.chat_wallpaper

    // Option B: or a hosted static URL if you want remote assets (still global)
    //const val DEFAULT_WALLPAPER_URL: String? = null
}
