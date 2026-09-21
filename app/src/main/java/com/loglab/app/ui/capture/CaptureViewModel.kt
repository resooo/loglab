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
import com.loglab.app.core.channel.ChannelState
import com.loglab.app.core.connect.DevSettingsLauncher
import com.loglab.app.core.connect.StartupCheck
import com.loglab.app.core.connect.StartupCheckResult
import com.loglab.app.core.report.AppLogger
import com.loglab.app.core.update.UpdateManager

data class TagFilter(val tag: String, val priority: LogPriority)

/** 启动自动检查更新的最小间隔：24 小时 */
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 3600_000L

@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LogRepository,
    val channelManager: ChannelManager,
    private val settings: SettingsRepository,
    private val startupCheck: StartupCheck,
    val appInfoProvider: AppInfoProvider,
    private val updateManager: UpdateManager,
    private val logger: AppLogger
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelState(null))

    /** 启动智能检查 */
    var startupResult by mutableStateOf<StartupCheckResult?>(null)
        private set
    var startupChecking by mutableStateOf(false)
        private set

    /**
     * 上次**完整**启动检查完成的时间戳（毫秒）。
     *
     * 用于 [runStartupCheck] 的快速路径判断：距上次检查够近且通道仍连接时跳过重跑，
     * 避免每次切页面/回前台都跑一遍最长 40 秒的全量流程。
     * 0 表示从未检查过（或用户点了手动重试要求强制重跑）。
     */
    private var lastCheckAtMs = 0L

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
    var availableUpdate by mutableStateOf<UpdateManager.UpdateInfo?>(null)
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

            // ★ 快速路径：刚刚检查过、且通道仍连着 → 不必再跑完整流程。
            //
            // 为什么需要：检查由 ON_RESUME 触发，用户每次切页面/切后台回来都会跑一遍。
            // 完整流程在"无线调试已关闭"时要经历
            //   直连失败 → 写开关 → mDNS 第1轮 → 第2轮(最长15s) → 端口扫描
            // 最坏 40 秒以上，而绝大多数切换场景下连接状态根本没变。
            //
            // 判定条件（两者都满足才跳过）：
            //   ① 距上次成功检查 < SKIP_WINDOW_MS
            //   ② 通道当前仍处于连接状态
            // 任一不满足就走完整流程 —— 保守优先，宁可多测一次也不漏掉真实的状态变化。
            val recentlyChecked = System.currentTimeMillis() - lastCheckAtMs < SKIP_WINDOW_MS
            val stillConnected = channelState.value.connected
            if (recentlyChecked && stillConnected && startupResult == null) {
                logger.log(
                    "UI",
                    "跳过启动检查（${(System.currentTimeMillis() - lastCheckAtMs) / 1000}s 前刚查过且通道仍连接）"
                )
                startupChecking = false
                return@launch
            }

            startupResult = runCatching { startupCheck.run() }.getOrNull()
            lastCheckAtMs = System.currentTimeMillis()
            logger.log("UI", "启动检查结束：${startupResult?.message ?: "无结果（异常）"}")
            startupChecking = false
        }
    }

    /**
     * 手动重试：**总是**跑完整流程，绕过快速路径。
     *
     * 用户在界面上主动点"重试"时，其意图就是"我改了设置，请重新检测"，
     * 此时跳过检查会让按钮看起来没反应。
     */
    fun retryStartupCheck() {
        lastCheckAtMs = 0
        runStartupCheck("手动重试")
    }

    fun clearStartupResult() {
        startupResult = null
    }

    /**
     * 跳系统「开发者选项」，让用户去打开无线调试开关。
     *
     * ★ 只用于 [StartupCheckResult.DebugOff]（无线调试未开启）：
     *   那是「开关没打开」，不是「没配对」——两件事的引导动作不同，不能都甩到连接页。
     */
    fun openDevSettings() {
        val ok = DevSettingsLauncher.open(context)
        logger.log("UI", if (ok) "已跳转开发者选项（无线调试未开启）" else "跳转开发者选项失败，已提示手动路径")
    }

    fun connect() {
        viewModelScope.launch {
            status = context.getString(R.string.capture_status_probing)
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

    /**
     * 应用图标：进程选择器按需调用（LazyColumn 只对可见项请求）。
     * 取不到返回 null，UI 用首字母占位块兜底。
     */
    fun iconFor(pkg: String): android.graphics.drawable.Drawable? = appInfoProvider.icon(pkg)

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
                status = context.getString(R.string.capture_need_package)
            } else if (startupMode && pkg != null) {
                // 启动抓取：先收全量日志，轮询等目标进程出现
                status = context.getString(R.string.capture_waiting_start_fmt, pkg)
                repository.captureStartup(pkg, config) { phase -> status = phase }
                    .onSuccess { r ->
                        entries = r.entries
                        startupPid = r.pid
                        startupIndex = r.startupIndex
                        status = if (r.pid != null) {
                            if (r.startupIndex >= 0) {
                                context.getString(
                                    R.string.capture_startup_done_point_fmt,
                                    r.entries.size, r.pid.toString(),
                                    r.waitedMs / 1000, r.startupIndex + 1
                                )
                            } else {
                                context.getString(
                                    R.string.capture_startup_done_fmt,
                                    r.entries.size, r.pid.toString(), r.waitedMs / 1000
                                )
                            }
                        } else {
                            context.getString(
                                R.string.capture_startup_timeout_fmt, pkg, r.entries.size
                            )
                        }
                        settings.rememberPackage(pkg)
                    }
                    .onFailure { error ->
                        status = context.getString(R.string.capture_startup_failed_fmt, error.message.orEmpty())
                        entries = emptyList()
                    }
            } else {
                status = context.getString(R.string.capture_running)
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
                    else context.getString(R.string.capture_failed_fmt, error.message.orEmpty())
                    entries = emptyList()
                }
            }
            busy = false
        }
    }

    private fun parseKeywords(input: String): List<String> =
        input.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        /**
         * 快速路径的生效窗口（毫秒）。
         *
         * 含义：距上次完整检查不足此时长、且通道仍处于连接状态时，
         * [runStartupCheck] 直接跳过重跑。
         *
         * 取值 30 秒的依据：
         *  - 足够长：覆盖"切到设置页看一眼再回来""从后台返回"这类高频操作，
         *    这些场景下连接状态几乎不可能变；
         *  - 足够短：无线调试端口在系统侧的变化（重开开关、adbd 重启）通常伴随
         *    用户主动操作，30 秒内不会"悄悄发生"而不被察觉；
         *  - 且判定还要求"通道仍连接"这一前提 —— 真断了会立刻走完整流程。
         */
        const val SKIP_WINDOW_MS = 30_000L
    }
}
