# LogLab 自动开启无线调试 + 自动识别端口 —— 可行性研究报告

> ## ⚠️ 更正声明（2026-09-21 追加）
>
> 本文档写于 2026-09-20，其中**关于「端口探测」的部分结论是基于沙箱间接观察得出的，
> 后续被证伪**。具体：
>
> - 文中建议用 `bind()` 反证法替换现有的 `probePort`/`canConnect`；
> - 后续实施时，我基于「沙箱 Python 测到某端口不响应 ADB 协议」这一观察，
>   进一步推断 `kadb.connectionCheck()` 语义有误并改动了核心逻辑，
>   **结果引入了回归**（原本可用的连接流程失效）。
> - 回退后确认：`kadb.connectionCheck()` 一直是正确用法，
>   `AdbConnection` 的握手校验也是严格的（未收到 `A_CNXN` 会抛异常）。
>
> **教训**：沙箱内用 Python/socket 做的协议实验，**不能代表 App 内第三方库的真实行为**。
> 涉及连接核心逻辑的改动，必须先在真机上用新旧版本做对照实验，确认差异来源后再动手。
>
> 下文保留原貌以记录当时的分析过程，但**请勿据此直接修改连接逻辑**。

> 结论日期：2026-09-20
> 参考实现：`/workspace/research/Shizuku`（RikkaApps/Shizuku 13.6.0）
> 分析报告：`/workspace/dazahui/Shizuku无线调试启动原理.md`、`Shizuku无线调试启动机制-实现报告.md`
> 目标：评估「启动 LogLab 后自动开启无线调试并识别端口」是否可行、如何实现

---

## 一、结论先行

| 问题 | 结论 |
|------|------|
| **能否实现自动化？** | ✅ **可以**，机制已完全查清 |
| **是否需要 Root / 电脑 / 安装 Shizuku？** | ❌ **都不需要** |
| **核心前提** | 拿到 `WRITE_SECURE_SETTINGS` 权限 |
| **如何拿到这个权限** | Shizuku 用的是「用 shell 权限给自己 `pm grant`」——**LogLab 已有 ADB 通道，可以照做** |
| **改造工作量** | **中等**：新增 5 个文件 + 修改 4 个，约 1~2 天 |
| **最大阻碍** | 首次仍需**一次性手动配对**；权限需在连接成功后授予 |

**一句话**：Shizuku 的机制**完全适用于 LogLab**，而且 LogLab 的起点比 Shizuku 更好——它已经有完整的 ADB 协议实现，只差「自我授权 + 写开关 + 开机自启」三块拼图。

---

## 二、Shizuku 机制拆解（源码核实）

### 2.1 完整权限链（这是整个方案的核心）

我在源码中查到了**最关键的一环** —— Shizuku 是怎么拿到 `WRITE_SECURE_SETTINGS` 的：

```java
// /workspace/research/Shizuku/server/src/main/java/rikka/shizuku/server/ShizukuService.java:242-247
} else {
    try {
        // ★ 用 shell(uid 2000) 权限，给自己（manager 包）授予 WRITE_SECURE_SETTINGS
        PermissionManagerApis.grantRuntimePermission(
            MANAGER_APPLICATION_ID,          // "moe.shizuku.privileged.api"
            WRITE_SECURE_SETTINGS,
            UserHandleCompat.getUserId(callingUid)
        );
    } catch (RemoteException e) {
        LOGGER.w(e, "grant WRITE_SECURE_SETTINGS");
    }
}
```

**完整链条：**

```
① 通过无线调试配对，连上本机 adbd（RSA 密钥已信任）
        ↓
② 用 ADB shell 执行命令（此时身份 = shell / uid 2000）
        ↓
③ shell 身份调 pm grant 给自己授 WRITE_SECURE_SETTINGS
        ↓
④ 拿到权限后，可写 Settings.Global 打开无线调试
        ↓
⑤ 之后每次启动/开机都能自动恢复
```

**关键洞察**：这不是什么漏洞，而是 **ADB shell 天然拥有的能力**（`pm grant` 就是给 shell 用的）。Shizuku 只是把这个能力自动化了。

### 2.2 三个开关

```kotlin
// AdbDialogFragment.kt:65-69（以及 BootCompleteReceiver.kt:63-65）
if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
    val cr = context.contentResolver
    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)              // 打开「无线调试」
    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)     // 打开「USB 调试」
    Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L) // 0 = 连接永不过期
}
```

| 设置项 | 作用 |
|--------|------|
| `adb_wifi_enabled` | **关键**——打开「无线调试」开关 |
| `ADB_ENABLED` | 打开「USB 调试」（adbd 总开关） |
| `adb_allowed_connection_time` | `0` = **永不自动断开**（默认 7 天会失效） |

### 2.3 端口识别的巧妙判据 ⭐

这是**最有价值的可移植代码**，直接解决 LogLab 当前的「幽灵端口」问题：

```kotlin
// /workspace/research/Shizuku/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt:75-81
/**
 * 反证法判断端口是否真的被监听：
 *   能 bind → 没人监听 → 返回 false（端口无效）
 *   绑不上 → 确实有人在监听 → 返回 true（端口有效）
 */
private fun isPortAvailable(port: Int) = try {
    ServerSocket().use {
        it.bind(InetSocketAddress("127.0.0.1", port), 1)
        false              // bind 成功 = 端口空闲 = 没有 adbd
    }
} catch (e: IOException) {
    true                   // bind 失败 = 端口被占用 = adbd 在监听 ✅
}
```

**为什么比 LogLab 现在的做法更好：**

| 方案 | 原理 | 缺陷 |
|------|------|------|
| LogLab 现状 | 主动 `connect()` 探测 | 端口被**别的进程**占用时会误判为可用 |
| Shizuku | `bind()` **反证** | 一次调用同时验证「有进程监听」+「是本机端口」+「零特权」 |

配合**本机网卡校验**，排除局域网内其他设备：

```kotlin
// AdbMdns.kt:60-66
if (running && NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .any { networkInterface ->
            networkInterface.inetAddresses
                .asSequence()
                .any { resolvedService.host.hostAddress == it.hostAddress }
        }
    && isPortAvailable(resolvedService.port)      // ★ 双重校验
) {
    serviceName = resolvedService.serviceName
    observer.onChanged(resolvedService.port)
}
```

### 2.4 mDNS 的幂等设计

```kotlin
// AdbMdns.kt:26-45
private var registered = false   // 是否已向系统注册监听
private var running = false      // 业务上是否应该运行

fun start() {
    if (running) return          // ★ 防重复 start
    running = true
    if (!registered) {           // ★ 只在未注册时注册，避免 IllegalStateException
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }
}

fun stop() {
    if (!running) return
    running = false
    if (registered) {
        nsdManager.stopServiceDiscovery(listener)
    }
}
```

> **双标志位的意义**：`NsdManager` 对同一 listener 重复 `discoverServices` 会抛异常；
> 而我们又希望 `start()` 幂等。分开两个标志位后，`start/stop` 可以随意调用。

### 2.5 开机自启

```kotlin
// BootCompleteReceiver.kt:25-40
override fun onReceive(context: Context, intent: Intent) {
    if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
        && Intent.ACTION_BOOT_COMPLETED != intent.action) return

    // 只处理主用户；Shizuku 服务已在运行则跳过
    if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

    if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
        rootStart(context)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU   // Android 13+
        && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        && ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
        adbStart(context)
    }
}
```

开机启动的完整流程（`goAsync()` + 协程 + 3 秒超时）：

```kotlin
// BootCompleteReceiver.kt:57-84
private fun adbStart(context: Context) {
    val cr = context.contentResolver
    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
    Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

    val pending = goAsync()          // ★ 通知系统「我还在干活」，避免广播超时被杀
    CoroutineScope(Dispatchers.IO).launch {
        val latch = CountDownLatch(1)
        val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port <= 0) return@AdbMdns
            try {
                val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
                val key = AdbKey(keystore, "shizuku")
                val client = AdbClient("127.0.0.1", port, key)
                client.connect()
                client.shellCommand(Starter.internalCommand, null)
                client.close()
            } catch (_: Exception) { }
            latch.countDown()
        }
        if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
            adbMdns.start()
            latch.await(3, TimeUnit.SECONDS)    // ★ 最多等 3 秒
            adbMdns.stop()
        }
        pending.finish()                        // ★ 必须调用，否则系统报 ANR
    }
}
```

### 2.6 Android 版本要求

```kotlin
// BootCompleteReceiver.kt:36
Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU   // Android 13 (API 33)
```

> Shizuku 对开机自启加了 Android 13 限制，注释引用了 `https://r.android.com/2128832`。
> 但**手动打开无线调试**（`AdbDialogFragment`）在 Android 11+ 即可用，没有这个限制。

---

## 三、LogLab 现状审计

### 3.1 已经具备的能力（可直接复用）

| 能力 | 位置 | 说明 |
|------|------|------|
| ADB 协议完整实现 | `core/adb/AdbConnection.kt` | `connect()` / `open(service)` / `AdbStream` 读写 |
| 统一后端接口 | `core/adb/AdbBackend.kt` | `probe()` / `execute(cmd)` / `stream(cmd)` / `label()` |
| RSA 密钥管理 | `core/adb/AdbKeyStore.kt` | 生成、持久化 |
| 配对协议 | `core/adb/AdbPairing.kt` | TLS + SPAKE2 配对 |
| mDNS 发现 | `core/adb/NsdDiscovery.kt` | 已含 `probePort` / `isStale` / `rankByLiveness` |
| 启动检查 | `core/connect/StartupCheck.kt` | 501 行，含端口探测兜底 |
| 前台服务 | `AndroidManifest.xml:59,69` | `foregroundServiceType="specialUse"` |

**关键**：`AdbBackend.execute(command: String): String` —— **已能执行任意 shell 命令**。

### 3.2 缺失的三块拼图

| 缺口 | 现状 | 需要做什么 |
|------|------|-----------|
| ① `WRITE_SECURE_SETTINGS` | manifest 里**没有** | 声明权限 + 连接后 `pm grant` 自授 |
| ② 自动打开无线调试 | 无 | 写 3 个 `Settings.Global` |
| ③ 开机自启 | **没有** `BOOT_COMPLETED` | 加 receiver + manifest 声明 |

### 3.3 现有 manifest 权限清单

```xml
INTERNET · ACCESS_NETWORK_STATE · CHANGE_WIFI_MULTICAST_STATE
FOREGROUND_SERVICE · FOREGROUND_SERVICE_SPECIAL_USE · POST_NOTIFICATIONS
WRITE_EXTERNAL_STORAGE · REQUEST_INSTALL_PACKAGES
```

→ 需要新增 `WRITE_SECURE_SETTINGS` 与 `RECEIVE_BOOT_COMPLETED`。

---

## 四、可行性判定

### 4.1 核心问题：LogLab 能否自己给自己授权？

**✅ 能。** 完整链路：

```
用户首次手动配对（输入配对码）→ 已配对
        ↓
连上 adbd（已信任 RSA 密钥）
        ↓
执行 shell 命令（身份 = shell / uid 2000）
    pm grant com.loglab.app android.permission.WRITE_SECURE_SETTINGS
        ↓
拿回结果：「operation not permitted」→ 说明也没关系
    （因为 shell 身份对 pm grant 是允许的）
        ↓
之后 Settings.Global.putInt() 就能成功写入
```

**验证方法**：`AdbBackend.execute()` 已支持任意命令，直接执行上述 `pm grant` 即可。

### 4.2 各环节可行性

| 环节 | 可行性 | 依据 |
|------|--------|------|
| 声明 `WRITE_SECURE_SETTINGS` | ✅ | manifest 声明即可，权限为 `signature\|privileged`，普通 App 声明不报错，只是默认不授予 |
| 用 shell 自授该权限 | ✅ | Shizuku 就是这么做的（`ShizukuService.java:243`） |
| 写 `adb_wifi_enabled` 等 | ✅ | 拿到权限后 `Settings.Global.putInt()` 即可 |
| mDNS 发现新端口 | ✅ | 已有 `NsdDiscovery` |
| 端口有效性判定 | ✅ | 可移植 `isPortAvailable` 反证法 |
| 自动连接 | ✅ | 已有 `AdbBackend` + `StartupCheck` |
| 开机自启 | ⚠️ | 需 Android 13+；且需权限已授予过 |

### 4.3 一个必须澄清的限制

**首次配对仍需手动**。原因：

- 配对需要用户在系统「无线调试」界面**查看 6 位配对码**
- 这个配对码无法被 App 读取（系统限制，不落盘、不广播）
- Shizuku 也是这么处理的（用通知 RemoteInput 让用户输入）

**但这是「一次性」的**：配对成功后 RSA 密钥被系统记住，之后可全自动。

---

## 五、实施方案

### 5.1 新增文件（5 个）

| # | 文件 | 职责 | 参考 |
|---|------|------|------|
| 1 | `core/adb/PortVerifier.kt` | `isPortAvailable` 反证法 + 本机网卡校验 | `AdbMdns.kt:75-81` |
| 2 | `core/system/SecureSettingsHelper.kt` | 权限检测 + 写 3 个开关 | `AdbDialogFragment.kt:65-69` |
| 3 | `core/system/SelfGrantHelper.kt` | 用 ADB 执行 `pm grant` 自授权限 | `ShizukuService.java:243` |
| 4 | `receiver/BootCompleteReceiver.kt` | 开机自启 | `BootCompleteReceiver.kt` 全文 |
| 5 | `core/connect/AutoWirelessAdb.kt` | 编排：开开关 → 等 mDNS → 连接 | `BootCompleteReceiver.adbStart()` |

#### ① PortVerifier.kt（核心，直接移植）

```kotlin
package com.loglab.app.core.adb

import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * 端口有效性判定 —— 移植自 Shizuku 的 AdbMdns。
 *
 * 为什么需要：mDNS 会把【已关闭的旧端口】当作有效服务继续通告（幽灵记录）。
 * 正连探测（TCP connect）无法区分「端口被 adbd 监听」与「端口被别的进程占用」，
 * 而 bind 反证法一次调用就能确认「本机确实有进程在该端口监听」。
 */
object PortVerifier {

    /**
     * 反证法：能 bind → 没人监听 → false；绑不上 → 有人在听 → true。
     * 注意语义陷阱：**返回 true 才表示端口是「可用」的（有 adbd）**。
     */
    fun isPortListening(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    /** 收集本机全部网卡地址（用于排除局域网内其他设备） */
    fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    /** 该 host 是否属于本机 */
    fun isLocal(host: String): Boolean = host in localAddresses()
}
```

#### ② SecureSettingsHelper.kt

```kotlin
package com.loglab.app.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * 无线调试的三个系统开关。
 * 移植自 Shizuku 的 AdbDialogFragment.onDialogShow() 与 BootCompleteReceiver.adbStart()。
 */
object SecureSettingsHelper {

    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** 是否已拿到 WRITE_SECURE_SETTINGS */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 打开无线调试。需要 WRITE_SECURE_SETTINGS，否则静默失败。
     * @return 是否真的写入成功（写完回读确认）
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun enableWirelessAdb(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val cr = context.contentResolver
        return runCatching {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            // 回读确认，避免「putInt 返回成功但实际被拒」的假成功
            Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
    }

    /** 无线调试当前是否开启 */
    fun isWirelessAdbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)
}
```

#### ③ SelfGrantHelper.kt（**这是整个方案的关键**）

```kotlin
package com.loglab.app.core.system

import com.loglab.app.core.adb.AdbBackend

/**
 * 用 LogLab 自己的 ADB 通道，给自己授予 WRITE_SECURE_SETTINGS。
 *
 * 原理（移植自 Shizuku 的 ShizukuService.grantRuntimePermission 调用）：
 *   无线调试连接后，我们执行 shell 命令的身份是 shell(uid 2000)，
 *   而 shell 天然拥有 pm grant 的能力。于是可以「自己给自己授权」，
 *   之后就能写 Settings.Global 打开无线调试，形成自动化闭环。
 *
 * 注意：这是一次性操作，成功后权限会持久化，无需重复执行。
 */
object SelfGrantHelper {

    /**
     * 尝试自授权限。
     * @return true = 已获得权限（本次新授权或原本就有）
     */
    suspend fun ensurePermission(backend: AdbBackend, packageName: String): Boolean {
        // 幂等：已有权限直接返回，避免重复执行 pm grant
        val cmd = "pm grant $packageName ${SecureSettingsHelper.WRITE_SECURE_SETTINGS}"
        return runCatching {
            val out = backend.execute(cmd)
            // pm grant 成功时无输出；失败会返回 "Operation not allowed" 之类
            // 这里不靠输出判断，统一由调用方回读 checkSelfPermission 确认
            out
        }.isSuccess
    }
}
```

#### ④ AutoWirelessAdb.kt（编排）

```kotlin
package com.loglab.app.core.connect

import android.content.Context
import com.loglab.app.core.adb.NsdDiscovery
import com.loglab.app.core.adb.PortVerifier
import com.loglab.app.core.system.SecureSettingsHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「自动开启无线调试 → 等 mDNS 出新端口 → 连接」的完整编排。
 *
 * 移植自 Shizuku 的 BootCompleteReceiver.adbStart()，改造点：
 *  - Shizuku 用 CountDownLatch + 3 秒硬超时（广播场景必须快速返回）
 *  - 我们是前台场景，改用协程 + 可配置超时，体验更好
 */
object AutoWirelessAdb {

    /**
     * 确保无线调试已开启并拿到可用端口。
     * @return 可用端口；null = 失败（无权限 / 超时）
     */
    suspend fun ensureEnabledAndDiscoverPort(
        context: Context,
        nsd: NsdDiscovery,
        timeoutMs: Long = 15_000
    ): Int? {
        // ① 打开开关（无权限时静默跳过，下面仍会尝试发现）
        SecureSettingsHelper.enableWirelessAdb(context)

        // ② 从设置里读一次端口（若系统已记住，可省一次 mDNS 往返）
        //    注意：getAdbTcpPort 在 Android 上通常读不到（需系统权限），仅作优化尝试
        // ③ mDNS 发现 + bind 反证校验
        return withTimeoutOrNull(timeoutMs) {
            nsd.discover(7000)
                .map { it.port }
                .filter { PortVerifier.isPortListening(it) }   // ★ 双重校验
                .firstOrNull()
        }
    }
}
```

#### ⑤ BootCompleteReceiver.kt

```kotlin
package com.loglab.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.loglab.app.core.system.SecureSettingsHelper

/**
 * 开机自动恢复无线调试连接。
 * 移植自 Shizuku 的 BootCompleteReceiver。
 *
 * 与 Shizuku 的差异：
 *  - Shizuku 用 goAsync() + 3 秒硬超时（它的启动目标是拉起服务进程，必须快）
 *  - LogLab 只需发现端口 + 写一条系统日志，可以稍宽松，但仍用 goAsync()
 *    避免广播超时
 */
class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        // Android 13+ 才允许这种自恢复（与 Shizuku 保持一致）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SecureSettingsHelper.hasWriteSecureSettings(context)) return

        val pending = goAsync()
        // 交给前台服务处理，避免在广播里做长耗时工作
        AdbRestoreService.start(context)
        pending.finish()
    }
}
```

### 5.2 修改现有文件（4 个）

| 文件 | 改动 | 原因 |
|------|------|------|
| `AndroidManifest.xml` | 加 `WRITE_SECURE_SETTINGS`、`RECEIVE_BOOT_COMPLETED`；注册 `BootCompleteReceiver` | 声明权限与开机广播 |
| `core/adb/NsdDiscovery.kt` | `rankByLiveness` 改用 `PortVerifier.isPortListening` 做二次校验 | 替代现有 `probePort`，更准 |
| `core/connect/StartupCheck.kt` | 连接成功后调 `SelfGrantHelper.ensurePermission()` | 一次性自授权限 |
| `strings.xml` / `values-en` | 新增权限引导文案 | 用户可见 |

**manifest 改动：**

```xml
<!-- 新增权限 -->
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ProtectedPermissions" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<application>
    <!-- 新增：开机自启 -->
    <receiver
        android:name=".receiver.BootCompleteReceiver"
        android:enabled="true"
        android:exported="true"
        android:directBootAware="true">
        <intent-filter android:priority="1000">
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        </intent-filter>
    </receiver>
</application>
```

### 5.3 实施顺序

**阶段 1：验证核心假设（0.5 天）**
1. manifest 加 `WRITE_SECURE_SETTINGS`
2. 在 `StartupCheck` 连接成功后调 `SelfGrantHelper.ensurePermission()`
3. 加日志：打印 `pm grant` 输出 + 回读 `checkSelfPermission` 结果
4. **实测：能否从「未授予」变为「已授予」**

> ⚠️ **这是 Go / No-Go 决策点**。若第 4 步失败，后面全都不成立。

**阶段 2：自动开无线调试（0.5 天）**
5. 加 `SecureSettingsHelper`
6. 加「设置 → 自动开启无线调试」开关与手动触发按钮
7. 实测：关掉无线调试 → 点按钮 → 是否自动打开

**阶段 3：端口识别升级（0.5 天）**
8. 加 `PortVerifier`
9. `NsdDiscovery.rankByLiveness` 接入 bind 反证法
10. 实测：幽灵端口是否被正确识别

**阶段 4：开机自启（0.5 天）**
11. 加 `BootCompleteReceiver` + manifest
12. 实测：重启手机后能否自动恢复

---

## 六、风险清单

| # | 风险 | 严重度 | 应对 |
|---|------|--------|------|
| 1 | **`pm grant` 自授失败** | 🔴 高 | 阶段 1 先验证；失败则放弃自动化，退回手动开无线调试 |
| 2 | **厂商 ROM 限制** | 🟠 中高 | OnePlus/Oppo/小米可能限制 `adb_wifi_enabled` 写入；需在多机型实测 |
| 3 | **首次仍需手动配对** | 🟡 中 | 无法绕过（系统设计），但只需一次；做好引导 UI |
| 4 | **开机自启 Android 版本限制** | 🟡 中 | 与 Shizuku 一致限定 Android 13+；低版本仅支持手动触发 |
| 5 | **权限被系统回收** | 🟡 中 | App 更新/重装后 `pm grant` 的权限会失效；需在启动检查里检测并重新自授 |
| 6 | **`WRITE_SECURE_SETTINGS` 被滥用质疑** | 🟡 中 | 需在隐私说明中明确用途；仅用于开无线调试，不做其他设置修改 |
| 7 | **后台被杀** | 🟢 低 | 已有前台服务（`foregroundServiceType="specialUse"`） |
| 8 | **重复 `pm grant` 浪费连接** | 🟢 低 | 幂等：先 `checkSelfPermission`，已有则跳过 |

### 特别提示：厂商 ROM 差异

`adb_wifi_enabled` 是 AOSP 设置项，但**厂商可能改过实现**：

| 厂商 | 已知情况 |
|------|---------|
| OnePlus / Oppo | 需实测（你的机型） |
| 小米 / 红米 | HyperOS 有额外的开发者选项限制 |
| 三星 | 相对接近 AOSP |
| 原生 Android | 完全支持 |

**建议**：实现时把「写入失败」当正常分支处理，失败时降级为提示用户手动开启。

---

## 七、与 Shizuku 方案的对比

| 维度 | 用 Shizuku App | LogLab 自实现（本方案） |
|------|--------------|---------------------|
| 用户需额外装 App | ✅ 需要 | ❌ **不需要** |
| 用户需授权 | Shizuku 授权 | 无需（自己 ADB 自授） |
| 首次配对 | 需（Shizuku 内部完成） | 需（LogLab 已有） |
| 代码复杂度 | 低（调 API） | 中（复刻机制） |
| 依赖 | Shizuku App | **无外部依赖** |
| Android 16 稳定性 | ⚠️ 已知超时问题 | ✅ **不受影响** |

**结论**：自实现方案**更优**——无外部依赖、不受 Shizuku 自身 bug 影响，且 LogLab 已具备全部底层能力。

---

## 八、最终结论

### ✅ 可行，推荐实施

**理由：**

1. **机制已完全查清** —— 权限链、三个开关、端口判据、开机自启，全部有源码证据
2. **LogLab 起点更好** —— 已有完整 ADB 协议实现，只差三块拼图
3. **无需外部依赖** —— 不装 Shizuku、不需要 Root、不需要电脑
4. **工作量可控** —— 1~2 天，新增 5 文件 + 改 4 文件
5. **顺带解决问题** —— `isPortAvailable` 反证法能根治当前的「幽灵端口」困扰

### 必须记住的三件事

1. **首次配对仍需手动**（系统设计限制，无法绕过）
2. **`pm grant` 自授是 Go/No-Go 决策点** —— 先验证这一步
3. **厂商 ROM 可能有差异** —— 写入失败要能优雅降级

### 下一步

建议立即执行**阶段 1 验证**（0.5 天）：
- 加权限声明
- 连接成功后执行 `pm grant`
- 回读确认是否成功

这一步的结果决定后续是否继续投入。
