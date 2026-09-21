package com.loglab.app.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.R
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.report.AppLogger
import com.loglab.app.core.system.SelfGrant
import com.loglab.app.core.system.WirelessDebugSettings
import com.loglab.app.core.update.UpdateManager
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.loglab.app.core.channel.ChannelState

/** 设置页「检查更新」状态机 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState                 // 初始/可再次检查
    data object Checking : UpdateUiState             // 请求 GitHub API 中
    data object UpToDate : UpdateUiState             // 已是最新
    data class Available(val info: UpdateManager.UpdateInfo) : UpdateUiState   // 有新版待下载
    data class Downloading(val progress: Int) : UpdateUiState                  // 下载中 0..100
    data class Downloaded(val file: java.io.File, val info: UpdateManager.UpdateInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * 设置页 ViewModel：设置页管「偏好」（外观/诊断/关于）+「状态」（连接/配对状态与兜底操作）。
 * 连接设备的日常操作在 ConnectViewModel（连接页），这里只做状态展示与兜底重置。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val updateManager: UpdateManager,
    private val channelManager: ChannelManager
) : ViewModel() {

    /** 运行日志（App 自身诊断日志，诊断组可查看/复制/清除） */
    fun readLog(): String = logger.read()
    fun clearLog() = logger.clear()

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 通道连接状态（设置页状态区展示用） */
    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelState(null))

    /**
     * 是否已持有 `WRITE_SECURE_SETTINGS`（决定"自动开启无线调试"显示哪种状态）。
     *
     * 用 `mutableStateOf` 而非 StateFlow：它只在设置页可见期间变化，
     * 且变化由本页按钮触发，用 Compose 状态足够。
     */
    var selfGrantGranted by mutableStateOf(false)
        private set

    /** 授权/开启操作是否进行中（禁用按钮防重复点击） */
    var enablingWirelessDebug by mutableStateOf(false)
        private set

    init {
        refreshSelfGrantState()
    }

    /** 重新读取权限状态（进入页面、以及每次操作后调用） */
    private fun refreshSelfGrantState() {
        selfGrantGranted = WirelessDebugSettings.hasWriteSecureSettings(appContext)
    }

    /**
     * 「自动开启无线调试」一体化操作：先确保有写设置权限，再打开开关。
     *
     * 两步合一是刻意的：用户的心智模型是"我要让 LogLab 能自动开无线调试"，
     * 而不是"先授权、再开开关"。分开成两个按钮只会让人困惑该点哪个。
     *
     * 执行内容：
     *  1. 若尚未授权 → 通过当前 ADB 通道执行 `pm grant` 自授（见 [SelfGrant]）；
     *  2. 写 `Settings.Global` 打开无线调试并设连接永不过期；
     *  3. 回读确认结果 —— 不用"已提交"敷衍，而是报告真实状态。
     */
    fun enableWirelessDebug() {
        if (enablingWirelessDebug) return
        viewModelScope.launch {
            enablingWirelessDebug = true
            try {
                // ① 权限：已有则跳过，避免无谓的 shell 往返
                if (!WirelessDebugSettings.hasWriteSecureSettings(appContext)) {
                    val channel = runCatching { channelManager.active() }.getOrNull()
                    if (channel == null) {
                        // 没连上就无从执行 pm grant —— 如实告知而不是假装成功
                        message = appContext.getString(R.string.settings_selfgrant_need_conn)
                        return@launch
                    }
                    val granted = runCatching {
                        SelfGrant.ensureWriteSecureSettings(appContext, channel)
                    }
                    if (granted.isFailure) {
                        message = appContext.getString(
                            R.string.settings_selfgrant_failed_fmt,
                            granted.exceptionOrNull()?.message ?: "未知错误"
                        )
                        logger.log("SETTINGS", "自授权限失败：${granted.exceptionOrNull()?.message}")
                        return@launch
                    }
                    logger.log("SETTINGS", "自授权限成功")
                }

                // ② 写开关
                val enabled = WirelessDebugSettings.enableWirelessDebug(appContext)
                val state = WirelessDebugSettings.describe(appContext)
                logger.log("SETTINGS", "开启无线调试=$enabled（$state）")

                message = if (enabled) {
                    appContext.getString(R.string.settings_selfgrant_ok)
                } else {
                    // 权限拿到但写不进去：多数是厂商 ROM 限制，如实说明并给出替代方案
                    appContext.getString(R.string.settings_selfgrant_switch_refused)
                }
            } finally {
                refreshSelfGrantState()
                enablingWirelessDebug = false
            }
        }
    }

    /** 操作结果提示（一次性，UI 消费后应调 [consumeMessage] 清除） */
    var message by mutableStateOf<String?>(null)
        private set

    fun consumeMessage() {
        message = null
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** 当前版本名（本地包信息，debug 带 -debug 后缀） */
    val localVersion: String by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    /** 兜底：强制重新配对。清掉「已配对」标记 + 断开通道：
     *  不断开的话通道仍是「已连接」，首页状态行会被连接态覆盖，看不到「未配对」提示 */
    fun resetPairing() {
        viewModelScope.launch {
            settings.update { it.copy(adbPaired = false) }
            runCatching { channelManager.disconnect() }
            logger.log("UI", "设置页：已重置配对状态（通道已断开）")
        }
    }

    /** 兜底：清除连接地址/端口（配对状态保留），下次连接重新扫描/手动填写 */
    fun clearConnectionInfo() {
        viewModelScope.launch {
            settings.update { it.copy(adbHost = "", adbPort = 0) }
            logger.log("UI", "设置页：已清除连接地址")
        }
    }

    // ---------------- 应用内更新（GitHub Releases） ----------------

    /** 检查最新 Release；有新版转 Available，否则 UpToDate */
    fun checkUpdate() {
        if (_updateState.value is UpdateUiState.Checking ||
            _updateState.value is UpdateUiState.Downloading
        ) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            runCatching { updateManager.checkLatest(localVersion) }
                .onSuccess { info ->
                    _updateState.value =
                        if (info == null) UpdateUiState.UpToDate
                        else UpdateUiState.Available(info)
                }
                .onFailure { e ->
                    logger.log("Update", "检查更新失败：${e.message}")
                    _updateState.value = UpdateUiState.Failed(
                        "检查更新失败：${e.message ?: "网络异常"}（需能访问 api.github.com）"
                    )
                }
        }
    }

    /** 下载新版本 APK（进度写回 Downloading） */
    fun downloadUpdate(info: UpdateManager.UpdateInfo) {
        _updateState.value = UpdateUiState.Downloading(0)
        viewModelScope.launch {
            runCatching { updateManager.downloadApk(info) { p -> _updateState.value = UpdateUiState.Downloading(p) } }
                .onSuccess { file -> _updateState.value = UpdateUiState.Downloaded(file, info) }
                .onFailure { e ->
                    logger.log("Update", "下载失败：${e.message}")
                    _updateState.value = UpdateUiState.Failed("下载失败：${e.message ?: "网络异常"}")
                }
        }
    }

    /** 拉起系统安装器；返回 null=已跳起，非 null=提示（如需先授权「安装未知应用」） */
    fun installUpdate(file: java.io.File): String? = updateManager.installApk(file)
}
