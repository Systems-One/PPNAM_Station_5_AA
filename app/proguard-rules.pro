# Keep line numbers in stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all app classes (Activities, Application, etc.)
-keep class com.mitas.ppnam.station5aa.** { *; }

# HiveMQ MQTT client
-keep class com.hivemq.** { *; }
-dontwarn com.hivemq.**

# Netty (used by HiveMQ)
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-keepclassmembers class io.netty.** { *; }

# Android ViewBinding (generated classes)
-keep class * extends androidx.viewbinding.ViewBinding { *; }

# JSON (used in MQTT payloads)
-keep class org.json.** { *; }

# Keep Kotlin metadata
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# Kotlin coroutines / stdlib
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
