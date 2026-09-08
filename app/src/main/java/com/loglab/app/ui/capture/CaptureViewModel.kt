package com.loglab.app.ui.capture

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.apps.AppInfo
import com.loglab.app.core.apps.AppInfoProvider
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.R
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.model.LogEntry
import com.loglab.app.data.repository.LogRepository
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagFilter(val tag: String, val priority: LogPriority)

/** 启动自动检查更新的最小间隔：24 小时 */
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 3600_000L

@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LogRepository,
    val channelManager: ChannelManager,
    private val settings: SettingsRepository,
    private val startupCheck: com.loglab.app.core.connect.StartupCheck,
    val appInfoProvider: AppInfoProvider,
    private val updateManager: com.loglab.app.core.update.UpdateManager,
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
    /** 缓冲区多选（默认 main + crash：crash 里有崩溃/ANR 堆栈） */
    var buffers by mutableStateOf(setOf(LogBuffer.MAIN, LogBuffer.CRASH))
        private set
    var clearFirst by mutableStateOf(false)
        private set
    /** 只看错误：快捷把级别切到 E（再点一次回到 V） */
    var errorsOnly by mutableStateOf(false)
        private set
    /** 启动抓取模式：不等进程就绪就开始收日志，抓"启动瞬间" */
    var startupMode by mutableStateOf(false)
        private set
    var startupTailSec by mutableStateOf(5)
        private set

    val tagFilters = mutableStateListOf<TagFilter>()

    var entries by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var pickerVisible by mutableStateOf(false)
        private set
    var lastDurationMs by mutableStateOf(0L)
        private set

    /** 最近一次普通抓取的行数（null=无可显示的统计行）；文案由 UI 层本地化拼接 */
    var lastLines by mutableStateOf<Int?>(null)
        private set

    /** 图标化应用选择器 */
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set

    /** 启动抓取结果标记：目标 PID 与启动点下标（-1=未捕获到） */
    var startupPid by mutableStateOf<Int?>(null)
        private set
    var startupIndex by mutableStateOf(-1)
        private set
    var startupOnly by mutableStateOf(false)
        private set

    /** 启动抓取时只展示"启动点之后"的日志（用户可切换回全窗口） */
    val displayEntries: List<LogEntry>
        get() = if (startupOnly && startupIndex > 0) entries.drop(startupIndex) else entries

    init {
        logger.log("UI", "首页初始化")
        viewModelScope.launch {
            val saved = settings.current()
            buffers = saved.defaultBuffers.ifEmpty { setOf(LogBuffer.MAIN, LogBuffer.CRASH) }
        }
        runStartupCheck("首次进入")
        checkUpdateSilently()
    }

    // ---------------- 启动静默检查更新（24h 节流） ----------------

    /** 发现的新版本；null=无新版或本次未检查 */
    var availableUpdate by mutableStateOf<com.loglab.app.core.update.UpdateManager.UpdateInfo?>(null)
        private set

    /**
     * 启动时静默检查 GitHub Releases：距上次成功检查不足 24h 则跳过。
     * 失败静默忽略（无网时不打扰用户），只写日志；成功才刷新节流时间戳。
     */
    private fun checkUpdateSilently() {
        viewModelScope.launch {
            val last = runCatching { settings.current().lastUpdateCheck }.getOrDefault(0L)
            if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) return@launch
            val info = runCatching { updateManager.checkLatest(updateManager.localVersion()) }
                .onSuccess {
                    settings.update { s -> s.copy(lastUpdateCheck = System.currentTimeMillis()) }
                }
                .getOrNull()
            info?.let {
                availableUpdate = it
                logger.log("Update", "启动检查：发现新版本 ${it.version}")
            }
        }
    }

    /**
     * 启动智能检查：未配对引导配对 / 端口自动修正 / 连不上提示开无线调试。
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

    fun clearStartupResult() {
        startupResult = null
    }

    fun connect() {
        viewModelScope.launch {
            status = "正在探测通道…"
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
        errorsOnly = value == LogPriority.ERROR
    }

    /** 「只看错误」快捷开关：切到 E，再点回到 V */
    fun toggleErrorsOnly() {
        errorsOnly = !errorsOnly
        priority = if (errorsOnly) LogPriority.ERROR else LogPriority.VERBOSE
    }

    fun onMaxLinesChange(value: String) {
        maxLines = value.toIntOrNull()?.coerceIn(1, 200_000) ?: 500
    }

    /** 缓冲区多选：至少保留一个（全取消则回落到 main）；顺便记住为默认 */
    fun toggleBuffer(buffer: LogBuffer) {
        val next = if (buffer in buffers) buffers - buffer else buffers + buffer
        buffers = if (next.isEmpty()) setOf(LogBuffer.MAIN) else next
        viewModelScope.launch { settings.update { it.copy(defaultBuffers = buffers) } }
    }

    fun onClearFirstChange(value: Boolean) {
        clearFirst = value
    }

    fun onStartupModeChange(value: Boolean) {
        startupMode = value
    }

    fun onStartupTailChange(sec: Int) {
        startupTailSec = sec.coerceIn(1, 60)
    }

    fun toggleStartupOnly() {
        startupOnly = !startupOnly
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
        if (show) refreshApps()
    }

    /** 读取本机应用列表（图标/名称来自 PackageManager，运行与前台状态来自 shell） */
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

    fun clearLogs() {
        entries = emptyList()
        status = null
        lastLines = null
        startupPid = null
        startupIndex = -1
        startupOnly = false
    }

    fun capture() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            lastLines = null
            startupPid = null
            startupIndex = -1
            startupOnly = false
            val startedAt = System.currentTimeMillis()
            val pkg = packageName.trim().ifBlank { null }
            val config = LogcatConfig(
                buffers = buffers,
                pids = emptyList(),
                tags = tagFilters.associate { it.tag to it.priority },
                globalPriority = if (tagFilters.isEmpty()) priority else null,
                maxLines = maxLines,
                clearFirst = clearFirst,
                streaming = false,
                keywords = parseKeywords(keywordInput),
                startupMode = startupMode,
                startupTailMs = startupTailSec * 1000L
            )

            if (startupMode && pkg == null) {
                status = "启动抓取需要先填目标包名（点「进程 ▸」选择）"
            } else if (startupMode && pkg != null) {
                // 启动抓取：先收全量日志，轮询等目标进程出现
                status = "等待 $pkg 启动…（先收全量日志，不丢启动前现场）"
                repository.captureStartup(pkg, config) { phase -> status = phase }
                    .onSuccess { r ->
                        entries = r.entries
                        startupPid = r.pid
                        startupIndex = r.startupIndex
                        status = if (r.pid != null) {
                            "启动抓取完成：${r.entries.size} 行 · PID ${r.pid} · 等待 ${r.waitedMs / 1000}s" +
                                if (r.startupIndex >= 0) " · 启动点第 ${r.startupIndex + 1} 行" else ""
                        } else {
                            "未等到 $pkg 启动（已超时），保留窗口内 ${r.entries.size} 行"
                        }
                        settings.rememberPackage(pkg)
                    }
                    .onFailure { error ->
                        status = "启动抓取失败：${error.message}"
                        entries = emptyList()
                    }
            } else {
                status = "抓取中…"
                val result = repository.capture(pkg, config)
                lastDurationMs = System.currentTimeMillis() - startedAt
                result.onSuccess { list ->
                    entries = list
                    // 统计行文案由 UI 层本地化（stringResource），这里只给数据
                    status = null
                    lastLines = list.size
                    pkg?.let { settings.rememberPackage(it) }
                }.onFailure { error ->
                    status = if (error.message?.contains("ECONNREFUSED") == true)
                        context.getString(R.string.capture_fail_adb_off)
                    else "抓取失败：${error.message}"
                    entries = emptyList()
                }
            }
            busy = false
        }
    }

    private fun parseKeywords(input: String): List<String> =
        input.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
}
