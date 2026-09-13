package com.loglab.app.data.model

import com.loglab.app.core.logcat.LogPriority
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条日志。解析失败时仅保留 raw，保证任何格式都能显示。
 *
 * [id]：进程内自增唯一标识。LazyColumn 的 item key 必须用它而不是内容 hashCode——
 * 日志里"同一毫秒、同内容"的重复行非常常见，用内容做 key 会触发
 * IllegalArgumentException（Key was already used）导致滑动时闪退。
 */
data class LogEntry(
    val raw: String,
    val timestamp: String = "",
    val pid: String = "",
    val tid: String = "",
    val priority: Char = '?',
    val tag: String = "",
    val message: String = "",
    val parsed: Boolean = false,
    val id: Long = nextId()
) {
    val level: LogPriority
        get() = LogPriority.from(priority)

    /**
     * 去掉行首时间戳的原文（列表展示用）。
     *
     * ★ 列表里不再显示 `09-04 18:45:14.661`——它每行重复、占掉近半屏宽，
     * 在手机上把真正要看的内容挤到右边。时间信息并没有丢：
     * 详情面板、长按复制、导出文件用的都是 [raw]，仍然带完整时间。
     *
     * 只对「确实以 timestamp 开头」的行做裁剪，未解析的续行（如堆栈）原样保留。
     */
    val rawWithoutTimestamp: String
        get() = if (timestamp.isNotEmpty() && raw.startsWith(timestamp)) {
            raw.substring(timestamp.length).trimStart()
        } else {
            raw
        }

    companion object {
        private val seq = AtomicLong(0)
        private fun nextId(): Long = seq.incrementAndGet()
    }
}

/** 设备（通道）基本信息 */
data class DeviceInfo(
    val label: String = "",
    val detail: String = ""
)

/** 导出结果 */
data class ExportResult(
    val fileName: String,
    val absolutePath: String,
    val lineCount: Int,
    val byteSize: Long,
    val gzipped: Boolean
)
