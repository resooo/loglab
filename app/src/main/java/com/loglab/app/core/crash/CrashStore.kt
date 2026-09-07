package com.loglab.app.core.crash

import android.content.Context
import com.loglab.app.core.report.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃记录仓库：内存 StateFlow + 本地持久化（crash_reports.json）。
 *
 * 前台服务 [com.loglab.app.service.CrashMonitorService] 捕获到崩溃后写入，
 * 页面观察 [events] 实时刷新；重启 App 不丢历史。
 */
@Singleton
class CrashStore @Inject constructor(
    @ApplicationContext context: Context,
    private val logger: AppLogger
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, FILE_NAME)
    private val dedupKeys = HashSet<String>()

    private val _events = MutableStateFlow<List<CrashEvent>>(emptyList())
    val events: StateFlow<List<CrashEvent>> = _events.asStateFlow()

    private val _monitoring = MutableStateFlow(false)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        _events.value = load()
    }

    fun setMonitoring(running: Boolean) {
        _monitoring.value = running
        if (running) _message.value = null
    }

    fun setMessage(text: String?) {
        _message.value = text
    }

    /** 写入一条（最新在前），重复内容自动去重；返回是否真的新增 */
    fun add(event: CrashEvent): Boolean = synchronized(this) {
        val key = dedupKey(event)
        if (!dedupKeys.add(key)) return false
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
        persist()
        logger.log("CRASH", "捕获崩溃：${event.packageName ?: "?"} · ${event.type} · ${event.time}")
        true
    }

    /** 批量写入（历史读取），按时间倒序合并 */
    fun addAll(incoming: List<CrashEvent>): Int = synchronized(this) {
        var added = 0
        val merged = _events.value.toMutableList()
        for (ev in incoming.sortedByDescending { it.time }) {
            val key = dedupKey(ev)
            if (!dedupKeys.add(key)) continue
            merged.add(0, ev)
            added++
        }
        if (added > 0) {
            _events.value = merged.take(MAX_EVENTS)
            persist()
            logger.log("CRASH", "合并历史崩溃：新增 $added 条")
        }
        added
    }

    fun clear() = synchronized(this) {
        dedupKeys.clear()
        _events.value = emptyList()
        persist()
        logger.log("CRASH", "已清空崩溃记录")
    }

    /** 全部记录拼成可分享文本 */
    fun exportText(): String = _events.value.joinToString("\n\n") { ev ->
        buildString {
            append("======== ${ev.time} · ${ev.displayPackage} · ${ev.type} ========")
            append("\n")
            append(ev.stack)
        }
    }

    private fun dedupKey(e: CrashEvent) = "${e.time}|${e.packageName}|${e.type}|${e.stack.hashCode()}"

    private fun load(): List<CrashEvent> = runCatching {
        if (!file.exists()) return emptyList()
        val list = json.decodeFromString<List<CrashEvent>>(file.readText())
        // v1.7.1 解析修复迁移：旧解析器产出的"无包名"记录（Native 进程名未提取、
        // Java 崩溃堆栈被打散只剩 FATAL EXCEPTION 一行）信息残缺，且崩溃监控
        // 回放 crash buffer 时会以修复后的解析重新入库，旧记录留着只会重复占位
        val cleaned = list.filter { !it.packageName.isNullOrBlank() }
        cleaned.forEach { dedupKeys.add(dedupKey(it)) }
        cleaned
    }.getOrDefault(emptyList())

    private fun persist() = runCatching {
        file.writeText(json.encodeToString(_events.value))
    }.onFailure {
        logger.log("CRASH", "持久化失败：${it.message}", it)
    }

    private companion object {
        const val FILE_NAME = "crash_reports.json"
        const val MAX_EVENTS = 200
    }
}
