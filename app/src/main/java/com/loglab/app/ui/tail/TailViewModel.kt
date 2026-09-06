package com.loglab.app.ui.tail

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.data.model.AppSettings
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
    private val repository: com.loglab.app.data.repository.LogRepository
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.loglab.app.core.channel.ChannelState(null))

    val lines: StateFlow<List<com.loglab.app.data.model.LogEntry>> = tailSession.lines
    val tailState: StateFlow<TailState> = tailSession.state

    var packageName by androidx.compose.runtime.mutableStateOf("")
        private set
    var buffer by androidx.compose.runtime.mutableStateOf(LogBuffer.MAIN)
        private set
    var priority by androidx.compose.runtime.mutableStateOf(LogPriority.VERBOSE)
        private set
    var keywordInput by androidx.compose.runtime.mutableStateOf("")
        private set
    var paused by androidx.compose.runtime.mutableStateOf(false)
        private set
    var status by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    /** 应用包名选择器（与首页一致，支持搜索） */
    var packageSuggestions by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set
    var pickerVisible by androidx.compose.runtime.mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val saved = settings.current()
            buffer = saved.defaultBuffer
        }
    }

    fun onPackageChange(value: String) { packageName = value }
    fun onBufferChange(value: LogBuffer) { buffer = value }
    fun onPriorityChange(value: LogPriority) { priority = value }
    fun onKeywordChange(value: String) { keywordInput = value }

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

    fun start() {
        val config = LogcatConfig(
            buffer = buffer,
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
            .onFailure { status = "启动失败：${it.message}" }
        paused = false
        status = null
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
