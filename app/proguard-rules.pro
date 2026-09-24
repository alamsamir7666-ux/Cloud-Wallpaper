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
# Extension plugin runtime ABI (CRITICAL — v1.0.0 regression)
#
# A plugin package's compile classpath is exactly: provider:api +
# kotlin-stdlib + kotlinx-serialization. The plugin dex binds to ALL of it
# by ORIGINAL name through DexClassLoader. R8 never sees plugin code (it is
# an asset, not a dependency), so left unkept it renames kotlin.Unit,
# kotlin.Pair, Function1, kotlinx.serialization.json.Json, ... and every
# plugin dies at first use with NoClassDefFoundError — which the data layer
# then reports as a generic network failure ("check your connection").
# That is exactly what shipped in v1.0.0: fine on debug builds (no R8), fine
# in CI (unit tests run unshrunk), dead on the release APK.
#
# Keeping these packages fully is the supported plugin contract, not an
# optimization oversight; it costs a couple of MB and it is non-negotiable
# while plugins load through DexClassLoader.
# tools/audit_release_dex.py fails the release build if any reference of a
# bundled plugin goes missing from the host dex again.
# ---------------------------------------------------------------------------
-keep class kotlin.** { *; }
-keep class kotlinx.serialization.** { *; }

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
