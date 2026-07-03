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
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# TODO investigate using smaller scope
-keep class com.google.common.cache.** { *; }

# Protobuf-lite (javalite) generates the proto-DataStore message classes (see
# OrcaDataStore.proto). At runtime protobuf-lite resolves each field by its original
# Java field name via reflection, so R8 must not rename or strip them. Without this the
# app crashes on startup with e.g. "Field ac3Supported_ for <class> not found".
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
