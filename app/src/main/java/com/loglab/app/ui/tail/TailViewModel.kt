package com.loglab.app.ui.tail

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.apps.AppInfo
import com.loglab.app.core.apps.AppInfoProvider
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.core.report.AppLogger
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.repository.LogRepository
import com.loglab.app.data.repository.SettingsRepository
import com.loglab.app.service.LogTailService
import com.loglab.app.service.TailSession
import com.loglab.app.service.TailState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class TailViewModel @Inject constructor(
    private val tailSession: TailSession,
    val channelManager: ChannelManager,
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val repository: LogRepository,
    val appInfoProvider: AppInfoProvider,
    private val logger: AppLogger
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.loglab.app.core.channel.ChannelState(null))

    val lines: StateFlow<List<com.loglab.app.data.model.LogEntry>> = tailSession.lines
    val tailState: StateFlow<TailState> = tailSession.state

    var packageName by mutableStateOf("")
        private set
    /** 缓冲区多选（默认 main + crash，crash 里有崩溃/ANR 堆栈） */
    var buffers by mutableStateOf(setOf(LogBuffer.MAIN, LogBuffer.CRASH))
        private set
    var priority by mutableStateOf(LogPriority.VERBOSE)
        private set
    var keywordInput by mutableStateOf("")
        private set
    var paused by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    /** 只看错误：快捷把级别切到 E（再点一次回到 V） */
    var errorsOnly by mutableStateOf(false)
        private set

    /** 图标化应用选择器（数据来自本机 PackageManager，不走 ADB，毫秒级） */
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set
    var pickerVisible by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val saved = settings.current()
            buffers = saved.defaultBuffers.ifEmpty { setOf(LogBuffer.MAIN, LogBuffer.CRASH) }
        }
    }

    fun onPackageChange(value: String) { packageName = value }
    fun onKeywordChange(value: String) { keywordInput = value }

    fun onPriorityChange(value: LogPriority) {
        priority = value
        errorsOnly = value == LogPriority.ERROR
    }

    /** 「只看错误」快捷开关：切到 E，再点回到 V */
    fun toggleErrorsOnly() {
        errorsOnly = !errorsOnly
        priority = if (errorsOnly) LogPriority.ERROR else LogPriority.VERBOSE
    }

    /** 缓冲区多选：至少保留一个（全取消则回落到 main） */
    fun toggleBuffer(buffer: LogBuffer) {
        val next = if (buffer in buffers) buffers - buffer else buffers + buffer
        buffers = if (next.isEmpty()) setOf(LogBuffer.MAIN) else next
        viewModelScope.launch { settings.update { it.copy(defaultBuffers = buffers) } }
    }

    fun showPicker(show: Boolean) {
        pickerVisible = show
        if (show) refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            appsLoading = true
            apps = runCatching { appInfoProvider.load(settings.current().recentPackages) }
                .getOrDefault(emptyList())
            appsLoading = false
        }
    }

    fun pickPackage(pkg: String) {
        packageName = pkg
        pickerVisible = false
        viewModelScope.launch { settings.rememberPackage(pkg) }
    }

    fun start() {
        if (packageName.isNotBlank()) {
            status = null
        }
        val config = LogcatConfig(
            buffers = buffers,
            globalPriority = priority,
            streaming = true,
            maxLines = 0,
            keywords = keywordInput.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
        )
        val intent = Intent(context, LogTailService::class.java).apply {
            action = LogTailService.ACTION_START
            putExtra(LogTailService.EXTRA_PACKAGE, packageName.trim())
            putExtra(LogTailService.EXTRA_CONFIG, Json.encodeToString(config))
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure {
                status = "启动失败：${it.message}"
                logger.log("TAIL", "启动前台服务失败：${it.message}", it)
            }
        paused = false
        viewModelScope.launch {
            packageName.trim().takeIf { it.isNotBlank() }?.let { settings.rememberPackage(it) }
        }
    }

    fun stop() {
        val intent = Intent(context, LogTailService::class.java).apply {
            action = LogTailService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
        paused = false
    }

    fun togglePause() {
        paused = !paused
        tailSession.setPaused(paused)
    }

    fun clear() = tailSession.clear()

    fun reconnect() {
        viewModelScope.launch {
            channelManager.autoConnect(settings.policyOnce())
        }
    }
}
