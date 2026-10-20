# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- NECESSARY ADDITIONS FOR PRIVATE BUCKET & FIRESTORE ---

# 1. Keep Glide's internal logic for private bucket handshakes
-keep public class com.bumptech.glide.Glide { *; }
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# 2. Keep your data class fields from being renamed (matches Firestore)
-keepclassmembers class com.gold.chatapp.ChatItem {
    <fields>;
}

# 3. Keep OkHttp and headers (Required for B2 Private Access)
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# 4. Keep your package classes to prevent activity/intent errors
-keep class com.gold.chatapp.** { *; }

# 5. --- MANDATORY FOR FCM V1 NOTIFICATIONS (FIXES THE BLANK ERROR) ---
# This prevents R8 from deleting constructors in the Google Auth library
# This fixes the "java.lang.Class<i5.a> has no zero argument constructor" error
-keep class com.google.auth.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.auth.**
-dontwarn com.google.api.client.**
-keepattributes EnclosingMethod,InnerClasses,Signature,AnnotationTarget

# Keep specific Jackson/JSON members that the Google Library uses internally
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Keep Google Credentials specific factory methods
-keepclassmembers class com.google.auth.oauth2.GoogleCredentials {
    public *;
}