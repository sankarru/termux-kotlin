# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# Keep JNI native methods — R8 strips them otherwise
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes that load native libraries
-keep class com.termux.PtyProcess { *; }
-keep class com.termux.terminal.JNI { *; }
-keep class com.termux.VtParser { *; }
-keep class com.termux.app.TermuxInstaller { *; }
-keep class com.termux.shared.net.socket.local.LocalSocketManager { *; }
