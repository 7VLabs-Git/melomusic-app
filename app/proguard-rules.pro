# File: app/proguard-rules.pro
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.melomusic.app.data.** { *; }
-keep class com.melomusic.app.UserPlaylist { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken