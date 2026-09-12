# Proguard rules for PS2 Memory Card Editor
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep core and UI model classes
-keep class xyz.mininxd.ps2memcards.core.** { *; }
-keep class xyz.mininxd.ps2memcards.model.** { *; }
-keep class xyz.mininxd.ps2memcards.ui.components.** { *; }

# Kotlin Coroutines internal factories
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Line numbers for clean stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
