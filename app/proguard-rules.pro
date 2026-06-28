# ProGuard rules for Listener app

# Media3 / ExoPlayer
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.session.** { *; }

# NewPipe Extractor (heavy reflection usage)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }

# FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }

# YoutubeDL-Android
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }

# OkHttp / Okio
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# JSoup
-keep class org.jsoup.** { *; }

# Nanojson
-keep class com.grack.nanojson.** { *; }

# Keep line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable

# Rhino / Mozilla Javascript (used by NewPipe or others)
-dontwarn java.beans.**
