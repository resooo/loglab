package com.loglab.app.data.repository

import com.loglab.app.core.channel.Channel
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.export.ExportOptions
import com.loglab.app.core.export.LogExporter
import com.loglab.app.core.logcat.LogFilter
import com.loglab.app.core.logcat.LogParser
import com.loglab.app.core.logcat.LogcatCommandBuilder
import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.core.logcat.PidResolver
import com.loglab.app.data.model.ExportResult
import com.loglab.app.data.model.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日志仓库：把「通道 + PID 解析 + 命令构建 + 过滤 + 导出」串成业务用例。
 */
@Singleton
class LogRepository @Inject constructor(
    private val channelManager: ChannelManager,
    private val settings: SettingsRepository,
    private val exporter: LogExporter,
    private val logger: com.loglab.app.core.report.AppLogger
) {

    /** 取当前通道，未连接则先自动探测 */
    private suspend fun requireChannel(): Channel {
        channelManager.active()?.let { return it }
        channelManager.autoConnect(settings.policyOnce()).getOrThrow()
        return channelManager.active() ?: throw IllegalStateException("无可用通道")
    }

    suspend fun resolvePid(packageName: String): Result<Int> =
        PidResolver(requireChannel()).resolve(packageName)

    /** 多进程：某包名下的全部 PID（主进程 + 子进程） */
    suspend fun resolvePids(packageName: String): List<Int> =
        PidResolver(requireChannel()).resolveAll(packageName)

    suspend fun listPackages(keyword: String = ""): List<String> =
        runCatching { PidResolver(requireChannel()).listPackages(keyword) }.getOrDefault(emptyList())

    suspend fun clearBuffer(): Result<Unit> =
        requireChannel().execute(LogcatCommandBuilder.buildClear()).map { Unit }

    /** 一次性抓取（logcat -d） */
    suspend fun capture(packageName: String?, config: LogcatConfig): Result<List<LogEntry>> =
        runCatching {
            logger.log(
                "CAPTURE",
                "开始抓取：目标=${packageName ?: "全部"}, maxLines=${config.maxLines}, " +
                    "优先级=${config.globalPriority}, buffers=${config.buffers.joinToString("+") { it.value }}"
            )
            val channel = requireChannel()
            if (config.clearFirst) {
                runCatching { channel.execute(LogcatCommandBuilder.buildClear()) }
            }
            val pids = if (!packageName.isNullOrBlank()) {
                PidResolver(channel).resolveAll(packageName)
            } else {
                config.pids
            }
            if (!packageName.isNullOrBlank()) {
                logger.log(
                    "CAPTURE",
                    if (pids.isEmpty()) "$packageName 未运行（无 PID），将抓全量"
                    else "$packageName PID=${pids.joinToString(",")}"
                )
            }
            val command = LogcatCommandBuilder.build(config.copy(pids = pids, streaming = false))
            logger.log("CAPTURE", "执行：$command")
            val output = channel.execute(command).getOrThrow()
            val parsed = output.lineSequence()
                .filter { it.isNotBlank() }
                .filter { LogFilter.matches(it, config.keywords) }
                .map { LogParser.parse(it) }
                .toList()
            // 多 PID 兜底：部分 ROM 的 logcat 只认最后一个 --pid，这里按 PID 集合
            // 在结果侧再滤一次（threadtime 已解析出 pid 字段），保证"多进程也精准"
            val result = if (pids.size > 1) {
                val set = pids.toSet()
                parsed.filter { !it.parsed || it.pid.toIntOrNull() in set }
            } else parsed
            logger.log("CAPTURE", "抓取完成：${result.size} 行")
            result
        }.onFailure { logger.log("CAPTURE", "抓取失败：${it.message}", it) }

    /** 启动抓取结果：全部缓存行 + 目标 PID + 启动点下标（首行属于该 PID 的位置） */
    data class StartupCaptureResult(
        val entries: List<LogEntry>,
        val pid: Int?,
        val startupIndex: Int,
        val waitedMs: Long
    )

    /**
     * 启动抓取：不等目标进程就绪就开始收日志，用来抓"启动瞬间"的崩溃。
     *
     * 流程（刻意不用 logcat -c）：
     *   1. `logcat -T 1` 从缓冲区头部开始收全量（不毁历史日志）；
     *   2. 每 400ms 轮询 `pidof/pgrep`，等待目标进程出现；
     *   3. PID 出现后再多收 startupTailMs（默认 5s）启动期日志；
     *   4. 返回整段时间窗内的全部行 + 启动点下标，UI 可只展示启动后部分。
     *
     * @param onPhase 进度回调（UI 显示"等待中…已等待 Ns"）
     */
    suspend fun captureStartup(
        packageName: String,
        config: LogcatConfig,
        timeoutMs: Long = 60_000L,
        onPhase: (String) -> Unit = {}
    ): Result<StartupCaptureResult> = runCatching {
        val channel = requireChannel()
        val collected = mutableListOf<LogEntry>()
        var foundPid: Int? = null
        val startedAt = System.currentTimeMillis()
        logger.log("CAPTURE", "启动抓取：等待 $packageName 启动（超时 ${timeoutMs / 1000}s）")

        coroutineScope {
            val collectJob = launch {
                channel.executeStream(LogcatCommandBuilder.buildStartup(config))
                    .catch { e -> logger.log("CAPTURE", "启动抓取流异常：${e.message}") }
                    .collect { line ->
                        if (line.isBlank()) return@collect
                        if (!LogFilter.matches(line, config.keywords)) return@collect
                        collected += LogParser.parse(line)
                    }
            }
            try {
                while (foundPid == null && System.currentTimeMillis() - startedAt < timeoutMs) {
                    val pids = PidResolver(channel).resolveAll(packageName)
                    if (pids.isNotEmpty()) {
                        foundPid = pids.first()
                        val waited = System.currentTimeMillis() - startedAt
                        logger.log("CAPTURE", "检测到 $packageName 启动：PID=${pids.joinToString(",")}（等待 ${waited}ms）")
                        onPhase("已检测到启动（PID ${pids.joinToString(",")}），继续抓取 ${config.startupTailMs / 1000}s…")
                        break
                    }
                    val secs = (System.currentTimeMillis() - startedAt) / 1000
                    onPhase("等待 $packageName 启动…（${secs}s）")
                    delay(400)
                }
                if (foundPid != null) delay(config.startupTailMs)
            } finally {
                collectJob.cancel()
            }
        }

        val pid = foundPid
        val startupIndex = if (pid == null) -1
        else collected.indexOfFirst { it.pid == pid.toString() }
        val waitedMs = System.currentTimeMillis() - startedAt
        logger.log(
            "CAPTURE",
            "启动抓取结束：共 ${collected.size} 行，PID=${pid ?: "未出现"}，启动点=$startupIndex，耗时 ${waitedMs}ms"
        )
        StartupCaptureResult(collected, pid, startupIndex, waitedMs)
    }.onFailure { logger.log("CAPTURE", "启动抓取失败：${it.message}", it) }

    /** 实时跟踪（ADB 真流式 / HostBridge 轮询） */
    fun tail(packageName: String?, config: LogcatConfig): Flow<LogEntry> = flow {
        logger.log("TAIL", "开始跟踪：目标=${packageName ?: "全部"}")
        val channel = requireChannel()
        val pids = if (!packageName.isNullOrBlank()) {
            PidResolver(channel).resolveAll(packageName)
        } else {
            config.pids
        }
        emit(
            LogEntry(
                raw = "已连接 ${channel.type} · 目标: ${packageName ?: "全部"} · PID: ${pids.joinToString(",").ifBlank { "-" }}",
                tag = "LogLab"
            )
        )
        val command = LogcatCommandBuilder.build(config.copy(pids = pids, streaming = true))
        // 多 PID 同样在结果侧兜底过滤（ROM 只认最后一个 --pid 时也能保持精准）
        val pidSet = pids.toSet()
        channel.executeStream(command).collect { line ->
            if (line.isBlank()) return@collect
            if (LogFilter.matches(line, config.keywords)) {
                val entry = LogParser.parse(line)
                if (pidSet.size <= 1 || !entry.parsed || entry.pid.toIntOrNull() in pidSet) {
                    emit(entry)
                }
            }
        }
        // 流正常结束（logcat 进程退出，如目标应用被杀）时给出明确提示，
        // 避免界面停在"已连接"让用户误以为卡死；异常中断走下方 catch
        emit(LogEntry(raw = "日志流已结束（logcat 退出，目标应用可能已关闭）", tag = "LogLab"))
    }
        .catch { e ->
            logger.log("TAIL", "跟踪中断：${e.message}", e)
            emit(LogEntry(raw = "跟踪中断: ${e.message ?: e::class.java.simpleName}", tag = "LogLab"))
        }
        .flowOn(Dispatchers.IO)

    /** 抓取并导出到私有目录，返回可供分享的文件信息 */
    suspend fun exportCapture(
        packageName: String?,
        fileName: String,
        config: LogcatConfig,
        addHeader: Boolean,
        gzip: Boolean
    ): Result<ExportResult> = runCatching {
        val entries = capture(packageName, config).getOrThrow()
        val lines = entries.map { it.raw }
        val pid = entries.firstOrNull { it.parsed }?.pid?.toIntOrNull()
        exporter.export(
            lines = lines,
            fileName = fileName,
            options = ExportOptions(
                packageName = packageName,
                pid = pid,
                buffers = config.buffers,
                keywords = config.keywords,
                channelType = channelManager.active()?.type,
                addHeader = addHeader,
                gzip = gzip
            )
        ).getOrThrow()
    }
}
