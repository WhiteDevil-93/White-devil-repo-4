# R8 rules for the WhiteDevil Android shell.
#
# Why this file exists: the release build shipped with isMinifyEnabled = false,
# so nothing was stripped. 50.4 of the APK's 52.3 MiB was dex, and 11,400 of its
# 33,979 classes were androidx.compose.material.icons while the app references
# 18 icons. Shrinking is the only meaningful size lever here -- all four native
# ABIs together are 0.04 MiB and every res/ entry together is 0.33 MiB, so ABI
# and density splits save nothing worth the risk.
#
# Most dependencies ship consumer rules that AGP applies automatically. The keeps
# below cover the three places where this app's own behaviour is not provable
# from the call graph: kotlinx.serialization's generated serializers, Tink's
# reflective key-manager registry behind androidx.security-crypto, and Ktor's
# CIO engine lookup.

# --- kotlinx.serialization -------------------------------------------------
# Call sites all pass an explicit .serializer(), but the generated $$serializer
# companions are reached through synthetic members R8 cannot always trace.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.whitedevil.**$$serializer { *; }
-keepclassmembers class com.whitedevil.** {
    *** Companion;
}
-keepclasseswithmembers class com.whitedevil.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# --- Tink / androidx.security-crypto ---------------------------------------
# EncryptedSharedPreferences resolves key managers by class name at runtime;
# stripping them fails only when the device key is first unsealed, not at build.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**

# --- Ktor ------------------------------------------------------------------
-keep class io.ktor.client.engine.cio.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# --- Coroutines ------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- Android platform conventions -----------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep the entry point and anything the manifest names by string.
-keep class com.whitedevil.MainActivity { *; }

# Credentials / Play services auth resolve providers reflectively.
-dontwarn com.google.android.gms.**
