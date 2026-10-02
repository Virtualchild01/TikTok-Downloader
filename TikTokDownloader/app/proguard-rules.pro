# Proguard rules for TikTok Downloader
-keep class com.example.tiktokdownloader.api.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
