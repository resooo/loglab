# LogLab 项目交接文档

> 版本：**1.9.3**（versionCode **27**，含 `Unreleased` 工作树改动）· 更新日期：**2026-09-13**
> 面向接手开发/维护的工程师。读完本文应能独立完成：环境搭建、构建出包、理解核心链路、继续迭代。

> ⚠️ **接手第一件事**：本轮改动已构建验证通过，但**版本号被有意留在 27 / 1.9.3 未升**（需求方要求「保持 1.9.3，暂时不升」）。
> 下次发版前记得在 `app/build.gradle.kts` 的 `defaultConfig` 里升版本，并把 `CHANGELOG.md` 的 `## [Unreleased]` 段落改名为正式版本号。

---

## 0. 本次交接相比上一版（v1.8.4）发生了什么

UI 层做了**全量重构**（v4 布局），外加一批崩溃监控的实质性修复。如果你读过旧版交接文档，下面这几节是全新的：

| 变化 | 位置 |
|---|---|
| UI 全面改为 **v4 布局**（顶栏极简化、高频行、悬浮 FAB、底部面板化） | §7 |
| **设计令牌体系 `V4`** + 深浅色自适应机制（`LocalV4Dark`） | §7.2 |
| 崩溃页多条链路修复（**尾部静默、UI 不刷新、图标、清除**） | §6 |
| 新增**诊断信息分享** | §6.6 |
| `DevSettingsLauncher`：「去开启无线调试」直达开发者选项 | §5.3 |
| 三个页面的**统一交互骨架**（高频行 + 底部 FAB 列） | §7.1 |
| `gradle.properties` 新增沙箱专用内存/网络配置 | §8.1 |

---

## 1. 项目概述

**LogLab** 是一款 Android 日志抓取工具：**无需 root、无需电脑、无需数据线**，手机直接抓取手机自身的 logcat 日志。

- 包名：`com.loglab.app`（debug 后缀 `.debug`）
- 平台要求：Android 8.0+（minSdk 26），真机验证环境：OnePlus PJX110 / ColorOS（Android 15）
- ABI：仅 `arm64-v8a`（`app/build.gradle.kts` 的 `ndk.abiFilters`，如需模拟器调试临时加 `x86_64`）
- UI 语言：简体中文 + English（AppCompat per-app locale 切换）
- UI 风格：**v4 布局**（`LogLab-布局方案v4-设计稿.html` 为设计依据）

### 核心价值（与同类工具的差异）

1. **内嵌 ADB 协议栈**（非 Shizuku/非 PC 中转）：App 内实现 ADB 协议 + TLS 无线调试配对（SPAKE2），直连本机 adbd。
2. **分屏配对方案**：针对 ColorOS「切走 App 配对码/端口即刷新」的机制，引导用户用系统分屏/自由窗口完成配对（详见 §5.5）。
3. **配对一次永久用**：配对密钥持久化，之后开关「无线调试」只变端口，App 自动 mDNS 扫描新端口直连。

### 代码规模

68 个 Kotlin 文件 / 约 11,800 行。

---

## 2. 功能清单（v1.9.3）

| 模块 | 功能 |
|---|---|
| **首页（抓取）** | 高频行（**抓启动**开关 · **选进程**胶囊 · ⌕ 搜索 · ☰ 筛选 · ⋮）+ 悬浮主 FAB「开始」；日志直接铺满页面，状态改为**居中状态卡浮层**（检测中/未开启无线调试/未配对/连不上，四态各带独立引导按钮）；二级（⋮ 菜单）：导出到文件 / 缓冲区 / 使用方法 |
| **实时页** | 高频行（⏸ 暂停 · ⏹ 停止 · 选进程 · ⌕ · ☰ · ⋮）；倒序跟随滚动；速率并入统计行；**PID 跟随**（目标应用被杀/重启自动重建流）；右下角悬浮 FAB |
| **崩溃页** | 前台服务监控崩溃（specialUse）；高频行（**⟳ 读取历史** · ☰ · ⋮）；记录列表（应用图标 + 应用名 + 红色时间 / 红色「类型 · 摘要」/ 灰色包名）；右下角 FAB「开始 / 停止」+ 上方 ⧉ 复制 / 🗑 清空（有记录才出现）；⋮ 菜单：读取历史 / 分享 / 今天⇄近7天 / **分享诊断信息** / 清空 |
| **导出页** | 导出 `.log` 文件（默认后缀 .log）、缓冲区多选、分享 |
| **连接页** | 设备 mDNS 扫描列表（点击即连）、分屏配对三步引导、配对码+配对端口（可选兜底）、手动填地址、探测通道、重新连接、ADB 公钥查看/写入 |
| **使用方法页** | 三步上手 + 分屏配对两张实拍图示 + 日常使用 + 排障清单 |
| **设置页** | v4 卡片式四组：**①连接**（连接状态 + 配对状态 + 重新配对/清地址）、**②外观**（深色主题 / 动态取色 / 语言）、**③诊断**（上次崩溃报告 / 运行日志）、**④关于**（当前版本 + 检查更新） |
| **启动智能检查** | 首页启动/回前台自动检测：无线调试开关 → mDNS 端口自动修正（失败回滚，防存档污染）→ 结果**四分类**（可连接 / 端口已更新 / 未配对 / 未开启 / 不可达），UI 分别给不同引导 |

---

## 3. 技术栈

| 项 | 版本/说明 |
|---|---|
| 语言/UI | Kotlin 2.2.20 + Jetpack Compose（BOM 2025.08.00，Material3，material-icons-extended） |
| 构建 | AGP 8.7.3 / Gradle 9.3.0 / JDK 17 |
| DI | Hilt 2.57.1（KSP 2.2.20-2.0.2） |
| 无线配对 | `com.flyfishxu:kadb-android:1.3.0`（仅配对环节，见 §5.6） |
| 抓取协议 | **自研内嵌 ADB**（`core/adb` 包：协议帧/TLS 传输/logcat 流） |
| 网络 | OkHttp 4.12.0（HostBridge 通道）、kotlinx-serialization 1.9.0 |
| 协程 | kotlinx-coroutines 1.10.2（`resolutionStrategy.force` 统一，防 Kadb 传入高版本） |
| 存储 | DataStore Preferences 1.1.7 + security-crypto 1.1.0-beta01 |
| SDK | compileSdk 35 / targetSdk 35 / **buildToolsVersion 锁定 34.0.0**（沙箱限制，见 §8.4） |
| 镜像 | `settings.gradle.kts` 优先走腾讯云/华为云 Maven 镜像（沙箱网络受限） |

---

## 4. 代码结构

```
app/src/main/java/com/loglab/app/                       68 个文件 / ~11.8k 行
├── App.kt / MainActivity.kt          # Hilt 入口 + 单 Activity（AppCompatActivity，为 per-app locale）
├── di/AppModule.kt                   # Hilt 全局提供
├── core/
│   ├── adb/                          # ★ 内嵌 ADB 协议栈
│   │   ├── AdbProtocol.kt            #   ADB 协议帧（CNXN/AUTH/OPEN/WRTE）
│   │   ├── AdbTransport.kt           #   TLS socket 传输
│   │   ├── AdbConnection.kt          #   连接会话与流复用
│   │   ├── AdbBackend.kt             #   ★ BuiltinAdbBackend + KadbAdbBackend 双实现
│   │   ├── AdbPairing.kt             #   SPAKE2 无线配对（调 Kadb）
│   │   ├── AdbKeyStore.kt            #   配对密钥持久化（配对一次永久用）
│   │   ├── KadbCertPersistence.kt    #   Kadb 证书持久化桥接（进程重启必恢复，否则证书被拒）
│   │   └── NsdDiscovery.kt           #   ★ mDNS 发现（三种服务类型，缺一不可）
│   ├── channel/                      # 双通道抽象
│   │   ├── Channel.kt / AdbChannel.kt / BridgeChannel.kt
│   │   └── ChannelManager.kt         # autoConnect(policy) 统一入口
│   ├── bridge/                       # HostBridge HTTP 通道（备用）
│   ├── connect/
│   │   ├── StartupCheck.kt           # 启动智能检查状态机（sealed class 结果）
│   │   └── DevSettingsLauncher.kt    # ★ 三级回退跳转开发者选项
│   ├── crash/                        # CrashEvent / CrashParser / CrashStore
│   ├── apps/AppInfoProvider.kt       # 应用图标+名称（PackageManager）+ 运行/前台（shell）
│   ├── export/LogExporter.kt         # 导出 .log
│   ├── logcat/                       # LogcatCommand / LogParser / LogPriority / PidResolver
│   ├── report/
│   │   ├── AppLogger.kt              # 运行日志（环形缓冲 + app.log），含 diagnostics()
│   │   └── CrashReporter.kt          # App 自身崩溃落盘
│   └── update/UpdateManager.kt       # 应用内更新检查/下载/安装
├── data/
│   ├── model/AppSettings.kt          # DataStore 偏好
│   ├── model/LogEntry.kt             # ★ rawWithoutTimestamp：列表不显示行首时间戳
│   └── repository/SettingsRepository.kt / LogRepository.kt
├── service/
│   ├── LogTailService.kt             # 实时跟踪前台服务（specialUse）
│   ├── TailSession.kt                # 含 watchPid：PID 跟随
│   └── CrashMonitorService.kt        # ★ 崩溃监控前台服务
└── ui/
    ├── LogcatApp.kt                  # ★ 导航：4 tab + 二级路由（无底栏）
    ├── theme/Theme.kt                # ★ V4 设计令牌 + LogColors + LocalV4Dark
    ├── capture/ tail/ crash/ settings/ connect/ guide/ export/ logview/ onboarding/
    └── components/
        ├── V4Components.kt           # ★ v4 组件主力（顶栏/高频行/FAB/菜单/RefreshOnResume）
        ├── V4StatusComponents.kt     #   居中状态卡一族
        ├── V4Settings.kt             #   设置页卡片一族
        ├── SearchSheet.kt            #   搜索底部面板
        ├── FilterSheet.kt            #   筛选底部面板
        ├── AppPickerDialog.kt        #   应用选择器底部面板（AppPickerSheet）
        ├── EllipseTextField.kt       #   胶囊输入框
        ├── LogListView.kt / LogLineSheet.kt / CopyableText.kt
        ├── ChannelBar.kt / FilterDropdown.kt
        └── (已删除：HomeStatusBar.kt / CfgChip.kt / PackagePickerDialog.kt)
```

资源：`res/drawable-nodpi/`（猫头 logo、使用方法两张实拍图）；adaptive icon：白底 + PNG 前景。

---

## 5. 核心机制详解

### 5.1 连接链路（最重要）

```
用户打开「无线调试」
        │
        ▼  首次
[mDNS 扫描] ──发现──▶ 连接页设备列表（点击即连）
[NsdDiscovery]        服务类型（三个都订阅，缺一不可）：
                      _adb-tls-connect._tcp. → TLS_CONNECT（可抓日志）
                      _adb-tls-pairing._tcp. → PAIRING（仅配对，且只有弹窗打开时才广播！）
                      _adb._tcp.             → PLAIN
        │
        ▼ 首次配对（只需一次）
连接页输入 6 位配对码（端口自动扫描，扫不到可手动填弹窗端口）
        │  AdbPairing.pair(host, port, code)  ← Kadb SPAKE2 over TLS
        ▼
配对成功 → AdbKeyStore 持久化密钥 → 自动扫 adbd 端口 → 回环连接
        │
        ▼ 日常（配对完成后）
打开无线调试 → StartupCheck → mDNS 扫新端口
→ 端口变了自动更新 adbPort 并重连 → 绿色状态点 → 开始抓取
```

> **地址恒为 `127.0.0.1`**（v1.8.4 起）：多版本实测局域网 IP 候补零命中，已删除该路径。
> mDNS **只负责发现端口**，连接一律走回环 —— 与网段无关，换 Wi-Fi 不失效。
> ColorOS 下本机 Wi-Fi IP 常被自家防火墙拦，回环反而通。

### 5.2 启动智能检查（`core/connect/StartupCheck.kt`）

首页启动/回前台触发。结果类型（`StartupCheckResult`）：

| 结果 | 含义 | UI 引导 |
|---|---|---|
| `Ready(host, port)` | 可连接 | 绿点，4 秒后提示消失 |
| `PortUpdated(host, old, new)` | 端口变了，已自动更新 | 绿点 + 提示 |
| `NeedPairing` | 无线调试开着，但没配对 | 🔑 + **去配对** → 连接页 |
| `DebugOff` | 无线调试没开 | ⚠ 红 + **去开启无线调试** → 开发者选项 |
| `NotReachable` | 已配对但连不上 | ⚠ + **去连接** → 连接页 |

内部 `Verify` 枚举（`OK` / `DEAD` / `UNREACHABLE`）用于区分「半死 adbd」：无线调试关掉后 adbd 仍可能接受 TLS 握手但不执行命令，此时判 `DEAD` 并直接下结论「未开启」。

### 5.3 「去开启」的正确跳转（`core/connect/DevSettingsLauncher.kt`）

**踩过的坑**：`StartupCheck` 在 core 层已区分 `DebugOff` / `NeedPairing` / `NotReachable`，但 UI 层把三种情况压成同一条「无线调试未开启」卡片，按钮一律 `onGoConnect` 跳**配对页** —— 于是「无线调试没开」的人被送到配对页，而配对页啥也做不了。

修复：
- 状态卡按三个分支渲染，各自给正确按钮；
- `DevSettingsLauncher.open()` 三级回退打开**开发者选项**：
  1. `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`
  2. 显式 `ComponentName` 指向 `DevelopmentSettingsDashboardActivity`
  3. `Settings.ACTION_SETTINGS`
- 全失败则 Toast 提示手动路径（`dev_settings_manual_hint`）。

### 5.4 双通道设计（`core/channel`）

`ChannelManager.autoConnect(ChannelPolicy)` 统一入口；`ChannelPolicy.ADB_ONLY` 为当前唯一策略。
`BridgeChannel`（HTTP HostBridge）保留代码作备用通道。抓日志走 `AdbChannel`。

**`AdbChannel.orderedBackends()` 的关键决策**：
```kotlin
if (settings.current().adbPaired) listOf(kadb) else listOf(builtin)
```
无线调试是 ADB over **TLS**，自研明文协议连不上；因此**配对成功后只走 Kadb，绝不回退明文**。

### 5.5 ColorOS 配对经验（血泪史，务必读）

1. **配对端口只在系统「使用配对码配对设备」弹窗打开期间广播** —— 弹窗一关，端口即失效。
2. **ColorOS 上切走 App（哪怕系统弹窗还开着），配对码和端口都会刷新**（原生 Android 不会）。
   → 最终方案是**分屏/自由窗口**：先把本 App 挂成小窗，再开配对弹窗，全程同屏无切换。
3. 悬浮窗方案（SYSTEM_ALERT_WINDOW）**已实现过又被移除**：ColorOS 对侧载应用启用
   Android 13+「受限设置」，悬浮窗开关置灰无法授权。v1.2.0 已彻底删除悬浮窗代码，**勿再引入**。
4. mDNS 曾有一个**致命 bug**（v1.2.1 修复）：`NsdDiscovery` 没订阅 `_adb-tls-pairing._tcp.`，
   导致配对端口永远扫不到。现已修复 + 手动填端口兜底。

### 5.6 Kadb 依赖注意事项（防闪退，重要）

`com.flyfishxu:kadb-android` 是 KMP 库，Android 实际构件是 `kotlinx-coroutines-core-jvm`。
**绝对不要 exclude kotlin-stdlib / kotlinx-coroutines-core-jvm** —— 排除后依赖树看着正常，
但 dex 里没有协程类，运行期 `ClassNotFoundException: StateFlowKt` 闪退。

兜底：`app/build.gradle.kts` 尾部有 **`verifyDexIntegrity`** 任务，构建后自动校验 dex 关键类
（coroutines / core.adb / core.channel / MainActivity），缺类直接构建失败。
协程版本由 `resolutionStrategy.force(...1.10.2)` 统一。

### 5.7 前台服务

`LogTailService`（实时跟踪）与 `CrashMonitorService`（崩溃监控）均为
`foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明
（targetSdk 34+ 要求）。通知渠道在各自 Service 内创建。

### 5.8 启动抓取与 PID 跟随（v1.7.0，仍生效）

**启动抓取**（`LogRepository.captureStartup` + `LogcatCommandBuilder.buildStartup`）：
刻意不用 `logcat -c`（破坏性，会抹掉上次崩溃现场），改用 `logcat -T 1`（只回放最近 1 行并持续跟随）；
同时每 400ms 轮询目标进程 PID，出现后继续抓 `startupTailMs`（默认 5s），返回全窗口行 + 启动点下标。
注意 `buildStartup` **不带 `--pid`**（目标进程尚未出现）。

**PID 跟随**（`TailSession.watchPid`）：实时页指定包名时，旧 `--pid` 流在应用被杀/重启后收不到新日志。
`watchPid` 每 2s 轮询 PID 集合，需**连续两次拿到同一新集合**（防启停瞬间抖动）才重建流。

---

## 6. 崩溃监控链路（本次重点，务必读完）

这条链路踩的坑最多，是本轮修复的核心。

### 6.1 整体数据流

```
logcat -b crash -v time -T "<当天0点>"
        │  流式（CrashMonitorService，前台服务）
        ▼
   CrashParser.feed(line)  ── 聚合崩溃块，识别 FATAL EXCEPTION / Fatal signal / ANR in
        │
        ▼  CrashEvent
   CrashStore.add(event)   ── 7 天窗口过滤 + 内容去重 + JSON 持久化 + StateFlow
        │
        ▼  events: StateFlow<List<CrashEvent>>
   CrashViewModel.filteredEvents (derivedStateOf)  ── 再按「今天 / 近7天」页签裁剪
        │
        ▼
   CrashScreen 列表
```

### 6.2 ★ 尾部静默问题（「监控到了但不显示」的根因之一）

**`logcat -b crash` 在一条崩溃写完后会长时间静默**（可能永远没有下一行）。
而 `CrashParser.feed()` 只在**下一行到达**或**流结束**时才产出事件 —— 于是：

> 用户点开始监控 → 切出去闪退目标应用 → 切回 LogLab，事件还压在解析器的 `current` 块里，压根没进 store。

**为什么「读取历史」能看到**：`logcat -b crash -d` 是**会正常结束**的一次性命令，结束时 `flush()` 把块交出来了。这正是该 bug 的表现特征。

**修复**：新增 `CrashParser.isSegmentBoundary(line)`：

```kotlin
fun isSegmentBoundary(line: String): Boolean {
    val cur = current ?: return false
    val trimmed = line.trimEnd('\n', '\r').trimStart()
    if (!STAMP_PREFIX.containsMatchIn(trimmed)) return false
    val key = TAG_PID.find(trimmed)?.let { it.groupValues[2] to it.groupValues[3].toInt() }
        ?: return true                       // 无 tag 的新时间戳行 → 新段
    if (key != currentKey) return true       // 换了 tag/pid → 新段
    return isCrashStart(trimmed)             // 同 tag 的新崩溃起点 → 新段
}
```

- **只做判定，不动 `current`** —— 保持 `feed()` 单独可用的语义。
- 续行（裸堆栈行、同 tag 后续 log）一律返回 `false`，不会误判。
- 调用方（`CrashMonitorService`）判定为 `true` 后自行 `flush()` 取件。

服务里的调用顺序**必须是边界判定在 `feed` 之前**：

```kotlin
.collect { line ->
    // feed 会「用新行结束旧块」，等它返回事件时新行已被吃掉，
    // 无法区分「刚结束的崩溃」与续行 —— 所以先判边界。
    if (parser.isSegmentBoundary(line)) parser.flush()?.let { publish(it) }
    parser.feed(line)?.let { publish(it) }
}
parser.flush()?.let { publish(it) }
```

### 6.3 ★ UI 不刷新（根因之二）

监控跑在**前台服务**里。用户切出去再切回来时，界面**没有任何重组触发点**（`filteredEvents` 的输入没变），列表停在旧快照上。

**修复 A —— `RefreshOnResume`（`ui/components/V4Components.kt`）**：

```kotlin
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
```

只在 `ON_RESUME` 触发，**不轮询**。且 `CrashViewModel.refresh()` **只在 `events` 数组真的换了引用时**才推进 `listEpoch`，避免正滚动列表时被无谓重建、丢滚动位置。

**修复 B —— 列表锚点（`listAnchor`）**：`LazyColumn` 的 item key 带上锚点：

```kotlin
key = { _, e -> "$anchor|${e.time}|${e.packageName}|${e.type}" }
```

覆盖「近 7 天」页签下新记录**插在列表中段**（而非头部追加）时的重绘盲区。

### 6.4 ★ 清除按钮无效（⚠️ 由 6.3 的修复引入的回归）

6.3 的锚点方案有个副作用：`clear()` 走的是老的 `store.clear()`，**没推进锚点**。
清空后 `events` 确实空了，但 LazyColumn 的 key 集合与重建前有交集 → Compose 认为那些项还活着 → 复用旧布局 → **界面看起来毫无变化**。

**修复**：`CrashViewModel.clear()` 里同时推进锚点：

```kotlin
fun clear() {
    store.clear()
    lastSeen = events.value   // 同步快照，避免 refresh() 立刻又推一次锚点
    listEpoch++               // 整表替换，直接换锚点让 key 前缀整体失效
}
```

> **教训**：给 `LazyColumn` 加带状态的 key 时，**所有**会改变列表内容的数据操作路径都得推进那个状态，
> 漏掉任何一条就是这种「点了没反应」的静默 bug。新增清空/导入/删除类操作时请一并检查。

### 6.5 崩溃页图标不显示

两个独立原因叠加：

**(a) 取图标的方法不对。** 原来用 `pm.getApplicationIcon(pkg)` —— 这个方法在部分应用上**不报错，但会返回框架内置的默认图标**（一个通用小方块）。

```kotlin
// 现在：显式 loadIcon，并把「真的没图标」变成明确的 null
fun icon(packageName: String, sizePx: Int = 0): Drawable? = runCatching {
    val info = pm.getApplicationInfo(packageName, 0)
    val drawable = info.loadIcon(pm)
    if (sizePx > 0) drawable.setBounds(0, 0, sizePx, sizePx)
    if (info.icon == 0 && drawable === pm.defaultActivityIcon) null else drawable
}.onFailure { logger.log("APPS", "加载图标失败 $packageName：${it.message}") }.getOrNull()
```

**(b) 位图每帧重建。** `Image` 的 `remember` key 原来是 `Drawable` **实例**，但 ViewModel 每次重组都会重新调用 `icon()` 拿到**新对象** → 位图反复重建（闪烁 + 掉帧）。
现在 key 用**包名**，并按 96px 解码（32dp 在 3x 屏上的像素量级）。

**（c）占位块升级**：拿不到图标时显示**应用名首字母 + 主色淡底**（与进程选择器一致），而不是灰底「?」。

### 6.6 诊断信息分享（新增）

排查「监控到了但不显示」时，光看界面无法判断卡在哪一环。⋮ 菜单新增 **「分享诊断信息」**：

`AppLogger.diagnostics(context, crashDump)` 打包：
```
===== LogLab 诊断信息 =====
生成时间 / 应用版本 / 设备信息
===== 崩溃记录（内存）=====
===== 运行日志 =====
```

同时 `CrashMonitorService.publish(event)` 对**每一条解析出的崩溃**写日志，明确记录
**包名 · 类型 · 时间 · 是否入库（已存在/超期跳过）** —— 去重拦截和 7 天窗口拦截都能直接看出来。

### 6.7 崩溃记录的去重与保留

- **保留窗口 7 天**（`CrashStore.RETENTION_DAYS`）：入库、加载、常驻跨天三处都过滤。
- **去重键**：`"${time}|${packageName}|${type}|${stack.hashCode()}"`。
- **加载时双重过滤**：丢掉无包名的残缺记录（旧版解析产物）+ 超期记录。
- **上限** `MAX_EVENTS = 200`。

> ⚠️ **清空的语义边界**：清空只清 **App 内存/JSON 里当前这批记录**，**不会**动 logcat 的 crash 缓冲区。
> 所以清完再点「读取历史」，之前的崩溃会被重新读回来。这是设计使然（缓冲区归系统管，App 无权限清）。
> 若产品上需要「清空后不再重复导入」，可加时间水位线，目前**未实现**。

---

## 7. v4 UI 体系（必读）

### 7.1 三页统一交互骨架

首页 / 实时页 / 崩溃页共用同一套骨架：

```
┌─────────────────────────────────┐
│ V4TopBar：标题 + 7dp 状态点      │  ← 状态点可点，进连接页
├─────────────────────────────────┤
│ V4QuickActionBar（高频行）       │  ← 按使用频率排序；右端 spacer 把 ⋮ 推最右
│   34dp 圆钮 / 胶囊 + ⋮          │
├─────────────────────────────────┤
│                                 │
│   V4StatsLine（统计行，小字）    │
│   内容区（列表 / 居中状态卡）    │
│                                 │
│                      ┌──┐       │  ← V4FabColumn：次级 40dp 圆钮（有内容才出现）
│                      │⧉ │       │
│                      │🗑│       │
│                      ├──┤       │
│                      │开始│      │  ← V4CaptureFab：56dp 主 FAB，**永远绘制**
│                      └──┘       │
└─────────────────────────────────┘
```

**★ `V4FabColumn` 的坑（已修复，勿重蹈）**：

```kotlin
/**
 * [showActions] 只控制**次级按钮**（列在前面那些 40dp 小圆钮）的显隐，
 * 主 FAB（[content] 的最后一项）**永远绘制**。
 *
 * 这里踩过坑：最初把门控套在整个 content 上，结果是
 * 「没日志 → 整列消失 → 连主按钮都没了」，
 * 而空列表恰恰是最需要主按钮的时候。
 */
```

调用点的正确写法：`if (内容非空) { ⧉ / 🗑 }` **只包次级按钮**，主 FAB 无条件绘制。

### 7.2 ★ 设计令牌与深浅色（本次修复重点）

所有 v4 组件一律走 `V4` 对象取色（`ui/theme/Theme.kt`），**不要写死颜色**。

**踩过的坑**：令牌原来用 `isSystemInDarkTheme()` 判断深浅 —— 读的是**系统**开关。
但 App 允许在设置页单独开深色，于是「系统浅色 + App 深色」时：
`MaterialTheme` 走深色 colorScheme（深底），令牌却返回浅色值（`Text = #1A1C1E` 深灰字）
→ **深灰字压深底，顶栏标题 / 分组标题 / 空态提示全部看不见**。

**修复**：令牌改为读 `LocalV4Dark`（由 `LogLabTheme` 下发当前**生效**的深浅），保证令牌与 `colorScheme` 永远同源。

```kotlin
internal val LocalV4Dark = staticCompositionLocalOf { false }
private val dark: Boolean @Composable get() = LocalV4Dark.current

val Bg: Color @Composable get() = if (dark) BgDark else BgLight
val Text: Color @Composable get() = if (dark) TextDark else TextLight
// ... Surface / Surface2 / Muted / Primary / Line / PrimarySoft / SwitchOff 同构

// 深色下需要单独提亮的四个（浅色值在深底上会「糊」掉或像可用按钮）
val IconIdleResolved: Color @Composable get() = if (dark) Color(0xFFB9BDC6) else IconIdle
val DisabledResolved: Color @Composable get() = if (dark) Color(0xFF3A3E46) else Disabled
val DisabledTextResolved: Color @Composable get() = if (dark) Color(0xFF6B7280) else DisabledText
val SubtleResolved: Color @Composable get() = if (dark) Color(0xFF8A8F99) else Subtle
```

`LogLabTheme` 里下发：

```kotlin
MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = {
    CompositionLocalProvider(V4.LocalV4Dark provides darkTheme) { content() }
})
```

另有 `SideEffect` 同步状态栏图标外观（`isAppearanceLightStatusBars = !darkTheme`）——
`enableEdgeToEdge()` 只在 `onCreate` 按系统深浅决定图标颜色，App 内运行时切换不会跟随。

**深色下的四个坑（血泪）**：
1. 令牌必须与 `colorScheme` 同源，别用 `isSystemInDarkTheme()`。
2. `DisabledText` 浅色值 `#969BA5` 在深底上**过亮**，看着像可用按钮 → 深色要压暗。
3. `Disabled` 浅色值 `#D9DBE1` 同理。
4. `IconIdle` 浅色值 `#5A6070` 在深底上对比度过低 → 深色提亮到 `#B9BDC6`。

### 7.3 edge-to-edge 与状态栏避让

- `MainActivity` 调 `enableEdgeToEdge()`；
- `LogcatApp` 的 `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))`；
- v4 页面用 `V4TopBar` 内部 `windowInsetsPadding(WindowInsets.statusBars)` 避让；
- 二级页（连接/使用方法/导出/logview）仍用 Material3 `TopAppBar`（自带 inset）。

**坑**：`V4TopBar` 最初没处理 inset，导致标题压在系统状态栏图标下面。`V4TopBar` 有 `applyStatusBarInset: Boolean = true` 逃生口，供已自行处理的页面关闭。

### 7.4 组件清单

| 文件 | 导出 |
|---|---|
| `V4Components.kt` | `V4TopBar` `V4StatusDot` `V4QuickActionBar` `V4ProcessChip` `V4RoundIconButton` `V4StatsLine` `V4CaptureFab` `V4FabAction` `V4FabColumn` `V4OverflowMenu` `V4MenuItem` `V4MenuSeparator` `V4OptRow` `V4OptChip` `V4Field` `V4SheetGrip` `V4SheetHeader` `V4SheetActions` `V4SheetButton` `V4SheetColumn` `V4Tagline` `V4CircleIconSurface` **`RefreshOnResume`** |
| `V4StatusComponents.kt` | `V4CenterStatus` `V4StatusCard` `V4Spinner` `V4StatusTitle` `V4StatusSubtitle` `V4StatusActions` `V4StatusButton` |
| `V4Settings.kt` | `V4SettingsSectionTitle` `V4SettingsCard` `V4SettingsDivider` `V4SettingsIcon` `V4SettingsRow` `V4SettingsValue` `V4Switch` `V4SettingsBadge` |
| `SearchSheet.kt` / `FilterSheet.kt` / `AppPickerDialog.kt` | `SearchSheet` / `FilterSheet` / `AppPickerSheet` |

### 7.5 本地化与格式化（踩过的坑）

- **格式串类型必须对齐**：实时页统计行 `ratePerSecond` 是 `Float`，曾写成 `%2$d 行/秒` →
  运行期 `IllegalFormatConversionException: d != java.lang.Float` 直接崩溃。现为 `%2$.1f`。
  **改动任何带 `%` 的字符串前，先回调用点核对参数类型。**
- **颜色判断不能依赖中文关键词**：UI 曾用 `contains("失败")` 决定红/蓝，切英文就失效。
  现由 `CrashStore.messageIsError` 显式传布尔。
- **语言存储由 AppCompat `autoStoreLocales` 自管**，**勿再往 `AppSettings` 加 `language` 字段**。

---

## 8. 构建与发布

### 8.1 环境要求

- **JDK 17** —— `gradle.properties` 里 `org.gradle.java.home=/opt/jdk-17.0.2` 是**沙箱路径**，
  ⚠️ **提交到你的环境前必须改回本机 JDK 路径**（或用 `-Dorg.gradle.java.home=` 覆盖）。
- **Android SDK**：platform 35 + **build-tools 34.0.0**（`buildToolsVersion` 已锁定）。
  `local.properties` 里 `sdk.dir` 需按本机路径自建（**不入库**）。
- **Gradle**：仓库**没有 wrapper**（`gradlew` 存在但 `gradle/wrapper/` 无 jar），用系统 `gradle`。
- `gradle.properties` 的沙箱专用配置（内存/网络），移植到资源充足的机器可放宽或删除：

```properties
# 沙箱 cgroup 内存上限 8GB：daemon(4.6G heap+1G meta) + kotlin daemon(1.5G) 必须 < 8G，
# 否则 R8 阶段触发 cgroup OOM kill，表现为 "Gradle build daemon disappeared unexpectedly"
org.gradle.jvmargs=-Xmx4608m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8
org.gradle.java.home=/opt/jdk-17.0.2     # ← 换机器必改
org.gradle.parallel=false
org.gradle.workers.max=1
kotlin.daemon.jvmargs=-Xmx1536m
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
# 沙箱网络受限：禁止 AGP 联网探测/下载缺失的 SDK 组件
android.builder.sdkDownload=false
kotlin.code.style=official
```

### 8.2 构建命令

```bash
cd /workspace/loglab          # ⚠️ 必须显式 cd，Bash 每次调用后 CWD 会重置

gradle --stop                 # ★ 每次构建前先停 daemon（见 8.4）
gradle assembleRelease verifyDexIntegrity --max-workers=1

# 产物：app/build/outputs/apk/release/app-release.apk
```

`verifyDexIntegrity` 会依赖 `assembleRelease` 并校验 dex 关键类，成功时打印：

```
[verifyDexIntegrity] release/app-release.apk OK (10315 个类，8 项必需类齐全)
```

### 8.3 签名与版本号

- **签名**：keystore **不入库**。`app/keystore/debug.jks` 本地保留（`.gitignore` 已排除）。
  密码优先取环境变量 `KS_PASS` / `KEY_PASS`，其次根目录 `keystore.pw.local`（也已 gitignore），
  都没有则构建签名失败 —— **密码不硬编码进仓库**。CI 由 GitHub Actions Secrets 注入。
- **版本号约定**：功能批次 `versionName +0.1`、bug 修复 `+0.01`，`versionCode` 恒 `+1`。
- **当前状态：`versionCode = 27` / `versionName = "1.9.3"`**（工作树含 `Unreleased` 改动，**未升版**）。

### 8.4 构建已知坑（沙箱实测，必读）

1. **Gradle daemon OOM**：R8 阶段 daemon 被杀，报 `Gradle build daemon disappeared unexpectedly`。
   处理：`gradle --stop` + `pkill -f "kotlin-compiler-embeddabl[e]"`（`[e]` 防止 pkill 匹配到自身）后重跑。
   堆参数是按 8G cgroup 调的，内存更大的机器可放宽。
2. **daemon 死锁**：上次异常退出后可能复用坏 daemon 卡死（长时间 0 CPU、build 目录无产出）。
   **每次构建前先 `gradle --stop`**。
3. **Bash CWD 重置**：shell 每次调用后回到起始目录，gradle 命令必须显式 `cd /workspace/loglab`。
4. **build-tools 只有 34**：AGP 想下 35 会失败，靠 `buildToolsVersion = "34.0.0"` 绕过，**勿删**。
5. **lint 已关闭 release 拦截**：AGP 8.7.3 内置 lint 的 UAST 桥与 Kotlin 2.2 的 analysis API 不兼容
   （`NonNullableMutableLiveDataDetector` 抛 `IncompatibleClassChangeError`），故
   `disable += "NullSafeMutableLiveData"` + `checkReleaseBuilds = false` + `abortOnError = false`。

---

## 9. 版本历史（1.9.x）

| 版本 | code | 主要内容 |
|---|---|---|
| **Unreleased** | 27 | 崩溃链路批量修复：**尾部静默**（`isSegmentBoundary` 提前产出）/ **UI 回前台不刷新**（`RefreshOnResume` + 列表锚点）/ **清除无效**（锚点未推进的回归）/ **图标不显示**（`getApplicationIcon` → `loadIcon`，位图缓存 key 换包名）；**深色模式根因修复**（令牌改读 `LocalV4Dark`，不再读系统开关）；「去开启」直达开发者选项（`DevSettingsLauncher`）；新增**诊断信息分享**。版本号按需求**保持 27 / 1.9.3 未升** |
| 1.9.3 | 27 | 启动抓取开关恢复显示（改回显式文字标签）；主 FAB「抓取」→「开始」消歧；高频行按使用频率重排（三页统一）；崩溃页 FAB「监听」→「开始」；**日志列表不再显示行首时间戳**（详情/复制/导出仍带） |
| 1.9.2 | 26 | 崩溃页清空不刷新（`filteredEvents` 裸 getter → `derivedStateOf`）；复制/清空下沉到主 FAB 上方（有内容才显示）；启动抓取移出 ⋮ 菜单 |
| 1.9.1 | 25 | 实时页崩溃 `IllegalFormatConversionException`（`%2$d` → `%2$.1f`）；v4 顶栏与状态栏重叠（补 `windowInsetsPadding`） |
| 1.9.0 | 24 | ★ **UI 全量改为 v4 布局**：极简顶栏 + 状态点、独立高频行、居中状态卡浮层、悬浮 FAB、搜索/筛选/进程选择底部面板化、⋮ 溢出菜单、崩溃页扁平行；新增 `V4` 设计令牌与整套共享组件；运行期状态文案本地化补齐；`messageIsError` 替代中文关键词判色；删除 `HomeStatusBar` / `CfgChip` / `PackagePickerDialog` |

更早版本（1.0.0 – 1.8.4）见 `CHANGELOG.md`。

---

## 10. 已知问题与待办建议

1. **版本号待升**：工作树含 `Unreleased` 改动，`versionCode/Name` 仍是 27 / 1.9.3（需求方要求暂不升）。
   下次发版务必升，并同步 `CHANGELOG.md`。
2. **清空的语义**：清空不涉及 logcat crash 缓冲区，清完点「读取历史」会重新读回（见 §6.7）。
   若需「不再重复导入」需加时间水位线。
3. **`gradle.properties` 的沙箱路径**：`org.gradle.java.home=/opt/jdk-17.0.2` 换机器必改。
4. **首页单次抓取内存**：maxLines 上限 100000，超长日志注意内存（实时页已做 1000 条裁剪，首页未分页）。
5. **mDNS 与多设备**：扫描会把本机服务与其他设备混在一起（列表有 host 区分），
   未来若支持「抓别的手机」需显式区分目标设备。
6. **大文件导出**：走 FileProvider 分享，超大日志（>10 万行）未做流式优化。
7. **keystore**：现用本地 debug keystore 签 release，**正式分发前务必换正式证书**。
8. **国际化长尾**：框架与核心页面已双语，连接页/使用方法页/导出页/LogView 仍有中文硬编码，
   后续抽入 `res/values-en`。
9. **`LazyColumn` key 与状态一致性**：见 §6.4 的教训 —— 新增任何改变列表内容的操作，
   都要检查是否需要推进 `listEpoch`。
10. **可选迭代方向**：日志高亮规则自定义、按进程/Tag 保存筛选预设、导出为 zip + 按级别分文件、
    Play 商店合规化（前景 icon 512、隐私政策页）。

---

## 11. 快速上手（接手第一天）

```bash
# 1. 解压源码后，检查/修改两处路径
#    - gradle.properties: org.gradle.java.home（换成本机 JDK 17）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建（必须先 cd + 先 stop）
cd /path/to/loglab
gradle --stop
gradle assembleRelease verifyDexIntegrity --max-workers=1

# 3. 安装到真机（Android 8+，arm64）
adb install -r app/build/outputs/apk/release/app-release.apk

# 4. 真机首次使用
#    开发者选项 → 无线调试 → 开启
#    App 首页点状态点 → 连接页 → 按分屏引导配对（详见使用方法页图文）

# 5. 复现/排查崩溃监控问题
#    崩溃页 ⋮ → 分享诊断信息（含运行日志，能看出流有没有起来、解析器认出了什么）
```

有任何与本文冲突的实现细节，**以代码为准**；本文档对应 **1.9.3（versionCode 27）+ Unreleased 工作树**。
