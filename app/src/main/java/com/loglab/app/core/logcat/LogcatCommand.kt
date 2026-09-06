package com.loglab.app.core.logcat

import kotlinx.serialization.Serializable

/**
 * logcat 过滤 + 命令构建（对应插件中的过滤表达式拼接逻辑）。
 */
@Serializable
data class LogcatConfig(
    /**
     * 缓冲区集合（可多选）：默认 main + crash。
     * crash 缓冲区是崩溃/ANR 堆栈的主战场（AM Crash、tombstone 摘要都在里面），
     * 以前只抓 main 会漏掉最关键的崩溃现场。
     */
    val buffers: Set<LogBuffer> = setOf(LogBuffer.MAIN, LogBuffer.CRASH),
    val format: LogFormat = LogFormat.THREADTIME,
    /** 目标进程 PID 集合（多进程应用会有多个）；为空=不限进程 */
    val pids: List<Int> = emptyList(),
    /** Tag -> 最低级别；为空时只看 globalPriority */
    val tags: Map<String, LogPriority> = emptyMap(),
    val globalPriority: LogPriority? = LogPriority.VERBOSE,
    val maxLines: Int = 500,
    val clearFirst: Boolean = false,
    /** true = 流式（不加 -d）；false = 一次性 dump */
    val streaming: Boolean = false,
    val keywords: List<String> = emptyList(),
    /** 启动抓取模式：不带 --pid 先收全量，轮询到 PID 后再按 PID 归并（见 LogRepository.captureStartup） */
    val startupMode: Boolean = false,
    /** 启动抓取：PID 出现后继续抓取的时长（毫秒） */
    val startupTailMs: Long = 5_000L
)

object LogcatCommandBuilder {

    fun build(config: LogcatConfig): String = buildString {
        append("logcat")
        if (!config.streaming) {
            append(" -d")
            if (config.maxLines > 0) append(" -t ${config.maxLines}")
        }
        append(" -v ${config.format.value}")
        // 多缓冲区：logcat 支持重复 -b；选了 all 就不必再拼其他（部分 ROM 会冲突）
        val buffers = config.buffers.ifEmpty { setOf(LogBuffer.MAIN) }
        if (buffers.any { it == LogBuffer.ALL }) {
            append(" -b all")
        } else {
            buffers.forEach { append(" -b ${it.value}") }
        }
        // 多进程：每个 PID 一个 --pid（多数 ROM 支持多值；不支持时
        // 由 LogRepository 在结果侧按 PID 集合二次过滤兜底）
        config.pids.forEach { append(" --pid=$it") }

        when {
            config.tags.isNotEmpty() -> {
                config.tags.forEach { (tag, priority) -> append(" ${tag.quoteTag()}:${priority.letter}") }
                append(" *:S")
            }
            config.globalPriority != null -> append(" *:${config.globalPriority.letter}")
        }
    }

    fun buildClear(): String = "logcat -c"

    /**
     * 启动抓取模式：`-T 1` 只回放最近 1 行并持续跟随，**不带 --pid**。
     *
     * 为什么用 -T 1 而不是先 `logcat -c`：
     *  -c 是破坏性操作，会把缓冲区里目标 App 上一次崩溃的现场一并抹掉且不可恢复；
     * 而 -T 1 的效果同样是"从现在开始、不回放几千行历史"，但完全不动缓冲区。
     */
    fun buildStartup(config: LogcatConfig): String = buildString {
        append("logcat -T 1")
        append(" -v ${config.format.value}")
        val buffers = config.buffers.ifEmpty { setOf(LogBuffer.MAIN) }
        if (buffers.any { it == LogBuffer.ALL }) {
            append(" -b all")
        } else {
            buffers.forEach { append(" -b ${it.value}") }
        }
        when {
            config.tags.isNotEmpty() -> {
                config.tags.forEach { (tag, priority) -> append(" ${tag.quoteTag()}:${priority.letter}") }
                append(" *:S")
            }
            config.globalPriority != null -> append(" *:${config.globalPriority.letter}")
        }
    }

    private fun String.quoteTag(): String =
        if (contains(' ') || contains(':')) "\"$this\"" else this
}

/** 关键词过滤（本地执行，避免 shell 转义问题） */
object LogFilter {
    fun matches(line: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        return keywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
    }
}
