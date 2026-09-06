package com.loglab.app.core.logcat

import com.loglab.app.data.model.LogEntry

/**
 * 日志行解析：支持 threadtime / time / brief / long 四种常见格式。
 * 解析失败时原样返回 raw，避免丢行。
 */
object LogParser {

    // 09-04 18:45:14.661  1234  5678 D TagName: message
    private val THREADTIME =
        Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$""")

    // 09-04 18:45:14.661 D/Tag( 1234): message   /  D/Tag: message
    private val BRIEF =
        Regex("""^(?:(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+)?([VDIWEF])/([^:]+)(?:\(\s*(\d+)\))?:\s?(.*)$""")

    // 09-04 18:45:14.661  1234  5678 D TagName: message 后跟多行（long 格式的续行）
    fun parse(raw: String): LogEntry {
        if (raw.isBlank()) return LogEntry(raw)

        THREADTIME.matchEntire(raw)?.let { m ->
            return LogEntry(
                raw = raw,
                timestamp = m.groupValues[1],
                pid = m.groupValues[2],
                tid = m.groupValues[3],
                priority = m.groupValues[4].first(),
                tag = m.groupValues[5].trim(),
                message = m.groupValues[6],
                parsed = true
            )
        }

        BRIEF.matchEntire(raw)?.let { m ->
            return LogEntry(
                raw = raw,
                timestamp = m.groupValues[1],
                pid = m.groupValues[4],
                priority = m.groupValues[2].first(),
                tag = m.groupValues[3].trim(),
                message = m.groupValues[5],
                parsed = true
            )
        }

        return LogEntry(raw = raw)
    }

    /** 生成去重键（用于 bridge 轮询去重） */
    fun dedupKey(raw: String): String = raw
}
