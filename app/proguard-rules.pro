# R8 rules for the release build.

# gomobile bridge (core.aar): libgojni.so finds these classes, fields and methods by name
# over JNI, so nothing in them may be renamed or removed.
-keep class go.** { *; }
-keep class core.** { *; }

# Kotlin objects handed to Go implement these interfaces; Go calls their methods by name.
-keep class * implements core.HostKeyVerifier { *; }
-keep class * implements core.SessionListener { *; }
-keep class * implements core.StatusListener { *; }
-keep class * implements core.HardwareSigner { *; }

# The terminal page calls the WebView bridge (TerminalWebView.Bridge) by method name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# WebView checks the annotation at runtime.
-keepattributes *Annotation*

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
