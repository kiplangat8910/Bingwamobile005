# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools ProGuard configuration file located at:
# /workspaces/Bingwamobile005/adminhub/proguard-rules.pro

# Keep Compose classes
-keep class androidx.compose.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Keep AdminHub models
-keep class com.bingwa.adminhub.data.models.** { *; }
