package com.loglab.app.ui.connect

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.R
import com.loglab.app.core.adb.AdbKeyStore
import com.loglab.app.core.adb.AdbPairing
import com.loglab.app.core.adb.NsdDiscovery
import com.loglab.app.core.adb.NsdCacheCleaner
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.channel.ChannelPolicy
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 连接页 ViewModel：承载全部「连接设备」相关操作。
 * 从设置页抽离而来 —— 连接是任务，设置只管偏好。
 *
 * 用户可见消息用 context.getString 生成（跟随 per-app locale；
 * Android ≤12 上 Application 层不随语言切换即时刷新，操作后重新生成即正确）。
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    val channelManager: ChannelManager,
    private val keyStore: AdbKeyStore,
    private val pairing: AdbPairing,
    private val nsd: NsdDiscovery,
    private val nsdCacheCleaner: NsdCacheCleaner,
    private val logger: com.loglab.app.core.report.AppLogger
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.loglab.app.core.channel.ChannelState(null))

    var pairPort by mutableStateOf("")
        private set
    var pairCode by mutableStateOf("")
        private set
    var pairingMessage by mutableStateOf<String?>(null)
        private set
    var pairingInProgress by mutableStateOf(false)
        private set
    var probeMessage by mutableStateOf<String?>(null)
        private set
    var probing by mutableStateOf(false)
        private set
    var publicKey by mutableStateOf<String?>(null)
        private set

    /** mDNS 发现的无线调试设备 */
    var discoveredDevices by mutableStateOf<List<NsdDiscovery.Device>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanMessage by mutableStateOf<String?>(null)
        private set

    fun onPairPortChange(value: String) { pairPort = value }
    fun onPairCodeChange(value: String) { pairCode = value }

    fun loadPublicKey() {
        publicKey = runCatching { keyStore.exportPublicKeyText() }.getOrNull()
    }

    /**
     * mDNS 扫描附近的无线调试设备。
     * 顺带把扫到的「配对端口」自动填入配对输入框 —— 小白只需输 6 位配对码。
     */
    fun scan() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanMessage = context.getString(R.string.scan_start)
            val raw = withContext(Dispatchers.IO) {
                runCatching { nsd.discover(7000).toList().lastOrNull() ?: emptyList() }
                    .getOrDefault(emptyList())
            }
            // ★ 扫描后做并发 TCP 探活并按可用性排序：
            //   mDNS 会把已关闭的旧端口当有效服务继续通告（幽灵记录），
            //   真机上真实端口常排在列表末尾，用户无从判断该点哪个。
            //   探活是回环连接（毫秒级），整批 < 500ms，可接受。
            val ranked = runCatching { nsd.rankByLiveness(raw) }.getOrDefault(emptyList())
            val devices = ranked.map {
                it.device.copy(alive = it.alive, stale = it.stale)
            }
            discoveredDevices = devices
            if (devices.isNotEmpty()) {
                val aliveCount = devices.count { it.alive == true }
                logger.log(
                    "UI",
                    "扫描完成：${devices.size} 个服务，探活通过 $aliveCount 个" +
                        devices.joinToString("") { d ->
                            "\n  · ${d.port} ${d.label} ${when {
                                d.stale -> "[已失效]"
                                d.alive == true -> "[可用]"
                                d.alive == false -> "[端口关闭]"
                                else -> ""
                            }}"
                        }
                )
            }
            // 上报本轮端口，供 App 退出时探活/证伪
            nsdCacheCleaner.reportScannedPorts(raw.map { it.port })

            // 自动填配对端口（仅当用户还没填时）
            if (pairPort.isBlank()) {
                devices.firstOrNull { it.kind == NsdDiscovery.Kind.PAIRING }?.let {
                    pairPort = it.port.toString()
                    logger.log("UI", "已自动填入配对端口 ${it.port}")
                }
            }

            // 「可用」条目优先提示，避免用户被幽灵端口误导
            val aliveCount = devices.count { it.alive == true }
            scanMessage = when {
                devices.isEmpty() ->
                    context.getString(R.string.scan_none_hint)
                // 有探活通过的条目 → 明确告知可用数量，并在列表里高亮推荐项
                aliveCount > 0 ->
                    context.getString(R.string.scan_found_alive_fmt, devices.size, aliveCount)
                // 扫到服务但全部探活失败 → 提示这些是过期记录，而不是让用户逐个试
                else ->
                    context.getString(R.string.scan_found_all_dead_fmt, devices.size)
            }
            scanning = false
        }
    }

    /** 用户从扫描结果中选择一台设备：写入设置并立即连接 */
    fun chooseDevice(device: NsdDiscovery.Device) {
        // 配对端口是每次点开配对弹窗随机的临时端口，配对后即失效——拦截，别让用户踩坑
        if (device.kind == NsdDiscovery.Kind.PAIRING) {
            scanMessage = context.getString(R.string.pairing_port_only_fmt, device.host, device.port)
            return
        }
        viewModelScope.launch {
            // v1.8.4：连接地址恒为 127.0.0.1（App 与 adbd 同机，回环与网段无关；
            // mDNS 只用来发现端口，解析出的 host 弃用）。
            settings.update { it.copy(adbHost = "127.0.0.1", adbPort = device.port) }
            val conn = channelManager.autoConnect(ChannelPolicy.ADB_ONLY)
            scanMessage = if (conn.isSuccess) {
                context.getString(R.string.scan_connected_fmt, device.port)
            } else {
                context.getString(
                    R.string.connect_failed_fmt,
                    device.port,
                    conn.exceptionOrNull()?.message ?: context.getString(R.string.confirm_adb_on)
                )
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    fun probe() {
        viewModelScope.launch {
            probing = true
            probeMessage = context.getString(R.string.probing_fmt)
            val ok = withContext(Dispatchers.IO) { channelManager.adbChannel.probe() }
            probeMessage = if (ok) {
                context.getString(R.string.probe_ok)
            } else {
                channelManager.adbChannel.lastError
                    ?.let { context.getString(R.string.probe_unavailable_err_fmt, it) }
                    ?: context.getString(R.string.probe_unavailable_plain)
            }
            probing = false
        }
    }

    /** 无线调试配对（SPAKE2）：配对成功后自动扫描 adbd 端口并多地址回退连接 */
    fun pair() {
        viewModelScope.launch {
            pairingInProgress = true
            // 配对端口只在系统「使用配对码配对设备」弹窗打开期间广播——
            // 进入页面时扫不到是正常的，点开始配对时现场扫描补齐
            var port = pairPort.trim().toIntOrNull()
            if (port == null) {
                pairingMessage = context.getString(R.string.pair_detecting_port)
                val found = withContext(Dispatchers.IO) {
                    runCatching { nsd.discover(4000).toList().lastOrNull() ?: emptyList() }
                        .getOrDefault(emptyList())
                }.firstOrNull { it.kind == NsdDiscovery.Kind.PAIRING }?.port
                if (found == null) {
                    pairingMessage = context.getString(R.string.pair_port_not_found)
                    pairingInProgress = false
                    return@launch
                }
                pairPort = found.toString()
                port = found
                logger.log("UI", "现场扫描到配对端口 $found")
            }
            val code = pairCode.trim()
            if (code.length < 6) {
                pairingMessage = context.getString(R.string.pair_need_6)
                pairingInProgress = false
                return@launch
            }
            pairingMessage = context.getString(R.string.pairing_now)
            // v1.8.4：配对与连接地址恒 127.0.0.1（同机回环恒可达，与网段无关）
            val result = withContext(Dispatchers.IO) { pairing.pair("127.0.0.1", port, code) }
            if (result.isSuccess) {
                // 配对成功后用 mDNS 自动补齐 adbd 端口（配对端口配对后即失效，必须用 adbd 端口）
                delay(800)
                val devices = withContext(Dispatchers.IO) {
                    runCatching { nsd.discover(6000).toList().lastOrNull() ?: emptyList() }
                        .getOrDefault(emptyList())
                }
                // 只认真正能抓日志的服务，避免把刚失效的配对端口当成 adbd 端口
                val discovered = devices.firstOrNull { it.kind == NsdDiscovery.Kind.TLS_CONNECT }
                    ?: devices.firstOrNull { it.kind != NsdDiscovery.Kind.PAIRING }
                val discoveredPort = discovered?.port ?: settings.current().adbPort
                settings.update { it.copy(adbPort = discoveredPort) }

                // v1.8.4：连接地址恒 127.0.0.1（注意用字面量而非 localhost：
                // 部分 ROM 上 localhost 先解析 IPv6 ::1，adbd 只监听 IPv4 回环）。
                settings.update { it.copy(adbHost = "127.0.0.1") }
                val conn = channelManager.autoConnect(ChannelPolicy.ADB_ONLY)
                if (conn.isSuccess) {
                    pairingMessage =
                        context.getString(R.string.pair_success_connected_fmt, "127.0.0.1", discoveredPort)
                } else {
                    pairingMessage = context.getString(
                        R.string.pair_success_connect_failed_fmt,
                        conn.exceptionOrNull()?.message ?: ""
                    )
                }
            } else {
                pairingMessage = result.exceptionOrNull()?.message
            }
            pairingInProgress = false
        }
    }

    /**
     * 高级自授权：把本机 ADB 公钥写入 /data/misc/adb/adb_keys（需要 root）。
     */
    fun installKeyToDevice() {
        viewModelScope.launch {
            probeMessage = context.getString(R.string.key_writing)
            val key = keyStore.exportPublicKeyText()
            val command = buildString {
                append("mkdir -p /data/misc/adb; ")
                append("grep -qxF '$key' /data/misc/adb/adb_keys 2>/dev/null || echo '$key' >> /data/misc/adb/adb_keys; ")
                append("chmod 640 /data/misc/adb/adb_keys 2>/dev/null; ")
                append("chown system:shell /data/misc/adb/adb_keys 2>/dev/null; ")
                append("echo installed")
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val channel = channelManager.active() ?: throw IllegalStateException("无可用通道")
                    channel.execute(command).getOrThrow()
                }
            }
            probeMessage = result.getOrNull()?.let { context.getString(R.string.key_written) }
                ?: context.getString(
                    R.string.key_write_failed_fmt,
                    result.exceptionOrNull()?.message ?: ""
                )
        }
    }

    fun reconnect(policy: ChannelPolicy = ChannelPolicy.ADB_ONLY) {
        viewModelScope.launch { channelManager.autoConnect(policy) }
    }
}
