package com.loglab.app.core.logcat

import kotlinx.serialization.Serializable

/**
 * logcat 过滤 + 命令构建（对应插件中的过滤表达式拼接逻辑）。
 */
@Serializable
data class LogcatConfig(
    val buffer: LogBuffer = LogBuffer.MAIN,
    val format: LogFormat = LogFormat.THREADTIME,
    val pid: Int? = null,
    /** Tag -> 最低级别；为空时只看 globalPriority */
    val tags: Map<String, LogPriority> = emptyMap(),
    val globalPriority: LogPriority? = LogPriority.VERBOSE,
    val maxLines: Int = 500,
    val clearFirst: Boolean = false,
    /** true = 流式（不加 -d）；false = 一次性 dump */
    val streaming: Boolean = false,
    val keywords: List<String> = emptyList()
)

object LogcatCommandBuilder {

    fun build(config: LogcatConfig): String = buildString {
        append("logcat")
        if (!config.streaming) {
            append(" -d")
            if (config.maxLines > 0) append(" -t ${config.maxLines}")
        }
        append(" -v ${config.format.value}")
        append(" -b ${config.buffer.value}")
        config.pid?.let { append(" --pid=$it") }

        when {
            config.tags.isNotEmpty() -> {
                config.tags.forEach { (tag, priority) -> append(" ${tag.quoteTag()}:${priority.letter}") }
                append(" *:S")
            }
            config.globalPriority != null -> append(" *:${config.globalPriority.letter}")
        }
    }

    fun buildClear(): String = "logcat -c"

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
