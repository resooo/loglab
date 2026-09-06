package com.loglab.app.core.connect

import com.loglab.app.core.adb.NsdDiscovery
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.channel.ChannelPolicy
import com.loglab.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 单轮 mDNS 扫描窗口：无线调试重开后通告可能延迟数秒；6 秒内必有结论，不让用户干等 */
private const val MDNS_WINDOW_MS = 6000L

/** 启动智能检查结果 */
sealed class StartupCheckResult {
    abstract val connected: Boolean
    abstract val message: String
    /** 异常状态（需要引导用户操作） */
    open val needsAction: Boolean get() = !connected

    /** 一切就绪 */
    data class Ready(val host: String, val port: Int) : StartupCheckResult() {
        override val connected get() = true
        override val message get() = "ADB 已连接 $host:$port"
    }

    /** mDNS 发现无线调试端口已变化，已自动更新并重新连接 */
    data class PortUpdated(val host: String, val oldPort: Int, val newPort: Int) : StartupCheckResult() {
        override val connected get() = true
        override val message get() = "检测到无线调试端口变化（$oldPort → $newPort），已自动更新并连接"
    }

    /** 未配对，需要引导 */
    object NeedPairing : StartupCheckResult() {
        override val connected get() = false
        override val message get() =
            "尚未完成无线调试配对：请到「设置 → 开发者选项 → 无线调试 → 使用配对码配对设备」，然后在 App 设置页完成配对"
    }

    /** 已配对但连不上（多为无线调试被关闭或网络不通） */
    data class NotReachable(val detail: String?) : StartupCheckResult() {
        override val connected get() = false
        override val message get() =
            detail?.takeIf { it.isNotBlank() } ?: "已配对但无法连接，请确认手机「无线调试」已开启"
    }
}

/**
 * 启动智能检查：
 *  1. 未配对 → 提示引导用户到设置页配对；
 *  2. 已配对 → 直连探测与 mDNS 扫描**同时启动**（保持"启动即扫描"时序，
 *     无线调试刚重开时也能第一时间发现新端口）；
 *  3. 直连成功 → 立即就绪（取消 mDNS）；
 *  4. 直连失败 → 采用 mDNS 结果：端口/主机变化说明无线调试重开过，
 *     自动更新配置并重连（这是最常见的失联原因）；
 *  5. 第一轮 mDNS 空手而归 → **自动再扫一轮**（无线调试刚重开时 mDNS 通告
 *     可能延迟数秒，单轮 6s 窗口经常不够）；
 *  6. 仍无结果 → 无线调试很可能未开启；提示中附带精确诊断
 *     （mDNS 发现/解析计数），便于区分"没扫到"与"扫到但连不上"。
 */
@Singleton
class StartupCheck @Inject constructor(
    private val settings: SettingsRepository,
    private val nsd: NsdDiscovery,
    private val channelManager: ChannelManager,
    private val logger: com.loglab.app.core.report.AppLogger
) {
    /** 当前检查阶段（供 UI 在"检查中"时显示到哪一步了，避免用户以为卡死） */
    private val _phase = MutableStateFlow("")
    val phase: StateFlow<String> = _phase.asStateFlow()

    private fun setPhase(text: String) {
        _phase.value = text
    }

    suspend fun run(): StartupCheckResult = withContext(Dispatchers.IO) {
        val cur = settings.current()
        logger.log("CHECK", "启动检查开始：已配对=${cur.adbPaired}，存档地址=${cur.adbHost}:${cur.adbPort}")
        setPhase("正在连接 ${cur.adbHost}:${cur.adbPort}…")

        // ① 未配对 → 引导
        if (!cur.adbPaired) {
            logger.log("CHECK", "未配对，跳过连接与扫描")
            return@withContext StartupCheckResult.NeedPairing
        }

        // ② 直连与 mDNS 并行：mDNS 从启动即开始扫描。
        // 取「首个非空快照」而非等满扫描窗口——直连成功路径要用它核对端口是否最新，
        // 直连失败路径要用它救活连接，通常 1~3 秒即可命中。
        val mdnsDevices = async {
            runCatching {
                nsd.discover(MDNS_WINDOW_MS).first { it.isNotEmpty() }
            }.getOrNull().orEmpty()
        }

        val direct = runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }.getOrNull()
        var directUsable = direct?.isSuccess == true
        if (directUsable) {
            // 真实性验证：TLS 握手能过 ≠ adbd 活着。无线调试关闭中/半死时，
            // 旧端口仍会接受握手，但 shell 命令起不来（日志实例：probe 成功 + echo 失败）。
            // 这种"假连接"必须当失败处理，否则 App 会顶着死端口显示"已连接"。
            val verify = runCatching {
                channelManager.adbChannel.execute("echo probe-ok").getOrNull()
            }.getOrNull()?.trim()
            logger.log(
                "CHECK",
                "直连 ${cur.adbHost}:${cur.adbPort} 握手成功；echo 验证=${verify ?: "失败"}"
            )
            if (verify != "probe-ok") {
                directUsable = false
                // 清掉假连接状态，避免通道条显示"已连接"
                runCatching { channelManager.disconnect() }
                logger.log("CHECK", "旧端口无法执行命令（无线调试多半已关闭），转用 mDNS 判定")
                setPhase("端口无响应，正在扫描无线调试服务…")
            }
        }

        if (directUsable) {
            // 旧端口确实活着：核对端口新鲜度——
            //   - mDNS 显示同 IP 有别的端口 → 无线调试已重开，切到新端口；
            //   - mDNS 显示同 IP 就是这个端口 → 存档端口确为最新，保持；
            // 只认"同 IP"，因此绝不会误切到同网段其他设备的服务。
            setPhase("已连接，正在核对端口是否最新…")

            val snapshot = mdnsDevices.await()
            // 同样排除配对端口，只看真正能抓日志的服务
            val mine = snapshot.filter {
                it.host == cur.adbHost && it.kind != NsdDiscovery.Kind.PAIRING
            }
            val newer = mine.firstOrNull { it.port != cur.adbPort }
            if (newer != null) {
                val oldPort = cur.adbPort
                logger.log("CHECK", "mDNS 发现同 IP 新端口 $oldPort → ${newer.port}，切换并验证")
                settings.update { it.copy(adbHost = newer.host, adbPort = newer.port) }
                val conn = runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }.getOrNull()
                if (conn?.isSuccess == true) {
                    logger.log("CHECK", "新端口 ${newer.port} 可用，已更新")
                    setPhase("")
                    return@withContext StartupCheckResult.PortUpdated(newer.host, oldPort, newer.port)
                }
                // 新端口不可用：回滚，保住本来能用的旧端口
                logger.log("CHECK", "新端口 ${newer.port} 不可用：${conn?.exceptionOrNull()?.message}，回滚 $oldPort")
                settings.update { it.copy(adbHost = cur.adbHost, adbPort = oldPort) }
                runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }
            } else {
                logger.log(
                    "CHECK",
                    if (mine.isNotEmpty()) {
                        "mDNS 确认 ${cur.adbPort} 就是当前无线调试端口（同 IP 服务端口一致），保持连接"
                    } else {
                        "mDNS ${MDNS_WINDOW_MS}ms 内未发现同 IP 服务" +
                            "（${snapshot.format().ifBlank { "无任何发现" }}），保持现有端口"
                    }
                )
            }
            setPhase("")
            return@withContext StartupCheckResult.Ready(cur.adbHost, cur.adbPort)
        }

        // ③ 直连失败或不可信：用 mDNS 结果救活连接（无线调试重开过则端口必变）
        if (direct?.isSuccess == true) {
            logger.log("CHECK", "直连握手成功但无法执行命令（adbd 半死）")
        } else {
            logger.log("CHECK", "直连 ${cur.adbHost}:${cur.adbPort} 失败：${direct?.exceptionOrNull()?.message}")
        }
        setPhase("正在扫描无线调试服务…")

        // ③ 直连失败：用 mDNS 结果核对端口（无线调试重开过则端口必变）
        var devices = mdnsDevices.await()
        logger.log("CHECK", "mDNS 第 1 轮：发现 ${devices.size} 个服务${devices.format()}")

        nsd.lastScanDiagnostics?.let { d ->
            logger.log(
                "CHECK",
                "mDNS 诊断：发现 ${d.servicesFound} 个 / 解析成功 ${d.resolveSucceeded} / " +
                    "解析失败 ${d.resolveFailed}${d.startFailed.takeIf { it.isNotEmpty() }?.let { " / 启动失败 $it" } ?: ""}"
            )
        }

        // 排除配对端口：它同样是 TLS，但每次点开配对弹窗都随机、配对后即失效，
        // 一旦被当成抓取端口，表现就是"连上了却抓不到任何日志"。
        val candidates = devices.filter { it.kind != NsdDiscovery.Kind.PAIRING }
        // 优先级：同 IP 的无线调试 TLS 端口 > 任意 TLS 端口 > 同 IP 明文 > 任意明文
        val matched = candidates.firstOrNull {
            it.kind == NsdDiscovery.Kind.TLS_CONNECT && it.host == cur.adbHost
        } ?: candidates.firstOrNull { it.kind == NsdDiscovery.Kind.TLS_CONNECT }
            ?: candidates.firstOrNull { it.host == cur.adbHost }
            ?: candidates.firstOrNull()

        if (matched != null) {
            logger.log("CHECK", "采用服务 ${matched.host}:${matched.port}（tls=${matched.tls}）")
            if (matched.port != cur.adbPort || matched.host != cur.adbHost) {
                val oldPort = cur.adbPort
                settings.update { it.copy(adbHost = matched.host, adbPort = matched.port) }
                logger.log("CHECK", "端口/地址变化：$oldPort → ${matched.port}，已更新配置并重连")
                val conn = runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }.getOrNull()
                return@withContext if (conn?.isSuccess == true) {
                    logger.log("CHECK", "新端口重连成功")
                    StartupCheckResult.PortUpdated(matched.host, oldPort, matched.port)
                } else {
                    logger.log("CHECK", "新端口重连失败：${conn?.exceptionOrNull()?.message}")
                    StartupCheckResult.NotReachable(
                        "发现服务 ${matched.host}:${matched.port}，但连接失败：" +
                            (conn?.exceptionOrNull()?.message ?: direct?.exceptionOrNull()?.message)
                    )
                }
            }
            // 地址未变但直连失败：可能是瞬时抖动，重试一次
            val retry = runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }.getOrNull()
            if (retry?.isSuccess == true) {
                logger.log("CHECK", "同地址重试连接成功")
                return@withContext StartupCheckResult.Ready(cur.adbHost, cur.adbPort)
            }
            logger.log("CHECK", "同地址重试仍失败：${retry?.exceptionOrNull()?.message}")
        }

        // ④ 下结论：mDNS 无发现（无线调试未开启/ROM 限制）或发现但连不上，附诊断
        val detail = buildString {
            append(
                if (devices.isEmpty()) scanSummary()
                else "发现 ${devices.size} 个无线调试服务但连接不上"
            )
            direct?.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?.let { append("；直连探测：").append(it) }
        }
        logger.log("CHECK", "结论：无法连接（$detail）")
        setPhase("")
        StartupCheckResult.NotReachable(detail)
    }

    private fun List<NsdDiscovery.Device>.format(): String =
        if (isEmpty()) "" else "：" + joinToString { "${it.host}:${it.port}[${it.label}]" }

    /** 把 mDNS 扫描诊断翻译成人话，区分"没扫到"与"扫到但解析失败" */
    private fun scanSummary(): String {
        val d = nsd.lastScanDiagnostics
        return when {
            d == null ->
                "未发现调试端口，请确认无线调试已开启！"
            d.servicesFound > 0 ->
                "未发现调试端口，请确认无线调试已开启！（mDNS 发现 ${d.servicesFound} 个服务但地址解析失败，系统 mDNS 受限，可在设置页手动填写 IP:端口）"
            d.startFailed.isNotEmpty() ->
                "未发现调试端口，请确认无线调试已开启！（mDNS 扫描启动失败：${d.startFailed.joinToString("、")}，可在设置页手动填写 IP:端口）"
            else ->
                "未发现调试端口，请确认无线调试已开启！（mDNS 扫描无任何结果：若开关确实开着，可能是系统限制了 mDNS，可在设置页手动填写 IP:端口）"
        }
    }
}
