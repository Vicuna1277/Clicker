# Add project specific ProGuard rules here.

# app_process loads this class by name; JNI exports nativeRun with its exact class name.
-keep class com.vicuna.clicker.root.input.RootNativeDaemon { *; }

# Existing script JSON and Room data must remain compatible after shrinking.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.vicuna.clicker.root.bean.** { *; }
-keep class com.vicuna.clicker.root.db.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
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

# Shizuku instantiates this Binder by its class name.
-keep class com.vicuna.clicker.root.input.ShizukuTouchService { *; }
