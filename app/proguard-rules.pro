-keep class com.loglab.app.core.** { *; }
-keep class com.loglab.app.data.** { *; }

# BouncyCastle：JCA 的算法实现类由 Provider 内部按「类名字符串」反射加载，
# R8 重命名类后这些字符串不会被同步改写，必须整包保留，
# 否则无线配对时抛 NoSuchAlgorithmException / NoSuchProviderException。
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.util.** { *; }
-keep class org.bouncycastle.math.** { *; }
-dontwarn org.bouncycastle.**

# Kadb（ADB 无线配对：SPAKE2 over Curve25519 + TLS）及其 SPAKE2 实现
-keep class com.flyfishxu.kadb.** { *; }
-keep class com.flyfish233.crypto.spake2.** { *; }

# 仅编译期注解，运行时不需要
-dontwarn org.jspecify.**

# Tink（security-crypto 传递依赖）引用但不提供的 errorprone 编译期注解
-dontwarn com.google.errorprone.annotations.**

# Kadb 引用但不提供的 JetBrains 编译期注解
-dontwarn org.jetbrains.annotations.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepclassmembers class * implements java.io.Serializable { *; }

# kotlinx.serialization
-keepclassmembers class com.loglab.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.loglab.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.loglab.app.**$$serializer { *; }

# kotlinx.coroutines：AGP 8.7.3 内置 R8 读不懂 Kotlin 2.2 的 @Metadata，
# 若让 R8 重命名这些类，会与其内部对 Kotlin 语言结构的处理产生冲突。
# 保留原名（仍允许优化），同时让下面的 verifyDexIntegrity 能按原名做校验。
-keep,allowoptimization,allowaccessmodification class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherLoader
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
