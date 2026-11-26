# Shrunk and optimised, NOT obfuscated (-dontobfuscate: readable stack traces, nothing to map back).
-dontobfuscate
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Built-in Tailscale (gomobile bind, :tailnet). The Go side calls back into Java and the generated glue looks classes and members up
# by name through JNI (FindClass / GetMethodID / GetFieldID), which R8 cannot see: go.Seq, go.Universe, the Seq proxies and the
# generated package (dev.leash.personal.tailnet.Tailnet, .Universe) must stay whole.
-keep class go.** { *; }
-keep class dev.leash.personal.tailnet.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# WebView JavaScript interface of the <agent-embed> host (called from JS by name)
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# ML Kit (the QR scanner) creates its component registrars by reflection. Its own rules keep the classes, and R8's strict
# full mode (the default since AGP 9) no longer keeps a kept class's constructor with it: without this, opening the scanner
# throws a NullPointerException in release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# kotlinx.serialization (only JsonElement is used, no @Serializable classes), OkHttp/Okio, ML Kit, CameraX, DataStore, Compose and
# coroutines ship their own consumer rules, which AGP merges.
