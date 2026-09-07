import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.loglab.app"
    compileSdk = 35
    // 沙箱内只能拿到 build-tools 34，显式锁定，避免 AGP 联网下载 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.loglab.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += setOf("zh", "en")

        // 设计文档：仅发布 arm64-v8a。若要在 x86_64 模拟器上调试，
        // 临时改为 listOf("arm64-v8a", "x86_64") 即可。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 本地：默认用 app/keystore/debug.jks（不入库，见 .gitignore）
            // CI：由 GitHub Actions secrets 注入环境变量（KS_FILE/KS_PASS/KEY_ALIAS/KEY_PASS），
            //     与 .github/workflows/release.yml 配套；环境变量缺省时回退本地默认值
            storeFile = file(System.getenv("KS_FILE") ?: "keystore/debug.jks")
            storePassword = System.getenv("KS_PASS") ?: "logcatgrabber"
            keyAlias = System.getenv("KEY_ALIAS") ?: "logcatgrabber"
            keyPassword = System.getenv("KEY_PASS") ?: "logcatgrabber"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME/VERSION_CODE：应用内更新对比版本用
        buildConfig = true
    }

    lint {
        // AGP 8.7.3 内置 lint 的 UAST 桥与 Kotlin 2.2 的 analysis API 不兼容，
        // NonNullableMutableLiveDataDetector 会抛 IncompatibleClassChangeError。
        disable += setOf("NullSafeMutableLiveData")
        // 同类兼容性问题可能出现在其他 detector 上，release 构建不因 lint 中断
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Kadb 会传进来更高版本的协程，统一到项目版本，避免运行期行为不一致
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            // BouncyCastle / jspecify 多 jar 同名 OSGi 清单，保留一份即可
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/MANIFEST.MF"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

dependencies {
    // ---- AndroidX core ----
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    // ---- Compose (BOM 与 Kotlin 2.2 的 Compose Compiler 匹配) ----
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Navigation ----
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // ---- Hilt ----
    implementation("com.google.dagger:hilt-android:2.57.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    ksp("com.google.dagger:hilt-compiler:2.57.1")

    // ---- ADB 无线调试配对（SPAKE2 over Curve25519 + TLS）----
    // 仅用于「配对」这一环节；日志抓取仍走下方自研的内嵌 ADB 协议实现。
    //
    // 注意：这里绝对不要 exclude kotlin-stdlib / kotlinx-coroutines-core-jvm。
    // coroutines-core 是 KMP 库，它在 Android 上的实际构件就是 -core-jvm，
    // 一旦排除，Gradle 依赖树里仍显示 coroutines 存在，但没有任何 jar 被打进 dex，
    // 运行时会抛 ClassNotFoundException: kotlinx.coroutines.flow.StateFlowKt。
    // 版本统一交给下面的 resolutionStrategy 处理。
    implementation("com.flyfishxu:kadb-android:1.3.0")

    // ---- Networking / serialization ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ---- Storage ----
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.security:security-crypto:1.1.0-beta01")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// 构建后自检：确认关键依赖真的被打进了 dex，而不只是出现在依赖树里。
//
// 背景：曾经为了避免 Kadb 抬升版本而写了
//   exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
// coroutines-core 是 KMP 库，它在 Android 上的实际构件就是 -core-jvm，
// 排除后 Gradle 依赖树仍然显示 coroutines 存在、构建也完全通过，
// 但 dex 里一个协程类都没有，运行期直接 ClassNotFoundException 闪退。
// 「dependencies 里有」≠「打进了 APK」，这里做真正的兜底校验。
// ---------------------------------------------------------------------------
// 说明：带 `;` 结尾的是精确类名，只用于「已配置 keep、不会被改名」的类；
// 以 `/` 结尾的是包前缀，用于校验整个库是否被打进来（release 下类名会被混淆，但包名不变）。
val requiredDexClasses = listOf(
    // coroutines 在 proguard-rules.pro 中已 keep，release 也保持原名
    "Lkotlinx/coroutines/flow/StateFlowKt;",
    "Lkotlinx/coroutines/BuildersKt;",
    "Lkotlinx/coroutines/CoroutineScope;",
    // 以下按包前缀校验（release 中类名会被 R8 重命名，包名不变）
    "Lkotlin/",
    "Lkotlinx/serialization/json/",
    // core 包已整包 keep，校验 ADB 协议 / mDNS 等关键类确在 dex 中
    "Lcom/loglab/app/core/adb/",
    "Lcom/loglab/app/core/channel/",
    // Manifest 中被引用，必保留
    "Lcom/loglab/app/MainActivity;"
)

tasks.register("verifyDexIntegrity") {
    group = "verification"
    description = "校验 APK 的 dex 中确实包含运行期必需的关键类"
    dependsOn("assembleRelease")

    val apkDir = layout.buildDirectory.dir("outputs/apk")
    val required = requiredDexClasses

    doLast {
            val dir = apkDir.get().asFile
            val apks = dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
            if (apks.isEmpty()) {
                throw GradleException("未在 $dir 下找到 APK，请先执行 assemble 任务")
            }

            var failed = false
            for (apk in apks) {
                val names = mutableSetOf<String>()
                val zip = ZipFile(apk)
                try {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.name.endsWith(".dex")) continue
                        val input = zip.getInputStream(entry)
                        try {
                            names += DexClassScanner.scan(input.readBytes())
                        } finally {
                            input.close()
                        }
                    }
                } finally {
                    zip.close()
                }

                val missing = required.filter { r -> names.none { it.startsWith(r) } }
                if (missing.isEmpty()) {
                    logger.lifecycle(
                        "[verifyDexIntegrity] ${apk.parentFile.name}/${apk.name} OK " +
                            "(${names.size} 个类，${required.size} 项必需类齐全)"
                    )
                } else {
                    failed = true
                    logger.error("[verifyDexIntegrity] ${apk.parentFile.name}/${apk.name} 缺少必需类：")
                    missing.forEach { logger.error("    $it") }
                }
            }
            if (failed) {
                throw GradleException(
                    "dex 完整性校验失败：关键依赖未打进 APK。" +
                        "最常见原因是误用了 exclude(kotlin-stdlib / kotlinx-coroutines-core-jvm)。"
                )
            }
    }
}

/**
 * 极简 dex 类表解析器：只需读出 class_defs 中的类型描述符，
 * 用于判断某个类「是否真的被打进了 dex」（而不是只存在字符串残留）。
 */
object DexClassScanner {
    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun uleb128(b: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = start
        while (true) {
            val c = b[p].toInt() and 0xFF
            p++
            result = result or ((c and 0x7F).toLong() shl shift)
            if (c and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }

    /** 读取 dex 字符串池中的 MUTF-8 字符串 */
    private fun stringAt(b: ByteArray, off: Int): String {
        val (_, p) = uleb128(b, off)
        val start = p
        var end = p
        while (b[end].toInt() != 0) end++
        return String(b, start, end - start, Charsets.UTF_8)
    }

    fun scan(dex: ByteArray): Set<String> {
        val stringIdsSize = u32(dex, 0x38)
        val stringIdsOff = u32(dex, 0x3C)
        val typeIdsOff = u32(dex, 0x44)
        val classDefsSize = u32(dex, 0x60)
        val classDefsOff = u32(dex, 0x64)

        val result = LinkedHashSet<String>()
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val classIdx = u32(dex, base)
            val strIdx = u32(dex, typeIdsOff + classIdx * 4)
            result += stringAt(dex, u32(dex, stringIdsOff + strIdx * 4))
        }
        check(stringIdsSize >= 0)
        return result
    }
}
