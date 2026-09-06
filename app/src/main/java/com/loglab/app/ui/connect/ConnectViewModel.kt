package com.loglab.app.ui.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.adb.AdbKeyStore
import com.loglab.app.core.adb.AdbPairing
import com.loglab.app.core.adb.NsdDiscovery
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.channel.ChannelPolicy
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val channelManager: ChannelManager,
    private val keyStore: AdbKeyStore,
    private val pairing: AdbPairing,
    private val nsd: NsdDiscovery,
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
            scanMessage = "正在扫描附近的无线调试设备…"
            val devices = withContext(Dispatchers.IO) {
                runCatching { nsd.discover(7000).toList().lastOrNull() ?: emptyList() }
                    .getOrDefault(emptyList())
            }
            discoveredDevices = devices

            // 自动填配对端口（仅当用户还没填时）
            if (pairPort.isBlank()) {
                devices.firstOrNull { it.kind == NsdDiscovery.Kind.PAIRING }?.let {
                    pairPort = it.port.toString()
                    logger.log("UI", "已自动填入配对端口 ${it.port}")
                }
            }

            scanMessage = when {
                devices.isEmpty() ->
                    "未发现设备。部分厂商（如 ColorOS）会禁用扫描，可展开「手动填写地址」填入无线调试主界面的 IP:端口"
                else -> "找到 ${devices.size} 个服务，点「无线调试」即可连接；配对端口已自动填入"
            }
            scanning = false
        }
    }

    /** 用户从扫描结果中选择一台设备：写入设置并立即连接 */
    fun chooseDevice(device: NsdDiscovery.Device) {
        // 配对端口是每次点开配对弹窗随机的临时端口，配对后即失效——拦截，别让用户踩坑
        if (device.kind == NsdDiscovery.Kind.PAIRING) {
            scanMessage = "${device.host}:${device.port} 是配对端口，仅用于一次性配对，不能抓日志，请选择「无线调试」那一项"
            return
        }
        viewModelScope.launch {
            settings.update { it.copy(adbHost = device.host, adbPort = device.port) }
            val conn = channelManager.autoConnect(ChannelPolicy.ADB_ONLY)
            scanMessage = if (conn.isSuccess) "已连接 ${device.host}:${device.port}"
            else "已填入 ${device.host}:${device.port}，但连接失败：${conn.exceptionOrNull()?.message ?: "请确认无线调试已开启"}"
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    fun probe() {
        viewModelScope.launch {
            probing = true
            probeMessage = "探测中…"
            val ok = withContext(Dispatchers.IO) { channelManager.adbChannel.probe() }
            probeMessage = if (ok) {
                "ADB: 可用"
            } else {
                "ADB: 不可用${channelManager.adbChannel.lastError?.let { "：$it" } ?: "，请确认无线调试已开启"}"
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
                pairingMessage = "正在识别配对端口…（请确保系统里已打开「使用配对码配对设备」弹窗）"
                val found = withContext(Dispatchers.IO) {
                    runCatching { nsd.discover(4000).toList().lastOrNull() ?: emptyList() }
                        .getOrDefault(emptyList())
                }.firstOrNull { it.kind == NsdDiscovery.Kind.PAIRING }?.port
                if (found == null) {
                    pairingMessage =
                        "未能自动获取配对端口（部分系统会限制 mDNS 扫描）：" +
                            "请把配对弹窗上显示的「IP 和端口」里的端口数字，填到「端口(可选)」框里，再点一次「开始配对」"
                    pairingInProgress = false
                    return@launch
                }
                pairPort = found.toString()
                port = found
                logger.log("UI", "现场扫描到配对端口 $found")
            }
            val code = pairCode.trim()
            if (code.length < 6) {
                pairingMessage = "请输入 6 位配对码"
                pairingInProgress = false
                return@launch
            }
            pairingMessage = "正在配对…"
            // host 兜底：手动填端口的新用户 adbHost 可能为空，空串传给 pair 会直接失败
            val pairHost = settings.current().adbHost.ifBlank { "127.0.0.1" }
            val result = withContext(Dispatchers.IO) { pairing.pair(pairHost, port, code) }
            if (result.isSuccess) {
                // 配对成功后用 mDNS 自动补齐 adbd 端口（配对端口配对后即失效，必须用 adbd 端口）
                delay(800)
                val devices = withContext(Dispatchers.IO) {
                    runCatching { nsd.discover(6000).toList().lastOrNull() ?: emptyList() }
                        .getOrDefault(emptyList())
                }
                // 只认真正能抓日志的服务，避免把刚失效的配对端口当成 adbd 端口
                val discoveredPort = devices.firstOrNull { it.kind == NsdDiscovery.Kind.TLS_CONNECT }?.port
                    ?: devices.firstOrNull { it.kind != NsdDiscovery.Kind.PAIRING }?.port
                    ?: settings.current().adbPort
                settings.update { it.copy(adbPort = discoveredPort) }

                // 依次尝试多个候选主机：mDNS 发现的主机、配对时填写的主机、回环地址。
                // 部分 ROM（如 ColorOS）下，App 连接设备自身的 WiFi IP 可能被厂商防火墙拦截，
                // 而 127.0.0.1 / localhost 反而可达，因此多地址回退能显著提升连接成功率。
                val candidates = buildList {
                    devices.firstOrNull { it.host == pairHost }?.host?.let { add(it) }
                    devices.firstOrNull()?.host?.let { add(it) }
                    if (pairHost.isNotBlank() && pairHost != "127.0.0.1" && pairHost != "localhost") add(pairHost)
                    add("127.0.0.1")
                    add("localhost")
                }.distinct()

                var connected = false
                var lastErr: String? = null
                for (host in candidates) {
                    settings.update { it.copy(adbHost = host) }
                    val conn = channelManager.autoConnect(ChannelPolicy.ADB_ONLY)
                    if (conn.isSuccess) {
                        connected = true
                        pairingMessage = "配对成功 · 已连接（${host}:${discoveredPort}）"
                        break
                    }
                    lastErr = conn.exceptionOrNull()?.message
                }
                if (!connected) {
                    pairingMessage = "配对成功，但连接失败：$lastErr"
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
            probeMessage = "正在写入公钥…"
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
            probeMessage = result.getOrNull()?.let { "公钥已写入，请重启 adbd 后重连" }
                ?: "写入失败：${result.exceptionOrNull()?.message}"
        }
    }

    fun reconnect(policy: ChannelPolicy = ChannelPolicy.ADB_ONLY) {
        viewModelScope.launch { channelManager.autoConnect(policy) }
    }
}
