package com.loglab.app.core.crash

/**
 * crash buffer（logcat -b crash）行流解析器。
 *
 * 关键机制：`logcat -v time` 输出中，一条 log 只有首行带时间戳前缀，
 * 同一条 log 的多行消息（如 Java 崩溃堆栈）没有前缀。因此：
 *  - 带 `MM-DD HH:MM:SS.mmm` 前缀的行 = 新 log 条目开始；
 *  - 不带前缀的行 = 当前条目（堆栈）的延续；
 *  - 当前条目收集结束时（下一条带时间戳的行 / 流结束）即可定界一个完整崩溃块。
 *
 * 块内含崩溃特征（FATAL EXCEPTION / Fatal signal / ANR in）才产出事件，
 * 其余普通日志条目直接丢弃。
 */
class CrashParser {

    private var current: MutableList<String>? = null

    /** 喂入一行原始输出，若上一个崩溃块就此完结则返回该事件 */
    fun feed(line: String): CrashEvent? {
        val l = line.trimEnd('\n', '\r')
        if (STAMP_PREFIX.containsMatchIn(l.trimStart())) {
            val finished = flush()
            current = mutableListOf(l)
            return finished
        }
        val block = current ?: return null   // 块外的无时间戳行（如分隔线）直接忽略
        block.add(l)
        if (block.size > MAX_BLOCK_LINES) return flush()   // 防异常超长块
        return null
    }

    /** 结束当前收集（流结束时调用），返回最后一个事件 */
    fun flush(): CrashEvent? {
        val block = current ?: return null
        current = null
        if (block.isEmpty()) return null
        val text = block.joinToString("\n").trimEnd()
        if (text.isBlank()) return null

        val time = parseStamp(block.first()) ?: ""
        return when {
            "FATAL EXCEPTION" in text -> javaCrash(text, block)
            "Fatal signal" in text -> nativeCrash(block)
            "ANR in" in text -> anrCrash(block)
            else -> null   // crash buffer 里的普通日志，不是崩溃
        }
    }

    private fun javaCrash(text: String, block: List<String>): CrashEvent {
        val pkg = Regex("Process:\\s*([\\w.$]+)").find(text)?.groupValues?.get(1)
        // 块内第一条异常声明行（跳过 "FATAL EXCEPTION: main" 本身）
        val excLine = block.firstOrNull {
            !it.contains("FATAL EXCEPTION") && EXC_MARK.containsMatchIn(it)
        }?.trim()
        val type = excLine?.substringBefore(": ")?.takeIf { it.isNotEmpty() } ?: "Java 崩溃"
        val summary = excLine?.substringAfter(": ")?.takeIf { it.isNotEmpty() } ?: type
        return CrashEvent(
            time = parseStamp(block.first()) ?: "",
            packageName = pkg,
            type = type,
            summary = summary.take(200),
            stack = text
        )
    }

    private fun nativeCrash(block: List<String>): CrashEvent {
        val first = block.first()
        val sig = Regex("Fatal signal \\d+ \\(([^)]+)\\)").find(first)?.groupValues?.get(1)
        return CrashEvent(
            time = parseStamp(first) ?: "",
            packageName = null,
            type = "Native 崩溃${sig?.let { " ($it)" } ?: ""}",
            summary = first.substringAfter("): ").take(200),
            stack = block.joinToString("\n").trimEnd()
        )
    }

    private fun anrCrash(block: List<String>): CrashEvent {
        val first = block.first()
        return CrashEvent(
            time = parseStamp(first) ?: "",
            packageName = Regex("ANR in ([\\w.$]+)").find(first)?.groupValues?.get(1),
            type = "ANR（应用无响应）",
            summary = "应用长时间无响应，被系统弹窗或自动关闭".take(200),
            stack = block.joinToString("\n").trimEnd()
        )
    }

    /** 从 logcat 首行提取时间并补全年份：MM-DD HH:MM:SS.mmm → yyyy-MM-dd HH:mm:ss */
    private fun parseStamp(first: String): String? {
        val m = STAMP_TIME.find(first) ?: return null
        val (mo, d, h, mi, s) = m.destructured
        val year = java.time.LocalDate.now().year
        return "%04d-%s-%s %s:%s:%s".format(year, mo, d, h, mi, s)
    }

    private companion object {
        private val STAMP_PREFIX = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+")
        private val STAMP_TIME = Regex("(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})")
        private val EXC_MARK = Regex("(Exception|Error)\\b")
        private const val MAX_BLOCK_LINES = 400
    }
}
