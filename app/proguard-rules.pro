# uniffi's generated bindings call into the Rust library through JNA, which
# looks classes and fields up by name at runtime.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class dev.cybercore.cybervault.core.** { *; }
-dontwarn java.awt.**
