# General
-keepattributes SourceFile,LineNumberTable,Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }

# Firebase & Firestore Models
# This prevents R8 from renaming the fields inside your data classes
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
}
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Your Specific Packages
# Add your model package here to ensure full protection
-keep class com.abdallah.cnnct.chat.model.** { *; }
-keep class com.abdallah.cnnct.chat.core.model.** { *; }
-keep class com.cnnct.chat.mvc.model.** { *; }

# Agora SDK
# Agora uses JNI (Native code), so it MUST NOT be obfuscated
-keep class io.agora.** { *; }
-dontwarn io.agora.**

# Hilt / Dagger
-keep class dagger.** { *; }
-dontwarn dagger.**
-keep class javax.inject.** { *; }
-keep interface dagger.hilt.** { *; }

# Moshi (If used for serialization)
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# AI / OpenAI
-keep class com.abdallah.cnnct.chat.core.ai.** { *; }

# Standard Android Keep Rules
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-dontwarn org.jspecify.nullness.Nullable

# App Data Models (Fix for "C.B" crash on release)
-keep class com.abdallah.cnnct.settings.model.** { *; }
-keep class com.abdallah.cnnct.homepage.model.** { *; }
-keep class com.abdallah.cnnct.chat.model.** { *; }
-keep class com.abdallah.cnnct.calls.model.** { *; }
-keep class com.abdallah.cnnct.notifications.model.** { *; }