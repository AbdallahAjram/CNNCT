package com.abdallah.cnnct.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic brand colors
val IndigoPrimaryLight = Color(0xFF6366F1)
val IndigoPrimaryDark = Color(0xFF818CF8)
val IndigoSecondaryLight = Color(0xFF4F46E5)
val IndigoSecondaryDark = Color(0xFF6366F1)

val ReadReceiptBlue = Color(0xFF007AFF)

val LightColorScheme = lightColorScheme(
    primary = IndigoPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondary = IndigoSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7D2FE),
    onSecondaryContainer = Color(0xFF312E81),
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFDC2626),
    onError = Color.White
)

val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimaryDark,
    onPrimary = Color(0xFF312E81),
    primaryContainer = Color(0xFF4338CA),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = IndigoSecondaryDark,
    onSecondary = Color(0xFF312E81),
    secondaryContainer = Color(0xFF3730A3),
    onSecondaryContainer = Color(0xFFC7D2FE),
    background = Color(0xFF111827),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF374151),
    onSurfaceVariant = Color(0xFFD1D5DB),
    outline = Color(0xFF4B5563),
    outlineVariant = Color(0xFF374151),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

// --- Custom Semantic Tokens ---
data class CustomColors(
    val presenceOnline: Color = Color.Unspecified,
    val presenceOffline: Color = Color.Unspecified,
    val presenceBlocked: Color = Color.Unspecified,
    val brandPrimary: Color = Color.Unspecified
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

val customColorsLight = CustomColors(
    presenceOnline = Color(0xFF10B981), // Emerald 500
    presenceOffline = Color(0xFF9CA3AF), // Gray 400
    presenceBlocked = Color(0xFFEF4444), // Red 500
    brandPrimary = IndigoPrimaryLight
)

val customColorsDark = CustomColors(
    presenceOnline = Color(0xFF34D399), // Emerald 400
    presenceOffline = Color(0xFF9CA3AF), // Gray 400 (sufficient for both)
    presenceBlocked = Color(0xFFF87171), // Red 400
    brandPrimary = IndigoPrimaryDark
)
