# 构建性能与内存问题排查

> 适用环境：太墟 Android 沙箱（PRoot / aarch64）
> 最后实测：2026-09-21

---

## 一、症状

release 构建异常缓慢或失败：

| 症状 | 表现 |
|------|------|
| **构建极慢** | 从正常的 5 分钟劣化到 **15~25 分钟** |
| **R8 OOM** | `ERROR: R8: java.lang.OutOfMemoryError: Java heap space` |
| **构建假死** | `process status` 显示"运行中"，但实际已卡住 |
| **daemon 消失** | `Gradle build daemon disappeared unexpectedly` |

---

## 二、根因

**沙箱可用内存不足，R8 阶段被迫使用 swap。**

```
宿主机其它进程占用内存
        ↓
沙箱可用内存 8GB → 2.4GB
        ↓
Gradle(3.5G) + Kotlin daemon(1G) + R8 峰值(2G+) > 可用内存
        ↓
数据换出到 swap，磁盘 IO 比内存慢几十倍
        ↓
构建时间翻倍 → 最终 OOM
```

### 关键数据

| 可用内存 | release 构建耗时 |
|---------|-----------------|
| ≥ 4.5 GB | 5~8 分钟 |
| 3.5~4.5 GB | 8~15 分钟 |
| < 3.5 GB | **OOM 或 20 分钟以上** |

### 最常见的元凶

**上一次构建残留的 java 守护进程。**

Gradle/Kotlin daemon 不随 shell 退出而结束，会长期占用 **1~1.5 GB** RSS + 大量 swap。
实测清理后可用内存从 2.46 GB 回升到 **4.02 GB**，构建立刻恢复正常。

---

## 三、解决方案

### 1. 构建前先跑前置脚本

```bash
sh scripts/prepare-build.sh          # 检查 + 清理残留进程
sh scripts/prepare-build.sh --check  # 只检查
```

脚本会：
- 遍历 `/proc` 找出 Gradle/Kotlin 守护进程（**PRoot 下 `ps aux` 不可用**，只能读 `/proc`）
- 精确匹配 `cmdline`，只杀构建相关进程
- 清理 Gradle `.tmp` 陈旧文件
- **给出内存判断与预计耗时**

### 2. 确认 `gradle.properties` 的内存配置

```properties
org.gradle.jvmargs=-Xmx3584m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx1024m
org.gradle.parallel=false
org.gradle.workers.max=1
```

> **注意**：`-Xmx2560m` 在可用内存 4GB 时 R8 仍会 OOM。
> 原因是 R8 处理多个 `-keep { *; }` 大库（BouncyCastle 等）时内存峰值很高。

### 3. 日常用 debug，只在发布时跑 release

```bash
# 验证功能（2~3 分钟）
sh scripts/prepare-build.sh && ./gradlew assembleDebug

# 发布（慢，只需一次）
sh scripts/prepare-build.sh && ./gradlew assembleRelease
```

---

## 四、如何判断构建是"真在跑"还是"假死"

**`process status` 显示"运行中"不可信**，必须查 `/proc`：

```bash
# ① 找构建进程
for p in $(ls /proc/ | grep -E '^[0-9]+$'); do
  [ -r /proc/$p/cmdline ] && tr '\0' ' ' < /proc/$p/cmdline | grep -q gradle && echo "PID $p"
done

# ② 查 CPU 时间（关键判据）
awk '{print "cpu=" $14+$15 "s"}' /proc/<PID>/stat

# ③ 查常驻内存
grep VmRSS /proc/<PID>/status
```

| 判据 | 健康 | 假死 |
|------|------|------|
| **CPU 时间** | 持续增长（隔 1 分钟能看到 +数百秒） | 停滞不动 |
| **VmRSS** | 数百 MB ~ 1.5 GB | **掉到几十 MB**（内存被回收） |
| 进程状态 | `S` / `R` | `S` 但 CPU 不动 |

### 实测对比

| | 假死时 | 正常时 |
|---|--------|--------|
| CPU 时间 | 13s（21 分钟没变） | **17461s → 24245s**（4 分钟涨 6784s） |
| VmRSS | **12 MB** | **1494 MB** |

> **VmRSS 掉到几十 MB 是"假死"最明确的信号** —— 说明进程内存已被系统回收。

---

## 五、常见误区

| 误区 | 事实 |
|------|------|
| "代码变多了导致构建慢" | 检查 `proguard-rules.pro` 与依赖是否变更；本项目重构后代码**减少**了 |
| "`ps aux` 看不到进程 = 进程死了" | PRoot 下 `ps aux` 只返回 1 行，**必须用 `/proc`** |
| "`du -sh` 看缓存大小" | 在 928MB / 5271 文件的 `build-cache-1` 上会超时数分钟，别用 |
| "内存总量够就行" | 要看 **MemAvailable** 和 **SwapFree**，宿主机占用不可控 |
| "中断重来更快" | R8 跑到尾声时中断 = 前面 20 分钟白费；先判断是否真卡死 |

---

## 六、历史记录

| 日期 | 现象 | 处理 |
|------|------|------|
| 2026-09-13 | release 5 分钟 → 12 分钟，R8 OOM | 下调 `-Xmx` 到 2560m（提交 `ae97027`） |
| 2026-09-21 | R8 OOM（可用内存 2.4GB） | 新增前置脚本 + 上调 `-Xmx` 到 3584m |

> 两次根因相同：**可用内存不足**。下调 `-Xmx` 只是让 OOM 延后，不是根治。
> 根治手段是**构建前回收内存**。
