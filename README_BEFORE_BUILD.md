# LogLab 源码包说明

**版本：1.9.3（versionCode 27）+ Unreleased 工作树**
打包日期：2026-09-13

---

## 一、解压后先做两件事

本包由沙箱环境导出，**有两处路径是本机专属的，必须改**：

### 1. `gradle.properties` 的 JDK 路径

```properties
org.gradle.java.home=/opt/jdk-17.0.2    # ← 沙箱路径，改成你的 JDK 17 路径
```

若你的 JDK 17 在环境变量里，也可以直接删掉这一行让 Gradle 用当前 JDK。

### 2. `local.properties` 的 SDK 路径（本包**不含**此文件，需自建）

```bash
echo "sdk.dir=/你的/android-sdk" > local.properties
```

---

## 二、构建

```bash
cd LogLab-src-1.9.3
gradle --stop                                        # 每次构建前先停 daemon
gradle assembleRelease verifyDexIntegrity --max-workers=1
```

产物：`app/build/outputs/apk/release/app-release.apk`

校验通过时会打印：

```
[verifyDexIntegrity] release/app-release.apk OK (10315 个类，8 项必需类齐全)
```

---

## 三、环境要求

| 项 | 要求 |
|---|---|
| JDK | **17** |
| Android SDK | platform **35** + build-tools **34.0.0**（`buildToolsVersion` 已锁定，勿删） |
| Gradle | 8.9+（本仓库**不含 wrapper jar**，用系统 `gradle`；`gradlew` 脚本在但不可直接用） |

> `gradle.properties` 里的内存参数（`-Xmx4608m` / kotlin daemon `1536m`）与
> `android.builder.sdkDownload=false` 是按**沙箱环境**（8GB cgroup、网络受限）调的。
> 你的机器内存更充裕时可以放宽堆参数；网络正常时可以删掉 `sdkDownload=false`。

---

## 四、签名

**本包不含任何密钥文件**（`.jks` / `.keystore` / `keystore.pw.local` 均已排除）。

`app/build.gradle.kts` 的 release 签名配置按以下优先级取密码：

1. 环境变量 `KS_PASS` / `KEY_PASS`（CI 用）
2. 项目根目录 `keystore.pw.local` 文件（本地用，已 gitignore）
3. 都没有 → **构建签名失败**（密码不硬编码进仓库，这是有意设计）

本地构建前请自备 keystore 并设置：

```bash
export KS_FILE=/path/to/your.jks
export KS_PASS=你的store密码
export KEY_ALIAS=你的alias
export KEY_PASS=你的key密码
```

---

## 五、重要提醒

1. **版本号待升**：本轮改动（`CHANGELOG.md` 的 `## [Unreleased]` 段落）已构建验证通过，
   但 `versionCode / versionName` 被**有意留在 27 / 1.9.3 未升**。
   下次发版前记得升版本，并把 `[Unreleased]` 改名为正式版本号。
2. **`gradle.properties` 的 JDK 路径**：见第一节，换机器必改。
3. 详细的架构、核心机制、已知坑与踩坑记录，全部在 **`HANDOVER.md`** —— 建议优先阅读，
   其中第 6 节（崩溃监控链路）和第 7 节（v4 UI 体系）是本轮改动的主要战场。
4. 版本历史见 `CHANGELOG.md`。

---

## 六、包内文件

```
LogLab-src-1.9.3/
├── HANDOVER.md              # ★ 交接文档（先读这个）
├── CHANGELOG.md             # 版本历史
├── README.md / README_zh-CN.md
├── LICENSE                  # MIT
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/
├── .github/workflows/release.yml
└── app/
    ├── build.gradle.kts     # 含 verifyDexIntegrity 任务
    ├── proguard-rules.pro
    └── src/                 # 68 个 Kotlin 文件 / ~11.8k 行
```
