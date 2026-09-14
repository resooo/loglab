# LogLab v1.9.5 更新说明

> 修复 v1.9.4 引入的崩溃历史读取回归，并解决 Native 崩溃解析的多个缺陷。

---

## 🔧 崩溃记录修复

### 1. 「读取历史崩溃」提示没有找到（v1.9.4 的回归）

v1.9.4 把 `logcat -T` 的时间格式改成了带年份的 ISO 8601 —— **这是错的**。

`logcat` 的 `-T` 只接受 `MM-DD HH:mm:ss.mmm`（**不带年份**）。加年份后它按字面解析失败，会把**所有输出都过滤掉**。

实测（OnePlus / Android 16，crash buffer 共 69 行）：

| `-T` 参数 | 返回行数 |
|-----------|---------|
| `09-14 00:00:00.000` | ✅ 69 行 |
| `2026-09-14T00:00:00.000` | ❌ 1 行 |
| `09-14` | ❌ 0 行 |

现已回滚为无年份格式。

### 2. Native 崩溃完全解析不出来

`CrashParser` 只认老格式 `Fatal signal 11 (SIGSEGV)`，但 Android 10+ 由 `debuggerd` 写入 crash buffer 的是 **tombstone 格式**：

```
*** engrave_tombstone_ucontext ***
signal 5 (SIGTRAP), code 1 (TRAP_BRKPT), fault addr --------
```

信号行**没有 `Fatal` 前缀**，于是 Chrome / WebView / 所有 NDK 应用的崩溃都被当成普通日志丢弃 —— 表现为「crash buffer 里明明有数据，解析出来 0 条」。

新增 tombstone 识别后，用真实 69 行数据离线验证：**0 条 → 2 条**。

### 3. 读取后列表不刷新，要切页签才出来

`filteredEvents` 是 `derivedStateOf`，内部读的是 `events.value` —— 而 **`StateFlow.value` 是普通属性访问，不会建立 Compose 快照依赖**，所以它感知不到 `events` 变化。

`range`（时间页签）是 `mutableStateOf`，切换时能触发失效 —— 这就是「切统计方式才刷新」的原因。

现已把 `events` 镜像进真正的 `MutableState`，`events` 一变立即重算并刷新。

### 4. Native 崩溃显示「未知应用（未解析到包名）」

tombstone 的进程行是：

```
pid: 18683, tid: 18708, name: CrRendererMain  >>> com.android.chrome:sandboxed_process0:org.chromium... <<<
```

旧正则要求 `>>>` 后**直接**跟 `<<<`，但中间内容含冒号，而 `[\w.$]` 不匹配冒号 —— 匹配失败。

现已放宽为 `>>>\s*([\w.$]+)`，并优先尝试 `Process name is <pkg>`（tombstone 里最可靠的来源）。

> 提示：**旧记录不会自动重新解析**。装新版后请点「清空」，再点「读取历史崩溃」，包名即可正确显示。

---

## 📥 安装

| 项目 | 说明 |
|------|------|
| 文件 | `LogLab_v1.9.5.apk` |
| 架构 | arm64-v8a |
| 系统 | Android 8.0+ (minSdk 26) |
| 升级 | **v1.8.x / v1.9.x 可直接覆盖安装**（签名未变） |

---

## 📋 完整变更

详见 [CHANGELOG.md](CHANGELOG.md)。
