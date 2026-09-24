# R8 rules — enabled in Part 8 (release hardening).

# ---------------------------------------------------------------------------
# Extension engine (CRITICAL)
#
# Plugins are dexed against the provider contract at build time and bind to
# those host classes BY ORIGINAL NAME at runtime through DexClassLoader. Any
# renaming or stripping under this package breaks every installed plugin
# with NoSuchMethodError / NoClassDefFoundError. Never obfuscate it.
# ---------------------------------------------------------------------------
-keep class com.cloudimage.provider.api.** { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization
#
# Runtime-decoded models: the Wallpaper navigation argument, provider
# filter DSL, extension manifests, the repository index, and the
# app-update DTOs. Rules adapted from the kotlinx.serialization README.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

# Keep the generated serializers of every app class; without them the JSON
# field names degrade at runtime.
-keep,includedescriptorclasses class com.cloudimage.**$$serializer { *; }

# Keep the Companion.serializer() accessors reachable from kept serializers.
-keepclassmembers class com.cloudimage.** {
    *** Companion;
}
-keepclasseswithmembers class com.cloudimage.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# OkHttp platform probes (standard from the okhttp README)
# ---------------------------------------------------------------------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
