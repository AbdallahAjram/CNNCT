# General
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }

# Firebase & Firestore Models
# Keep all data classes used for Firestore serialization/deserialization
-keep class com.cnnct.chat.mvc.model.** { *; }
-keep class com.abdallah.cnnct.chat.model.** { *; }
# Also keep any other potential model locations just in case
-keep class com.abdallah.cnnct.chat.core.model.** { *; }


# Agora SDK
-keep class io.agora.** { *; }
-dontwarn io.agora.**

# AI / OpenAI
# Keep AI repository and related classes if they are used via reflection or serialization
-keep class com.abdallah.cnnct.chat.core.ai.** { *; }

# Other common Android defaults (usually in default android rules, but good to ensure)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-dontwarn org.jspecify.nullness.Nullable