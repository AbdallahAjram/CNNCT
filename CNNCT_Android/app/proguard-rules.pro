# --- General Android & Kotlin Rules ---
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-dontwarn javax.annotation.**

# Preserve line numbers for readable crash reports in Google Play Console
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Hilt / Dagger Rules ---
-keep class dagger.hilt.android.internal.** { *; }
-keep class * extends org.dagger.Internal { *; }
-keep class androidx.hilt.** { *; }
-dontwarn androidx.hilt.**

# --- Firebase & Firestore Models ---
# This ensures Firestore can map data to your specific data classes
-keep class com.example.cnnct.models.** { *; }
-keepattributes *Annotation*

# --- Agora SDK Rules (Critical for VoIP) ---
-keep class io.agora.** { *; }
-dontwarn io.agora.**
-keep class io.agora.rtc2.** { *; }

# --- Coil (Image Loading) ---
-keep class coil.** { *; }
-dontwarn coil.**

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext$ScheduledWithHandle {
    volatile last;
}