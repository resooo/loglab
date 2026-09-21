# ============================================================================
# 关闭 R8 的优化阶段（2026-09-21 加入，解决构建 OOM）
# ============================================================================
# `-dontoptimize` 跳过 R8 最耗内存的优化 pass（内联、类合并、逃逸分析等），
# 只保留**压缩**（删无用代码）与**混淆**（重命名）——这两项对体积的贡献最大。
#
# 为什么需要：本项目的 keep 规则包含多个巨型库的整包保留
#   - org.bouncycastle.** { *; }   （含后量子密码，上千个类）
#   - com.loglab.app.core.** { *; }（项目自身全部代码）
# 全部 `{ *; }` 保留意味着 R8 要对每个成员做可达性分析却一个都删不掉，
# 内存峰值远超沙箱可用内存（~4GB），实测连续四次
# `ERROR: R8: java.lang.OutOfMemoryError: Java heap space`。
#
# 代价评估：优化阶段主要收益是少量体积缩减（通常 3~8%）与微弱性能提升，
# 对本项目而言远小于"构建能跑通"的价值。若将来在内存充裕的机器上构建，
# 可移除本行以恢复完整优化。

-dontoptimize

# ---------------------------------------------------------------------------
# 项目自身代码：只保留必要部分（2026-09-21 收窄）
# ---------------------------------------------------------------------------
# 原先是 `-keep class com.loglab.app.core.** { *; }` + `data.** { *; }`，
# 即把项目全部代码的类名/成员名原样保留。这对 R8 是**纯负担**：
# 它必须为每个成员做可达性分析，却一个都不能删、不能改名，
# 内存峰值因此大幅上升（本项目在沙箱里连续多次 R8 OOM）。
#
# 收窄依据（已核查）：
#  - 项目内**没有任何反射**（无 Class.forName / ::class.java.name 之类）；
#  - 唯一的"按名字访问"需求来自 kotlinx.serialization，它有官方 keep 规则
#    （kotlinx-serialization 自带 consumer rules），不需要整包保留；
#  - Hilt/Dagger 的组件类由注解处理器生成，其 keep 规则见文件末尾。
-keep class com.loglab.app.di.** { *; }
-keep class com.loglab.app.App { *; }
-keep class com.loglab.app.MainActivity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
# 注：data.model 的序列化保留由下方 kotlinx.serialization 段落负责，
# 此处不再重复（重复规则会让 R8 做无谓的重复分析）。

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
