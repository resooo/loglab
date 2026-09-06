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
                    "优先级=${config.globalPriority}, buffer=${config.buffer}"
            )
            val channel = requireChannel()
            if (config.clearFirst) {
                runCatching { channel.execute(LogcatCommandBuilder.buildClear()) }
            }
            val pid = if (!packageName.isNullOrBlank()) {
                PidResolver(channel).resolve(packageName).getOrThrow()
            } else {
                config.pid
            }
            val command = LogcatCommandBuilder.build(config.copy(pid = pid, streaming = false))
            logger.log("CAPTURE", "执行：$command")
            val output = channel.execute(command).getOrThrow()
            output.lineSequence()
                .filter { it.isNotBlank() }
                .filter { LogFilter.matches(it, config.keywords) }
                .map { LogParser.parse(it) }
                .toList()
                .also { logger.log("CAPTURE", "抓取完成：${it.size} 行") }
        }.onFailure { logger.log("CAPTURE", "抓取失败：${it.message}", it) }

    /** 实时跟踪（ADB 真流式 / HostBridge 轮询） */
    fun tail(packageName: String?, config: LogcatConfig): Flow<LogEntry> = flow {
        logger.log("TAIL", "开始跟踪：目标=${packageName ?: "全部"}")
        val channel = requireChannel()
        val pid = if (!packageName.isNullOrBlank()) {
            PidResolver(channel).resolve(packageName).getOrThrow()
        } else {
            config.pid
        }
        emit(
            LogEntry(
                raw = "已连接 ${channel.type} · 目标: ${packageName ?: "全部"} · PID: ${pid ?: "-"}",
                tag = "LogLab"
            )
        )
        val command = LogcatCommandBuilder.build(config.copy(pid = pid, streaming = true))
        channel.executeStream(command).collect { line ->
            if (line.isBlank()) return@collect
            if (LogFilter.matches(line, config.keywords)) {
                emit(LogParser.parse(line))
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
                buffer = config.buffer,
                keywords = config.keywords,
                channelType = channelManager.active()?.type,
                addHeader = addHeader,
                gzip = gzip
            )
        ).getOrThrow()
    }
}
