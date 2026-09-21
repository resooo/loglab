# LogLab 支持 Shizuku 通道 —— 详细技术方案

> 版本：v1.0（2026-09-14）
> 目标版本：v1.10.0
> 结论摘要：**可行，但必须走 UserService 路径**——`Shizuku.newProcess()` 在当前 API 中已不可用。

---

## 一、结论先行

| 问题 | 结论 |
|------|------|
| 能否支持 Shizuku？ | ✅ 能，现有 `Channel` 抽象层已预留位置 |
| 最省事的做法（直接调 `Shizuku.newProcess`） | ❌ **不可行**，见下方关键发现 |
| 唯一可行架构 | ✅ **Shizuku UserService + AIDL 回传** |
| 工作量 | 中等偏大：约 8 个新文件 + 6 处改动，预估 2~3 天 |
| 最大风险 | R8 混淆破坏 AIDL；Android 16 上 Shizuku 稳定性 |

---

## 二、关键发现：`newProcess` 已不可用

**这是本次调研最重要的结论，直接决定了架构选型。**

从 Maven 中央仓库拉取 `dev.rikka.shizuku:api:13.1.5` 官方源码验证：

```java
// rikka/shizuku/Shizuku.java:491
/**
 * @deprecated This method should only be used when you are transitioning from "su".
 * Use {@link Shizuku#transactRemote(Parcel, Parcel, int)} for binder calls and
 * {@link Shizuku#bindUserService(UserServiceArgs, ServiceConnection)}
 * for complicated requirements.
 * <p>This method is planned to be removed from Shizuku API 14.
 */
private static ShizukuRemoteProcess newProcess(@NonNull String[] cmd,
                                               @Nullable String[] env,
                                               @Nullable String dir) { ... }
```

两个致命点：

1. **`private`** —— 外部代码**无法调用**。早期版本（12.x）它是 public，网上大量教程仍按旧签名写，照抄会编译失败。
2. **`@deprecated` + 计划在 API 14 移除** —— 即使想办法反射调用，也是死路。

**可用的公开 API 只有三个：**

| API | 签名 | 适用场景 |
|-----|------|---------|
| `transactRemote` | `public static void transactRemote(Parcel, Parcel, int)` | 调用**系统服务**（需 Binder 接口 + 隐藏 AIDL） |
| `bindUserService` | `public static void bindUserService(UserServiceArgs, ServiceConnection)` | **执行任意 shell 命令** ✅ |
| `getUid` / `getVersion` / `pingBinder` | 辅助方法 | 状态检测 |

→ **执行 `logcat` 这类任意 shell 命令，唯一路径是 UserService。**

---

## 三、现有代码盘点（改造基础）

### 3.1 抽象层已就绪，但被架空

`core/channel/` 已有完整的双通道形态：

```
Channel.kt           ← 接口：probe / execute / executeStream / label / close
├── AdbChannel.kt    ← 唯一活跃实现
├── BridgeChannel.kt ← 完整实现，但【无人注入、无人激活】
└── ChannelManager.kt← 构造仅注入 adbChannel，_activeChannel 只可能指向 ADB
```

**`ChannelManager` 的关键缺陷**（改造必改点）：

```kotlin
@Singleton
class ChannelManager @Inject constructor(
    val adbChannel: AdbChannel          // ❌ 只有 ADB
) {
    suspend fun autoConnect(policy: ChannelPolicy = ChannelPolicy.ADB_ONLY): Result<ChannelType> {
        if (adbChannel.probe()) {
            _activeChannel.value = adbChannel    // ❌ 硬编码
            ...
        }
        // policy 参数被完全忽略（注释明说"已废弃"）
    }
}
```

### 3.2 死代码孤岛（可复用，但不是 Shizuku）

`core/bridge/` 三个文件完整可用，但整条依赖链**无外部入口**：

```
BridgeChannel → BridgeClient → BridgeAuth        （孤岛，无人注入）
                     ↓
              HTTP 127.0.0.1:7980 /api/shell     （HostBridge 语义）
```

⚠️ **重要澄清**：现有 `BridgeChannel` 是 **HTTP HostBridge 客户端**，**不是 Shizuku**。项目里 `grep shizuku|moe.shizuku|rikka` 在 gradle / manifest / 源码中**零命中**。

它的 `executeStream` 是**轮询式**（每 1.5s 拉 `logcat -d -t 800` 去重），与 ADB 的真流式语义不同。

### 3.3 设置项残留

| 项 | 位置 | UI 入口 |
|----|------|---------|
| `bridgeUrl` | `AppSettings:17`（默认 `http://127.0.0.1:7980`） | ❌ 设置页 grep `bridge` 零命中 |
| `channelPolicy` | `AppSettings:18`（默认 `AUTO`） | ❌ 无入口，且被 `autoConnect` 忽略 |
| bridge token | `BridgeAuth` 存 EncryptedSharedPreferences | ❌ 无入口 |

残留文案：`strings.xml` 的 `about_tech` 仍写"支持 ADB 直连与 HostBridge 双通道"（与实际不符）。

### 3.4 Shizuku 必须覆盖的命令清单

从全项目 shell 调用点提取（共 19 处）：

| 类别 | 命令 | 调用点数 |
|------|------|---------|
| logcat 一次性 | `logcat -d` / `-c` / `-b crash -d -T` | 5 |
| logcat 流式 | `logcat -T 1` / 无 `-d` 的 tail | 3 |
| 进程查询 | `pidof` / `ps -A -o PID,NAME` / `pgrep -f` | 7 |
| 包管理 | `pm list packages -3` | 1 |
| 系统状态 | `dumpsys activity activities` | 1 |
| 探活 | `echo probe-ok` | 2 |
| **写系统文件** | 写 `/data/misc/adb/adb_keys` + `chmod`/`chown` | 1 |

⚠️ 最后一项（写 `adb_keys`）**Shizuku 无法直接完成**——见第七节风险分析。

---

## 四、目标架构

```
                    ┌─────────────────────────────┐
                    │      Channel（接口，不变）    │
                    └──────────┬──────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
   AdbChannel            ShizukuChannel         (BridgeChannel 保留或删除)
   （无线 ADB）           （新增，@Singleton）
        │                      │
        │                      │ AIDL
        │              ┌───────▼────────┐
        │              │ AdbShellService │ ← 运行在 Shizuku 进程（shell/root 身份）
        │              │  Runtime.exec   │
        │              └───────┬────────┘
        │                      │
        └──────────┬───────────┘
                   ▼
          ChannelManager（改造：持有两个通道，按策略选择）
```

### 4.1 为什么用 UserService 而不是绑定 `IShizukuService`

`transactRemote` 需要构造 `Parcel` 并手工写入事务码，且 `IShizukuService` 的 AIDL 是**隐藏的**（不在公开 SDK 里），要自己反编译拿到接口定义。**脆弱且不可维护**。

UserService 则是官方设计给"复杂需求"的正规路径：Shizuku 用 shell/root 身份 `startService` 我们的 Service，我们通过正常 AIDL 与它通信。

---

## 五、改造清单（分层）

### 5.1 依赖与配置（3 处）

**① `app/build.gradle.kts`**

```kotlin
dependencies {
    // ... 现有依赖
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
```

> ⚠️ 项目暂无 version catalog（`gradle/libs.versions.toml` 不存在），版本内联即可，与现有风格一致。

**② `AndroidManifest.xml`**

```xml
<!-- 声明所需权限（provider 库的 manifest 里也有，但显式声明更清晰） -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<application>
    <!-- Shizuku UserService：由 Shizuku 以高权限身份启动 -->
    <service
        android:name=".core.shizuku.AdbShellService"
        android:exported="false"
        android:process=":shizuku_service" />
</application>
```

**③ `app/proguard-rules.pro`**（**关键，否则 release 必崩**）

```proguard
# Shizuku UserService 的 AIDL 接口必须保留，否则 R8 会重命名导致 binder 调用失败
-keep class com.loglab.app.core.shizuku.** { *; }
-keep interface com.loglab.app.core.shizuku.** { *; }

# Shizuku API 自身
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
```

### 5.2 新增文件（8 个）

| # | 文件 | 作用 |
|---|------|------|
| 1 | `core/shizuku/IAdbShell.aidl` | AIDL 接口定义 |
| 2 | `core/shizuku/AdbShellService.kt` | UserService 实现，`Runtime.exec` 执行 shell |
| 3 | `core/shizuku/ShizukuShellExecutor.kt` | 单例，持有 AIDL 代理，封装 execute / executeStream |
| 4 | `core/shizuku/ShizukuStatus.kt` | 权限与可用性检测（pingBinder / checkSelfPermission） |
| 5 | `core/channel/ShizukuChannel.kt` | `Channel` 实现，桥接 Executor |
| 6 | `core/shizuku/ShizukuPermissionLauncher.kt` | 权限申请（Activity 结果回调封装） |
| 7 | `ui/settings/ShizukuSection.kt` | 设置页区块：状态 / 申请授权 / 跳转安装 |
| 8 | `di/ShizukuModule.kt` | Hilt provider（或并入现有 `di/AppModule.kt`） |

#### AIDL 接口设计

```aidl
// IAdbShell.aidl
package com.loglab.app.core.shizuku;

interface IAdbShell {
    /** 执行命令并等待结束，返回完整输出 */
    String exec(String command, String[] env, String dir);

    /**
     * 流式执行：回调逐行推送。
     * 注意用 oneway 避免 binder 线程阻塞。
     */
    void execStream(String command, ILineCallback callback);
    void cancelStream(int token);
}

// ILineCallback.aidl
interface ILineCallback {
    void onLine(String line);
    void onExit(int code);
    void onError(String message);
}
```

#### UserService 核心实现要点

```kotlin
class AdbShellService : Service() {
    private val binder = object : IAdbShell.Stub() {
        override fun exec(command: String, env: Array<String>?, dir: String?): String {
            // UserService 已由 Shizuku 以 shell/root 身份启动，
            // 这里直接 Runtime.exec 就具备高权限
            val p = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            return if (err.isBlank()) out else "$out\n[stderr] $err"
        }

        override fun execStream(command: String, cb: ILineCallback) {
            // ⚠️ 必须在独立线程读流 —— binder 线程池有限，阻塞会导致整体卡死
            Thread {
                runCatching {
                    val p = ProcessBuilder("/system/bin/sh", "-c", command).start()
                    p.inputStream.bufferedReader().forEachLine { cb.onLine(it) }
                    cb.onExit(p.waitFor())
                }.onFailure { cb.onError(it.message ?: "unknown") }
            }.start()
        }
    }
    override fun onBind(intent: Intent) = binder
}
```

### 5.3 改造现有文件（3 个）

#### ① `ChannelManager.kt` —— 核心改造

```kotlin
@Singleton
class ChannelManager @Inject constructor(
    private val adbChannel: AdbChannel,
    private val shizukuChannel: ShizukuChannel     // ← 新增
) {
    suspend fun autoConnect(policy: ChannelPolicy = ChannelPolicy.AUTO): Result<ChannelType> {
        _state.value = ChannelState(null, false, detail = "正在探测通道…")

        // 按策略挑选候选顺序
        val candidates = when (policy) {
            ChannelPolicy.ADB_ONLY    -> listOf(adbChannel)
            ChannelPolicy.SHIZUKU_ONLY-> listOf(shizukuChannel)
            ChannelPolicy.AUTO        -> listOf(shizukuChannel, adbChannel)  // Shizuku 更快，优先
        }

        for (ch in candidates) {
            if (runCatching { ch.probe() }.getOrDefault(false)) {
                val label = runCatching { ch.label() }.getOrDefault(ch.type.name)
                _activeChannel.value = ch
                _state.value = ChannelState(ch.type, true, label, ch.detailHint())
                return Result.success(ch.type)
            }
        }

        _activeChannel.value = null
        _state.value = ChannelState(null, false, detail = "无可用的调试通道")
        return Result.failure(IllegalStateException("无可用的调试通道"))
    }
}
```

**同时需要处理的调用点**：6 处直接访问 `channelManager.adbChannel`（绕过 `active()`），应改为 `active()`：

| 位置 | 现状 | 改法 |
|------|------|------|
| `ConnectViewModel:151/155` | `adbChannel.probe()` | 改为按策略探测 |
| `StartupCheck:156/215` | `adbChannel.execute("echo probe-ok")` | 改为 `active()` |
| `CrashMonitorService:109` | `adbChannel.executeStream(...)` | 改为 `active()` |

> ⚠️ 但 `StartupCheck` 的整个流程是**为无线 ADB 设计的**（mDNS 发现、端口探测、配对）。走 Shizuku 时这些步骤**全部不需要**，应提前短路。

#### ② `Channel.kt` —— 扩展枚举

```kotlin
enum class ChannelType { ADB, SHIZUKU, BRIDGE }

enum class ChannelPolicy { AUTO, ADB_ONLY, SHIZUKU_ONLY, BRIDGE_ONLY }
```

#### ③ `StartupCheck.kt` —— 增加 Shizuku 优先分支

```kotlin
suspend fun run() {
    // ★ 新增：Shizuku 可用则直接用它，跳过 mDNS / 端口探测 / 配对全套流程
    if (shizukuStatus.isAvailable() && shizukuStatus.hasPermission()) {
        logger.log("CHECK", "检测到 Shizuku 可用，优先使用 Shizuku 通道")
        channelManager.autoConnect(ChannelPolicy.SHIZUKU_ONLY)
            .onSuccess { logger.log("CHECK", "Shizuku 通道已激活（无需配对与端口探测）"); return }
        logger.log("CHECK", "Shizuku 通道激活失败，回退无线 ADB")
    }
    // ... 原有 ADB 流程不变
}
```

### 5.4 UI 改动（2 处）

- `ChannelBar.kt` / `ConnectScreen.kt`：`state.type` 判定增加 `SHIZUKU` 分支（现只有 `== ADB` 与 else「未连接」，BRIDGE 是死分支）
- `SettingsScreen.kt`：新增「Shizuku」区块

---

## 六、分阶段实施计划

### 阶段 1：打通链路（最小验证）
1. 加依赖 + manifest 声明
2. 写 `IAdbShell.aidl` + `AdbShellService`
3. 写 `ShizukuShellExecutor`（只做 `exec`，不做 stream）
4. 临时在设置页加一个「测试 Shizuku」按钮，执行 `id` 看返回

**验收**：能拿到 `uid=2000(shell)` 或 `uid=0(root)`。

### 阶段 2：接入 Channel 抽象
5. 写 `ShizukuChannel`（`probe` = Shizuku 可用且有权限）
6. 改造 `ChannelManager` 支持通道选择
7. 改 6 处 `adbChannel` 直接引用

**验收**：禁用无线 ADB 后，App 仍能抓日志。

### 阶段 3：流式与性能
8. AIDL 加 `execStream` + 回调
9. `ShizukuChannel.executeStream` 用回调转 `Flow`
10. 验证 `logcat` 实时跟踪是否真流式

**验收**：实时页刷新延迟 < 500ms，无卡顿。

### 阶段 4：UI 与体验
11. 设置页 Shizuku 区块（状态 / 授权 / 安装引导）
12. `ChannelBar` 显示当前通道类型
13. 启动检查短路优化

### 阶段 5：加固
14. ProGuard 规则
15. Binder 死亡监听与自动重连
16. Android 14/15/16 兼容性测试

---

## 七、风险清单

| # | 风险 | 严重度 | 应对 |
|---|------|--------|------|
| 1 | **R8 破坏 AIDL**：release 构建后 binder 调用失败 | 🔴 高 | 必加 keep 规则；构建后实测 release 包（本项目 release 开 `isMinifyEnabled`） |
| 2 | **Android 16 上 Shizuku 超时**：本机已实测 `UserService 执行失败: Timed out waiting for 20000ms` | 🔴 高 | 属 Shizuku 自身问题；需评估是否值得投入，或作为"降级选项"提供 |
| 3 | **无法写 `/data/misc/adb/adb_keys`**：该文件属 `system:shell`，Shizuku 的 shell 身份（uid 2000）可能无权写 | 🟡 中 | 实测确认；若不可用，则自授权功能仍需无线 ADB 通道 |
| 4 | **Binder 线程阻塞**：`execStream` 在 binder 线程读流会耗尽线程池 | 🟠 中高 | 必须用独立线程读流；AIDL 回调用 `oneway` |
| 5 | **进程泄漏**：`logcat` 长驻进程未正确销毁 | 🟠 中 | `close()` 里显式 `destroy()`；记录 token 便于取消 |
| 6 | **UserService 被杀**：Shizuku 重启或用户撤销授权 | 🟡 中 | `addBinderDeadListener` 监听并自动重连 |
| 7 | **`newProcess` 移除焦虑**：网上教程照抄会编译失败 | 🟡 中 | 本方案已规避，只用 `bindUserService` |
| 8 | **与无线调试冲突**：两者同时开启 | 🟢 低 | 策略上 Shizuku 优先，互不干扰 |
| 9 | **SELinux 限制**：UserService 进程的 domain 受限 | 🟡 中 | 大概率无碍（Shizuku 已被广泛使用），需实测 |

### ⚠️ 需要先验证的三个前提

在投入开发前，建议先做**可行性探针**：

1. **本机 Shizuku 是否可用** —— Android 16 上已知有超时问题，先确认能正常授权
2. **UserService 能否启动** —— 用官方 sample 或最小 demo 验证
3. **shell 身份能读写哪些路径** —— 特别是 `/data/misc/adb/adb_keys`

---

## 八、替代方案对比

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **A. Shizuku UserService** | 官方推荐路径；能执行任意 shell；无配对流程 | 需写 AIDL；Android 16 稳定性存疑 | ✅ **推荐** |
| B. `Shizuku.newProcess` | 代码极简（旧教程写法） | **已 private + deprecated，API 14 移除** | ❌ 不可用 |
| C. 保留现有 HTTP HostBridge | 代码已存在，零改动 | 依赖外部服务（非 Shizuku）；轮询非流式 | ⚠️ 可作为第三通道保留 |
| D. Root（`su`） | 能力最强 | 绝大多数用户无 root | ⚠️ 可选补充 |
| E. 只做无线 ADB | 已完善（v1.9.5 含端口探测兜底） | 需配对；关开无线调试后端口会变 | 现状 |

**建议：A 为主，C 保留为可选，E 兜底。**

---

## 九、价值评估（是否值得做）

### 支持的场景

| 场景 | 无线 ADB | Shizuku |
|------|---------|---------|
| 首次使用需配对 | ✅ 需要（含输入配对码） | ❌ 仅需授权一次 |
| 关开无线调试后端口变化 | ⚠️ 需 mDNS/探测兜底 | ✅ 无影响 |
| 无需开启无线调试 | ❌ 必须开 | ✅ 不需要 |
| 重启后恢复 | 需重连 | 自动重连 |

**核心价值：省去配对与端口管理**。v1.9.5 已通过 `PortProbe` 大幅改善端口问题，但**配对流程仍是使用门槛**。

### 成本

- 开发：2~3 天
- 维护：Shizuku 版本升级需跟进
- 依赖：引入 2 个第三方库，APK 增大约 100KB

### 建议

⚠️ **先做可行性探针再决定**。理由：

1. 本机（OnePlus PJX110 / Android 16）已实测 **Shizuku UserService 超时不可用**，若 Shizuku 本身在这台机器上不稳定，投入的收益存疑
2. 无线 ADB 通道经 v1.9.5 修复后已相当可靠（含主动端口探测兜底）
3. Shizuku 需要用户额外安装 Shizuku App 并授权，**用户群覆盖率有限**

**推荐的决策路径**：

```
先花 2 小时做探针验证
    ├─ Shizuku 在本机可用 → 按本方案实施（2~3 天）
    └─ Shizuku 在本机不可用 → 暂缓，或仅做框架预留
```

---

## 十、附：现有代码的可复用资产

| 资产 | 复用方式 |
|------|---------|
| `Channel` 接口 | 直接复用，`ShizukuChannel` 实现它 |
| `ChannelManager` 状态机 | 扩展 `autoConnect` 的选择逻辑 |
| `ChannelPolicy` 枚举 | 加 `SHIZUKU_ONLY` |
| `BridgeChannel` | 作为第三通道保留，或删除（若确定不做 HTTP 桥） |
| `AppSettings.bridgeUrl/channelPolicy` | `channelPolicy` 复用；`bridgeUrl` 视是否保留 C 方案 |
| 现有 `verifyDexIntegrity` 任务 | 扩展覆盖 `core/shizuku/` |
| ProGuard 经验 | 现有规则已 keep coroutines，照此模式加 Shizuku 规则 |

---

## 十一、下一步行动

若要推进，建议按序执行：

1. **探针验证**（2 小时）：加依赖 → 写最小 UserService → 执行 `id` 看 uid
2. **决策**：探针通过则进入阶段 1~5；不通过则仅提交本方案文档归档
3. **实施**：按第六节分阶段，每阶段验收后再进入下一阶段

**关键前置**：需要一台 **Shizuku 可正常工作**的设备做验证。当前设计机（OnePlus PJX110 / Android 16）的 Shizuku 有超时问题，可能无法用于验收。
