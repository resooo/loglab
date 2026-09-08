# LogLab 项目交接文档

> 版本：v1.8.3（versionCode 22）· 更新日期：2026-09-08
> 面向接手开发/维护的工程师。读完本文应能独立完成：环境搭建、构建出包、理解核心链路、继续迭代。

---

## 1. 项目概述

**LogLab** 是一款 Android 日志抓取工具：**无需 root、无需电脑、无需数据线**，手机直接抓取手机自身的 logcat 日志。

- 包名：`com.loglab.app`（debug 后缀 `.debug`）
- 平台要求：Android 8.0+（minSdk 26），真机验证环境：OnePlus PJX110 / ColorOS（Android 15）
- ABI：仅 `arm64-v8a`（`app/build.gradle.kts` 的 `ndk.abiFilters`，如需模拟器调试临时加 `x86_64`）
- UI 语言：简体中文；UI 风格对标 [LogFox](https://github.com/F0x1d/LogFox)（内容即界面、等宽字体、级别字母着色）

### 核心价值（与同类工具的差异）

1. **内嵌 ADB 协议栈**（非 Shizuku/非 PC 中转）：App 内实现 ADB 协议 + TLS 无线调试配对（SPAKE2），直连本机 adbd。
2. **分屏配对方案**：针对 ColorOS「切走 App 配对码/端口即刷新」的机制，引导用户用系统分屏/自由窗口完成配对（详见 §5.4）。
3. **配对一次永久用**：配对密钥持久化，之后开关「无线调试」只变端口，App 自动 mDNS 扫描新端口直连。

---

## 2. 功能清单（v1.8.3）

| 模块 | 功能 |
|---|---|
| 首页（抓取） | 一键抓取（主按钮 44dp + 复制/清空 40dp 图标）、工具行（搜索+进程+级别+行数下拉）、**快捷芯片行**（只看错误 / 缓冲区多选 main·system·crash·radio·events·all / 启动抓取 / 搜索=过滤⇄高亮）、**启动抓取**（不等进程就绪先收流，检测到启动后继续抓 N 秒，定位「启动点」并可「只看启动后」）、进程包名动态展开输入框（**图标化应用选择器**：应用图标+前台/运行中徽标，数据来自本机 PackageManager 不走 ADB）、点行弹底部详情面板、状态行（智能检查+通道状态合一，点击进连接页） |
| 实时页 | 开始/暂停/停止/复制/清空图标控制行、速率显示（顶栏小字）、倒序跟随滚动、缓冲区多选 + 只看错误 + 匹配模式芯片、**PID 跟随**（目标应用被杀/重启后自动重建日志流，带防抖）、关键词过滤 |
| 崩溃页 | 崩溃监控前台服务（specialUse）、崩溃记录列表、堆栈底部面板查看/复制/分享 |
| 导出页 | 导出为 `.log` 文件（默认后缀 .log）、**缓冲区多选**、分享 |
| 连接页 | 设备 mDNS 扫描列表（点击即连）、分屏配对三步引导、配对码+配对端口（可选，手动兜底）、手动填写地址、探测通道、重新连接、ADB 公钥查看/写入（root 高级） |
| 使用方法页 | 三步上手 + 分屏配对两张实拍图示 + 日常使用 + 排障清单 |
| 设置页 | 状态区（连接/配对状态 + 重新配对/清除连接地址兜底）、外观（深色主题）、诊断（上次崩溃报告/运行日志全屏查看）、关于（当前版本 + GitHub 检查更新/下载/安装） |
| 启动智能检查 | 首页启动时自动检测：无线调试开关状态（独立提示+一键跳开发者选项）→ mDNS 端口自动修正（验证失败回滚原配置，防存档污染）→ 连接结果分级提示（可重试/去连接） |

---

## 3. 技术栈

| 项 | 版本/说明 |
|---|---|
| 语言/UI | Kotlin 2.2.20 + Jetpack Compose（BOM 2025.08.00，Material3） |
| 构建 | AGP 8.7.3 / Gradle 8.9（`/opt/gradle/gradle-8.9`）/ JDK 17 |
| DI | Hilt 2.57.1（KSP 2.2.20-2.0.2） |
| 无线配对 | `com.flyfishxu:kadb-android:1.3.0`（仅配对环节，见 §5.5 注意事项） |
| 抓取协议 | **自研内嵌 ADB**（`core/adb` 包：协议帧/TLS 传输/logcat 流） |
| 网络 | OkHttp 4.12.0（HostBridge 通道）、kotlinx-serialization 1.9.0 |
| 协程 | kotlinx-coroutines 1.10.2（`resolutionStrategy.force` 统一，防止 Kadb 传入高版本） |
| 存储 | DataStore Preferences（`androidx.datastore:datastore-preferences:1.1.7`）+ security-crypto |
| SDK | compileSdk 35 / targetSdk 35 / **buildToolsVersion 锁定 34.0.0**（沙箱限制，见 §6.4） |
| 镜像 | `settings.gradle.kts` 优先走腾讯云/华为云 Maven 镜像（沙箱网络受限） |

---

## 4. 代码结构

```
app/src/main/java/com/loglab/app/
├── App.kt / MainActivity.kt          # Hilt 入口 + 单 Activity
├── di/AppModule.kt                   # Hilt 全局提供
├── core/
│   ├── adb/                          # ★ 内嵌 ADB 协议栈
│   │   ├── AdbProtocol.kt            #   ADB 协议帧（CNOK/OKAY/WRTE 等）
│   │   ├── AdbTransport.kt           #   TLS socket 传输
│   │   ├── AdbConnection.kt          #   连接会话与流复用
│   │   ├── AdbBackend.kt             #   logcat 命令执行后端
│   │   ├── AdbPairing.kt             #   SPAKE2 无线配对（调 Kadb）
│   │   ├── AdbKeyStore.kt            #   配对密钥持久化（配对一次永久用）
│   │   ├── KadbCertPersistence.kt    #   Kadb 证书持久化桥接
│   │   └── NsdDiscovery.kt           #   ★ mDNS 发现（三种服务类型）
│   ├── channel/                      # 双通道抽象
│   │   ├── Channel.kt / AdbChannel.kt / BridgeChannel.kt
│   │   └── ChannelManager.kt         # autoConnect(policy) 统一入口
│   ├── bridge/                       # HostBridge HTTP 通道（备用通道）
│   ├── connect/StartupCheck.kt       # 启动智能检查状态机
│   ├── crash/                        # 崩溃解析/存储（CrashParser/CrashStore）
│   ├── apps/AppInfoProvider.kt       # 应用选择器数据源（PackageManager 图标/名称 + shell 运行/前台状态）
│   ├── export/LogExporter.kt         # 导出 .log
│   ├── logcat/                       # LogcatCommand(多缓冲区/多PID/buildStartup)/LogParser/LogPriority/PidResolver(resolveAll)
│   └── report/                       # AppLogger（运行日志）/ CrashReporter（自身崩溃）
├── data/
│   ├── model/AppSettings.kt          # DataStore 偏好（darkTheme/fontSize/adbHost/adbPort/adbPaired…）
│   └── repository/SettingsRepository.kt / LogRepository.kt
├── service/
│   ├── LogTailService.kt             # 实时跟踪前台服务（specialUse）
│   ├── TailSession.kt
│   └── CrashMonitorService.kt        # 崩溃监控前台服务
└── ui/
    ├── LogcatApp.kt                  # ★ 导航：4 tab（capture/tail/crash/settings）
    │                                 #   + 二级路由（connect/guide/export/logview，无底栏）
    ├── theme/Theme.kt
    ├── capture/ tail/ crash/ settings/ connect/ guide/ export/ logview/ onboarding/
    └── components/                   # HomeStatusBar(状态行+EllipseTextField) /
                                      # FilterDropdown(胶囊下拉) / LogListView / LogLineSheet /
                                      # AppPickerDialog(图标化应用选择器) / CopyableText / CfgChip / ChannelBar
```

资源：`res/drawable-nodpi/`（猫头 logo `ic_launcher_foreground.png`、使用方法两张实拍图）；adaptive icon：白底（`values/ic_launcher_background.xml` #FFFFFF）+ PNG 前景。

---

## 5. 核心机制详解

### 5.1 连接链路（最重要）

```
用户打开「无线调试」
        │
        ▼  首次
[mDNS 扫描] ──发现──▶ 连接页设备列表（点击即连）
[NsdDiscovery]        服务类型（NsdDiscovery.kt types 列表）：
                      _adb-tls-connect._tcp. → TLS_CONNECT（可抓日志）
                      _adb-tls-pairing._tcp. → PAIRING（仅配对，弹窗打开时才广播！）
                      _adb._tcp.             → PLAIN
        │
        ▼ 首次配对（只需一次）
连接页输入 6 位配对码（端口自动扫描，扫不到可手动填弹窗端口）
        │  AdbPairing.pair(host, port, code)  ← Kadb SPAKE2 over TLS
        ▼
配对成功 → AdbKeyStore 持久化密钥 → 自动扫 adbd 端口 → 多地址回退连接
        （候选 host：mDNS 结果 → 用户填写 → 127.0.0.1 → localhost，
          ColorOS 下本机 Wi-Fi IP 可能被自家防火墙拦，127.0.0.1 反而通）
        │
        ▼ 日常（配对完成后）
打开无线调试 → App 启动智能检查（StartupCheck）→ mDNS 扫新端口
→ 端口变了自动更新 adbPort 并重连 → 绿色"已连接" → 开始抓取
```

### 5.2 启动智能检查（`core/connect/StartupCheck.kt`）

首页启动/回到前台触发：检查无线调试开关 → mDNS 发现 → 端口修正 → 结果分级
（`StartupCheckResult`：connected / needsAction 等），UI 上由 `HomeStatusBar` 四态展示
（检查中橙点 / 已连接绿点 / 失败红点+去连接+重试 / 未连接灰点）。成功提示 4 秒自动消失。

### 5.3 双通道设计（`core/channel`）

`ChannelManager.autoConnect(ChannelPolicy)` 统一入口；`ChannelPolicy.ADB_ONLY` 为当前唯一策略
（v1.0 精简掉 USB）。`BridgeChannel`（HTTP HostBridge）保留代码作为备用通道。抓日志走
`AdbChannel.probe()` / `active()`。

### 5.4 ColorOS 配对经验（血泪史，务必读）

1. **配对端口只在系统「使用配对码配对设备」弹窗打开期间广播**——弹窗一关，端口即失效。
2. **ColorOS 上切走 App（哪怕系统弹窗还开着），配对码和端口都会刷新**（原生 Android 不会）。
   → 所以最终方案是**分屏/自由窗口**：先把本 App 挂成小窗，再开配对弹窗，全程同屏无切换。
3. 悬浮窗方案（SYSTEM_ALERT_WINDOW）已实现过又被移除：ColorOS 对**侧载应用**启用
   Android 13+「受限设置」，悬浮窗开关置灰无法授权（解除路径：应用信息 → ⋮ → 允许受限设置；
   或电脑 `adb shell appops set com.loglab.app android:system_alert_window allow`）。
   v1.2.0 已彻底删除悬浮窗代码，勿再引入。
4. mDNS 曾有一个**致命 bug**（v1.2.1 修复）：`NsdDiscovery` 没订阅 `_adb-tls-pairing._tcp.`
   导致配对端口永远扫不到。现已修复并加了手动填端口兜底（连接页「端口(可选)」框）。

### 5.5 Kadb 依赖注意事项（防闪退，重要）

`com.flyfishxu:kadb-android` 是 KMP 库，Android 实际构件是 `kotlinx-coroutines-core-jvm`。
**绝对不要 exclude kotlin-stdlib / kotlinx-coroutines-core-jvm**——排除后依赖树看起来正常，
但 dex 里没有协程类，运行期 `ClassNotFoundException: StateFlowKt` 闪退。
兜底：`app/build.gradle.kts` 尾部有 `verifyDexIntegrity` 任务，构建后自动校验 dex 关键类
（coroutines/core.adb/core.channel/MainActivity），缺类直接构建失败。协程版本由
`resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-*:1.10.2")` 统一。

### 5.6 前台服务

`LogTailService`（实时跟踪）与 `CrashMonitorService`（崩溃监控）均为
`foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明
（targetSdk 34+ 要求）。通知渠道在各自 Service 内创建。

### 5.7 启动抓取与 PID 跟随（v1.7.0）

**启动抓取**（`LogRepository.captureStartup` + `LogcatCommandBuilder.buildStartup`）：
抓"App 启动瞬间"的崩溃。刻意不用 `logcat -c`（破坏性，会抹掉上次崩溃现场），
改用 `logcat -T 1`（只回放最近 1 行并持续跟随，效果等同"从现在开始"）；
同时每 400ms 轮询 `pgrep -f`/`ps` 等目标进程 PID（`PidResolver.resolveAll`），
出现后继续抓 `startupTailMs`（默认 5s，UI 可调），返回全窗口行 + 启动点下标，
UI 可「只看启动后」。注意 `buildStartup` **不带 --pid**（目标进程尚未出现）。

**PID 跟随**（`TailSession.watchPid`）：实时页指定包名时，旧 `--pid` 流在应用被杀/
重启后收不到新日志（表现为"跟踪突然静默"）。`watchPid` 每 2s 轮询 PID 集合，
需连续两次拿到同一新集合（防 pgrep 启停瞬间抖动）才重建日志流，并插入一行
「检测到 XX 进程变化」标记。

**多缓冲区**：`LogcatConfig.buffers`（默认 main+crash，crash 是崩溃/ANR 堆栈主战场），
命令侧逐个 `-b` 拼接（选 all 则只拼 `-b all`，部分 ROM 重复 -b all 会冲突）；
导出头与 `ExportOptions.buffers` 同步。**多 PID**：`--pid` 多值 + 结果侧按
`LogEntry.pid` 集合二次过滤兜底（部分 ROM 的 logcat 只认最后一个 --pid）。

---

## 6. 构建与发布

### 6.1 环境要求

- JDK 17（`gradle.properties` 已写死 `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64`，
  其他环境需改掉这行或对齐路径）
- Android SDK：platform 35 + **build-tools 34.0.0**（`buildToolsVersion` 已锁定；
  `android.builder.sdkDownload=false` 禁止 AGP 联网下载，其他环境可删）
- Gradle 8.9（或 `./gradlew` 若补 wrapper——当前仓库**没有 wrapper**，用系统 gradle）

### 6.2 构建命令

```bash
# release（已配置签名，直接出可安装包）
/opt/gradle/gradle-8.9/bin/gradle assembleRelease --max-workers=1
# 产物：app/build/outputs/apk/release/app-release.apk（~4.8MB）

# dex 完整性自检（可选，见 §5.5）
/opt/gradle/gradle-8.9/bin/gradle verifyDexIntegrity
```

### 6.3 签名与版本号

- 签名：keystore 文件不入库；密码与 alias 存于 GitHub Actions Secrets（KS_PASS / KEY_PASS / KEY_ALIAS），不写入任何文档或代码；本地构建需自行设 KS_FILE/KS_PASS/KEY_ALIAS/KEY_PASS 四个环境变量
  （release 直接复用此 keystore，正式发布前建议换正式证书）
- **版本号约定**：每个功能批次 `versionName +0.1`（bug 修复 +0.01），`versionCode` 恒 +1。
  当前 v1.7.2 / 17。改动必须同步升版本，改 `app/build.gradle.kts` 的 `defaultConfig`。

### 6.4 构建已知坑（沙箱实测，必读）

1. **Gradle daemon OOM**：R8 阶段 daemon 被杀报 `Gradle build daemon disappeared unexpectedly`。
   处理：`gradle --stop` + `pkill -f "kotlin-compiler-embeddabl[e]"`（`[e]` 防止 pkill 匹配到
   自身命令行）后重跑。`gradle.properties` 的堆参数（4.6G/1.5G）是按 8G cgroup 调的，
   内存更大的机器可放宽。
2. **daemon 死锁**：连续两次构建之间若上次异常退出，新构建可能复用坏 daemon 卡死
   （表现为长时间 0 CPU、build 目录无产出）。**每次构建前先 `gradle --stop`**。
3. **Bash CWD 重置**：shell 每次调用后回到起始目录，gradle 命令必须显式 `cd /workspace/loglab`。
4. build-tools 只有 34：AGP 想下 35 会失败，靠 `buildToolsVersion = "34.0.0"` 绕过，勿删。

---

## 7. 版本历史

| 版本 | code | 主要内容 |
|---|---|---|
| 1.8.3 | 22 | 误判修复：无线调试关闭后 adbd「半死」（仍接受 TLS 握手但不执行命令）+ 系统 mDNS 幽灵通告，曾误报「发现 N 个无线调试服务但连接不上」。现候选验证区分 OK/半死(DEAD)/不可达(UNREACHABLE)，**只要出现半死信号即判定未开启**（显示「去开启」直达开发者选项）；抓取遇 ECONNREFUSED 显示友好提示（无线调试可能已关闭）而非裸异常 |
| 1.8.2 | 21 | 本地化收尾：**补全英文缺口**（启动检查结果与实时阶段、连接/配对页、使用方法页、日志详情「复制原文/仅看此Tag」、首页统计行「共N行·耗时Nms」改为 UI 层本地化拼接、进程选择弹窗全双语）；实时页「进程」chip 与首页一致直弹应用选择器（删展开输入行）；**无线调试配对设备名 logcat-grabber → LogLab**（公钥注释同步；已配对设备重配后生效；内部 keystore ALIAS 与签名 keyAlias 不动，改名会破坏既有密钥）|
| 1.8.1 | 20 | 修复与体验：**修 `Only one --pid argument can be provided.`**（logcat 只接受单 --pid，命令侧取首个 PID，结果侧 PID 集合过滤兜底不变）；「重新配对」断开通道 + 直跳连接页（此前通道保持连接，回首页看不到「未配对」）；**连接页 loopback 优先**（选设备连 127.0.0.1:端口 局域网 IP 回退、配对成功后 127.0.0.1 优先、扫描列表展示 127.0.0.1:端口）；实时页 + 共用状态栏完成双语；首页「进程」chip 直接弹应用选择器（删手动输入行）；芯片缩短（只看错误→错误 / 启动抓取→抓启动）|
| 1.8.0 | 19 | ★ 体验大版本：**127.0.0.1 loopback 优先连接**（mDNS 只取端口，回环直连与网段无关，换 Wi-Fi 不失效不重扫；loopback 毫秒级失败 + 逐候选 echo 复验回滚，老版本局域网 IP 存档自动迁移）/**启动静默检查更新**（24h 节流，首页更新横幅 → 设置页自动弹更新框）/**更新对话框渲染 Release notes**（markdown 清理为纯文本）/崩溃页「今天/近7天」筛选 + 行内应用图标与名称（保留窗口放宽为 7 天）/关于页改底部面板（开源地址/Bug反馈 ibr@foxmail.com/太墟署名可点击）/首页芯片行调序（只看错误→启动抓取→缓冲区）/**应用内语言切换**（跟随系统/中文/English，AppCompat per-app locale 自动持久化，核心页面已抽取双语，长尾页面 v1.8.x 补全）/README 双语（英文默认）/CHANGELOG.md |
| 1.7.3 | 18 | 版本对齐（GitHub 网页端修改）：versionCode 18 / versionName 1.7.3，修复 tag 与 versionName 错位导致「检查更新」死循环 |
| 1.0.0 | 1 | 初版：内嵌 ADB 抓取、无线配对、LogFox 风格 4 tab UI、启动智能检查、崩溃监控、导出、双通道、11 项安全加固 |
| 1.1.0 | 2 | 首页信息架构重排（状态行合一/椭圆输入框/使用方法页/连接页独立）；配对端口现场扫描（未根治）；悬浮窗配对（后被移除）；设置页精简 |
| 1.2.0 | 3 | 回退悬浮窗方案（ColorOS 受限设置无法授权），改为分屏配对引导 |
| 1.2.1 | 4 | ★ 关键修复：mDNS 补订 `_adb-tls-pairing._tcp.`（配对端口扫描此前从未生效）；新增手动填配对端口兜底；pairHost 空串兜底 |
| 1.3.0 | 5 | 使用方法页嵌入分屏配对实拍图×2；首页主按钮行重排（复制/清空前移）；筛选下拉化；进程输入框动态展开 |
| 1.3.1 | 6 | 首页布局减法：控件区 5 行 → 2 行（主按钮 44dp、图标 40dp、搜索/进程/级别/行数合并一行，缓冲区收进「更多筛选」） |
| 1.4.0 | 7 | 实时页同步紧凑化（速率并入顶栏、控制行图标化）；首页顶栏 ⋮ 删除；全新几何猫头 logo（adaptive icon）；设置页删字号；新增「状态」区与重新配对/清除连接地址兜底 |
| 1.5.0 | 8 | ★ 品牌与包名重塑：项目名 LogLab、包名 com.loglab.app（applicationId/namespace/目录/文档全量迁移）；v1.5.0 起与旧包名 App 无法覆盖安装（需卸载重装） |
| 1.5.1 | 9 | 修复崩溃/运行日志/导出文件分享在非 Activity context 下崩溃（chooser 与 target 均补 FLAG_ACTIVITY_NEW_TASK，共 5 处）；logo 重绘：极简剪影猫头，内容占比 65% → ~48%，视觉不再突兀 |
| 1.6.0 | 10 | ★ 应用内更新 + 开源准备：GitHub Releases 检查/下载/安装（UpdateManager，设置页「关于」入口）；buildConfig 开启；签名支持 CI 环境变量注入；Gradle wrapper 8.9；README/LICENSE(MIT)/.gitignore（keystore 不入库）；.github/workflows/release.yml tag 自动发布；git init（remote: github.com/resooo/loglab） |
| 1.6.1 | 11 | ★ 修复：无线调试关闭后启动检查误报「已连接」——mDNS 陈旧缓存（幽灵服务）+ 握手成功即判连接；现在所有 Ready/PortUpdated 结论强制 echo 复验（echoOk()），失败即断开并提示「无线调试可能已关闭」 |
| 1.6.2 | 12 | 无线调试未开启独立提示「无线调试未开启，去开启」，按钮直跳开发者选项；「未配对」与「未开启」分开提示 |
| 1.6.3 | 13 | 启动检查滞后性修复（幽灵缓存时自动再扫一轮再下结论）；深色主题下状态栏/导航栏图标颜色跟随 App 内主题（SideEffect 同步 isAppearanceLightStatusBars）；设置页删除两处冗余指引文字 |
| 1.6.4 | 14 | ★ 修复「整体罢工」：mDNS 解析出不可达地址（ENETUNREACH）时新配置先持久化导致存档被污染——现在验证失败必回滚原配置，且候选放宽为「任何与存档不同、同 IP 优先」逐个复验自愈 |
| 1.7.2 | 17 | ★ 崩溃页只看当天：监控回放与历史读取加 `-T 当天0点`，CrashStore 入库/加载/常驻跨天三处过滤非当天记录——老崩溃（如第三方 App 每次启动复现的）不再刷屏；确认 com.logcat.monitor 是设备上另一抓日志 App 因缺 FOREGROUND_SERVICE_SPECIAL_USE 权限反复崩，与 LogLab 无关 |
| 1.7.1 | 16 | ★ 修复崩溃页解析：Native 崩溃包名从 `tid/pid (进程名)` 提取（旧版硬编码 null 全显示「未知应用」）；Java 崩溃堆栈聚合（AndroidRuntime 逐行独立 log，旧版被拆散只剩 FATAL EXCEPTION 一行，Process: 包名/异常类名全丢）；摘要兜底线程名，不再「Java 崩溃 · Java 崩溃」；加载时丢弃旧无包名残缺记录，监控回放后重新入库 |
| 1.7.0 | 15 | ★ 日志精准抓取批次（P1-P3）：多缓冲区默认 main+crash / 多进程多 PID（命令侧 --pid 多值 + 结果侧 PID 集合兜底）/「只看错误」快捷开关 / 搜索=过滤⇄高亮双模式 / **启动抓取**（logcat -T 1 非破坏 + 轮询 pidof 等进程出现 + 启动点定位 + 只看启动后）/ 实时页 **PID 跟随**（应用重启自动重建流）/ **图标化应用选择器**（AppInfoProvider：本机 PackageManager 图标/名称毫秒级 + shell 前台/运行中徽标）；导出页缓冲区多选；首页缓冲区芯片行 |

---

## 8. 已知问题与待办建议

1. **Hot buffer 抓取期间的内存**：首页单次抓取 maxLines 上限 100000，超长日志注意内存
   （渲染层实时页已做 1000 条裁剪，首页未做分页）。
2. **монitors 与多设备**：mDNS 扫描会把本机服务与其他设备混在一起（列表有 host 区分），
   未来若支持「抓别的手机」需显式区分目标设备。
3. **logview/导出大文件**：导出走 FileProvider 分享，超大日志（>10 万行）未做流式优化。
4. **keystore**：现用 debug keystore 签 release，正式分发前务必换。
5. **国际化长尾**：v1.8.0 已完成语言切换框架（AppCompat per-app locale）与核心页面双语
   （导航/抓取/崩溃/设置/更新对话框），连接页/使用方法页/导出页/LogView/动态状态消息仍为中文硬编码，
   后续版本抽入 res/values-en。语言存储由 AppCompat autoStoreLocales 自管，勿再往 AppSettings 加 language 字段。
6. **可选迭代方向**：日志高亮规则自定义、按进程/Tag 保存筛选预设、导出为 zip+按级别分文件、
   Play 商店合规化（前景 icon 512、隐私政策页）。

---

## 9. 快速上手（接手第一天）

```bash
# 1. 克隆/解压源码后，检查 Android SDK 路径（local.properties 不在包内，需自建）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建
gradle assembleRelease --max-workers=1

# 3. 安装到真机（Android 8+，arm64）
adb install -r app/build/outputs/apk/release/app-release.apk

# 4. 真机首次使用
#    开发者选项 → 无线调试 → 开启
#    App 首页点状态行 → 连接页 → 按分屏引导配对（详见使用方法页图文）
```

有任何与本文冲突的实现细节，以代码为准；本文档对应 v1.8.3（versionCode 22）。
