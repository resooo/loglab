package com.loglab.app.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.model.LogEntry
import com.loglab.app.data.repository.LogRepository
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagFilter(val tag: String, val priority: LogPriority)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: LogRepository,
    val channelManager: ChannelManager,
    private val settings: SettingsRepository,
    private val startupCheck: com.loglab.app.core.connect.StartupCheck,
    private val logger: com.loglab.app.core.report.AppLogger
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.loglab.app.core.channel.ChannelState(null))

    /** 启动智能检查 */
    var startupResult by mutableStateOf<com.loglab.app.core.connect.StartupCheckResult?>(null)
        private set
    var startupChecking by mutableStateOf(false)
        private set

    /** 检查进行到哪一步了（直连探测 / mDNS 扫描），检查条实时显示 */
    val checkPhase = startupCheck.phase

    var packageName by mutableStateOf("")
        private set
    var tagInput by mutableStateOf("")
        private set
    var keywordInput by mutableStateOf("")
        private set
    var priority by mutableStateOf(LogPriority.VERBOSE)
        private set
    var maxLines by mutableStateOf(500)
        private set
    var buffer by mutableStateOf(LogBuffer.MAIN)
        private set
    var clearFirst by mutableStateOf(false)
        private set

    val tagFilters = mutableStateListOf<TagFilter>()

    var entries by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var packageSuggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var pickerVisible by mutableStateOf(false)
        private set
    var lastDurationMs by mutableStateOf(0L)
        private set

    init {
        // 首次连接完全交给启动智能检查（其内部直连探测即建立连接）：
        // 不再并发调用 connect()，避免重复探测旧端口与通道状态互相覆盖
        logger.log("UI", "首页初始化")
        runStartupCheck("首次进入")
    }

    /**
     * 启动智能检查：未配对引导配对 / 端口自动修正 / 连不上提示开无线调试。
     *
     * [reason] 仅用于运行日志标注触发来源（首次进入 / 回到前台 / 手动重试），
     * 便于判断"这次为什么没跑 mDNS"。
     */
    fun runStartupCheck(reason: String = "未标注") {
        if (startupChecking) return
        viewModelScope.launch {
            startupChecking = true
            startupResult = null
            logger.log("UI", "触发启动检查（$reason）")
            startupResult = runCatching { startupCheck.run() }.getOrNull()
            logger.log("UI", "启动检查结束：${startupResult?.message ?: "无结果（异常）"}")
            startupChecking = false
        }
    }

    /** 清除智能检查提示（成功提示展示数秒后自动隐藏） */
    fun clearStartupResult() {
        startupResult = null
    }

    fun connect() {
        viewModelScope.launch {
            status = "正在探测通道…"
            // 容错：任何意外异常都不能让协程崩溃（否则表现为界面打不开/闪退）
            val result = try {
                channelManager.autoConnect(settings.policyOnce())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            status = result.getOrNull()?.let { null } ?: result.exceptionOrNull()?.message
        }
    }

    fun onPackageChange(value: String) {
        packageName = value
    }

    fun onTagInputChange(value: String) {
        tagInput = value
    }

    fun onKeywordChange(value: String) {
        keywordInput = value
    }

    fun onPriorityChange(value: LogPriority) {
        priority = value
    }

    fun onMaxLinesChange(value: String) {
        maxLines = value.toIntOrNull()?.coerceIn(1, 200_000) ?: 500
    }

    fun onBufferChange(value: LogBuffer) {
        buffer = value
    }

    fun onClearFirstChange(value: Boolean) {
        clearFirst = value
    }

    fun addTagFilter(prio: LogPriority = LogPriority.DEBUG) {
        val tag = tagInput.trim()
        if (tag.isEmpty()) return
        if (tagFilters.any { it.tag == tag }) return
        tagFilters.add(TagFilter(tag, prio))
        tagInput = ""
    }

    fun removeTagFilter(filter: TagFilter) {
        tagFilters.remove(filter)
    }

    fun showPicker(show: Boolean) {
        pickerVisible = show
        if (show) refreshPackages()
    }

    fun refreshPackages() {
        viewModelScope.launch {
            // 始终全量拉取：过滤交给选择器内的搜索框，避免输入框残留内容导致列表残缺
            packageSuggestions = repository.listPackages("")
            if (packageSuggestions.isEmpty()) {
                packageSuggestions = settings.current().recentPackages
            }
        }
    }

    fun pickPackage(pkg: String) {
        packageName = pkg
        pickerVisible = false
        viewModelScope.launch { settings.rememberPackage(pkg) }
    }

    fun clearLogs() {
        entries = emptyList()
        status = null
    }

    fun capture() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "抓取中…"
            val startedAt = System.currentTimeMillis()
            val config = LogcatConfig(
                buffer = buffer,
                pid = null,
                tags = tagFilters.associate { it.tag to it.priority },
                globalPriority = if (tagFilters.isEmpty()) priority else null,
                maxLines = maxLines,
                clearFirst = clearFirst,
                streaming = false,
                keywords = parseKeywords(keywordInput)
            )
            val result = repository.capture(packageName.trim().ifBlank { null }, config)
            lastDurationMs = System.currentTimeMillis() - startedAt
            result.onSuccess { list ->
                entries = list
                status = "共 ${list.size} 行 · 耗时 ${lastDurationMs}ms"
                packageName.trim().takeIf { it.isNotBlank() }?.let { pkg ->
                    settings.rememberPackage(pkg)
                }
            }.onFailure { error ->
                status = "抓取失败：${error.message}"
                entries = emptyList()
            }
            busy = false
        }
    }

    private fun parseKeywords(input: String): List<String> =
        input.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
}
