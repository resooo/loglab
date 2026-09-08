package com.loglab.app.core.connect

import androidx.annotation.StringRes
import com.loglab.app.R
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 单轮 mDNS 扫描窗口：无线调试重开后通告可能延迟数秒；6 秒内必有结论，不让用户干等 */
private const val MDNS_WINDOW_MS = 6000L

/** 幽灵缓存复核窗口：与首轮等长，给刚重开的无线调试新通告足够时间出现 */
private const val MDNS_RESCAN_WINDOW_MS = 6000L

/**
 * loopback 地址字面量。
 * 注意：用 "127.0.0.1" 而非 "localhost"——localhost 在部分 ROM 上先解析出 IPv6 ::1，
 * 而 adbd 无线调试只监听 IPv4 回环，::1 会先撞一次失败。
 */
private const val LOOPBACK = "127.0.0.1"

/**
 * 检查阶段的本地化载体：core 层只产出资源 ID + 参数，
 * UI 层（Composable 上下文）用 stringResource 解析——Android ≤12 的 per-app locale
 * 只作用于 Activity 层，core/VM 层直接拼中文会漏翻。
 */
data class CheckPhase(@StringRes val res: Int? = null, val args: List<Any> = emptyList())

/** 启动智能检查结果 */
sealed class StartupCheckResult {
    abstract val connected: Boolean
    abstract val message: String
    /** UI 层本地化：资源 ID + 格式化参数（与 [message] 内容一致，中文 message 仅供日志） */
    abstract val messageRes: Int
    open val messageArgs: List<Any> = emptyList()
    /** 异常状态（需要引导用户操作） */
    open val needsAction: Boolean get() = !connected

    /** 一切就绪 */
    data class Ready(val host: String, val port: Int) : StartupCheckResult() {
        override val connected get() = true
        override val message get() = "ADB 已连接 $host:$port"
        override val messageRes get() = R.string.status_ready_fmt
        override val messageArgs get() = listOf(host, port)
    }

    /** mDNS 发现无线调试端口/地址已变化，已自动更新并重新连接 */
    data class PortUpdated(val host: String, val oldPort: Int, val newPort: Int) : StartupCheckResult() {
        override val connected get() = true
        override val message get() =
            if (oldPort == newPort) "已连接 $host:$newPort"
            else "检测到无线调试端口变化（$oldPort → $newPort），已自动更新并连接"
        override val messageRes get() =
            if (oldPort == newPort) R.string.status_ready_fmt
            else R.string.status_port_updated_fmt
        override val messageArgs get() =
            if (oldPort == newPort) listOf(host, newPort) else listOf(oldPort, newPort)
    }

    /** 未配对，需要引导（与「无线调试未开启」分开提示，内容互不混淆） */
    object NeedPairing : StartupCheckResult() {
        override val connected get() = false
        override val message get() =
            "尚未完成无线调试配对：请到「设置 → 开发者选项 → 无线调试 → 使用配对码配对设备」，然后在 App 设置页完成配对"
        override val messageRes get() = R.string.status_need_pairing
    }

    /** 无线调试未开启（mDNS 只有幽灵缓存记录/完全无发现）→ UI 显示「去开启」直达开发者选项 */
    object DebugOff : StartupCheckResult() {
        override val connected get() = false
        override val message get() = "无线调试未开启，去开启"
        override val messageRes get() = R.string.status_debug_off
    }

    /** 已配对但连不上（多为无线调试被关闭或网络不通） */
    data class NotReachable(
        val detailRes: Int = R.string.status_not_reachable,
        val detailArgs: List<Any> = emptyList()
    ) : StartupCheckResult() {
        override val connected get() = false
        override val message get() = "已配对但无法连接（详见 messageRes）"
        override val messageRes get() = detailRes
        override val messageArgs get() = detailArgs
    }
}

/**
 * 启动智能检查（v1.8.0 起 **127.0.0.1 loopback 优先**）：
 *
 * App 与 adbd 跑在同一台手机上，无线调试端口对本机回环地址始终可达——
 * 与 Wi-Fi 网段无关，换网/断网重连都不会失效。mDNS 的价值只剩「告知当前端口」，
 * 因此：
 *  1. 未配对 → 提示引导用户到设置页配对；
 *  2. 已配对 → 直连存档地址与 mDNS 扫描**同时启动**（保持"启动即扫描"时序，
 *     无线调试刚重开时也能第一时间发现新端口）；
 *  3. 直连成功（过 echo 复验）→ 核对端口新鲜度后立即就绪；
 *  4. 直连失败 → 对 mDNS 发现的每个端口，按 **127.0.0.1:端口 → mDNS解析IP:端口**
 *     顺序尝试：loopback 毫秒级失败（connection refused），远快于局域网 IP 的
 *     秒级超时；连错端口几乎无代价，连上即持久化；
 *  5. 老版本存档是局域网 IP 的，直连失败后先试 127.0.0.1:同端口（一次性迁移）；
 *  6. 第一轮 mDNS 空手而归/疑似幽灵缓存 → 自动再扫一轮；
 *  7. 仍无结果 → 无线调试很可能未开启；提示中附带精确诊断。
 *
 * 安全性：所有候选都必须过 echo 复验，失败一律回滚原配置——
 * 同网段其他设备的端口被误作候选时，TLS 握手（配对密钥）必然失败，不会污染存档。
 */
@Singleton
class StartupCheck @Inject constructor(
    private val settings: SettingsRepository,
    private val nsd: NsdDiscovery,
    private val channelManager: ChannelManager,
    private val logger: com.loglab.app.core.report.AppLogger
) {
    /** 当前检查阶段（供 UI 在"检查中"时显示到哪一步了，避免用户以为卡死） */
    private val _phase = MutableStateFlow(CheckPhase())
    val phase: StateFlow<CheckPhase> = _phase.asStateFlow()

    private fun setPhase() {
        _phase.value = CheckPhase()
    }

    private fun setPhase(@StringRes res: Int, vararg args: Any) {
        _phase.value = CheckPhase(res, args.toList())
    }

    /** 候选验证结论：OK=可用；DEAD=TLS 握手成功但命令执行失败（adbd 半死，无线调试已关闭的强信号）；UNREACHABLE=连接/握手本身失败 */
    private enum class Verify { OK, DEAD, UNREACHABLE }

    /** 连接当前配置地址并做 echo 复验（握手成功 ≠ adbd 活着） */
    private suspend fun connectVerified(): Verify {
        val connected = runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }
            .getOrNull()?.isSuccess == true
        if (!connected) return Verify.UNREACHABLE
        return if (echoOk()) Verify.OK else Verify.DEAD
    }

    /**
     * 真实性复验：TLS 握手能过 ≠ adbd 活着（无线调试关闭后 adbd 半死时仍监听 TLS）。
     * 一切「连接成功」的结论（Ready / PortUpdated）都必须先过这一关。
     */
    private suspend fun echoOk(): Boolean =
        runCatching {
            channelManager.adbChannel.execute("echo probe-ok").getOrNull()
        }.getOrNull()?.trim() == "probe-ok"

    /**
     * 对一个候选端口生成地址尝试序列：loopback 恒为第一顺位，
     * mDNS 解析出的 IP 作为回退（防极端 ROM 不监听回环）。
     */
    private fun addrCandidates(port: Int, mdnsHost: String?): List<Pair<String, Int>> =
        buildList {
            add(LOOPBACK to port)
            if (!mdnsHost.isNullOrBlank() && mdnsHost != LOOPBACK) add(mdnsHost to port)
        }

    /**
     * 采用一个候选地址：写入配置 → 连接 + echo 复验。
     * 返回 [Verify.OK] 表示地址留在配置里；失败自动断开（配置保留候选值，
     * 由调用方决定回滚——逐候选尝试时用 [rollbackTo] 恢复）。
     */
    private suspend fun tryAdopt(host: String, port: Int): Verify {
        settings.update { it.copy(adbHost = host, adbPort = port) }
        val v = connectVerified()
        if (v != Verify.OK) runCatching { channelManager.disconnect() }
        return v
    }

    /** 回滚到原配置并重连（尽力而为，回滚失败不影响后续流程） */
    private suspend fun rollbackTo(host: String, port: Int) {
        settings.update { it.copy(adbHost = host, adbPort = port) }
        runCatching { channelManager.autoConnect(ChannelPolicy.ADB_ONLY) }
    }

    suspend fun run(): StartupCheckResult = withContext(Dispatchers.IO) {
        val cur = settings.current()
        logger.log("CHECK", "启动检查开始：已配对=${cur.adbPaired}，存档地址=${cur.adbHost}:${cur.adbPort}")
        setPhase(R.string.check_phase_connecting, cur.adbHost, cur.adbPort)
        // 「TLS 握手成功但 echo 失败」= adbd 半死 = 无线调试已关闭（关闭过程中 adbd 仍短暂监听 TLS，
        // 系统 mDNS 还会残留幽灵通告）。只要出现该信号，最终结论一律 DebugOff，不被幽灵通告带偏。
        var sawDeadAdbd = false

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
                sawDeadAdbd = true
                // 清掉假连接状态，避免通道条显示"已连接"
                runCatching { channelManager.disconnect() }
                logger.log("CHECK", "旧端口无法执行命令（adbd 半死：无线调试多半已关闭），转用 mDNS 判定")
                setPhase(R.string.check_phase_dead_port)
            }
        }

        // ③ 直连成功：核对端口新鲜度——
        //    mDNS 报出的无线调试 TLS 端口与存档不同 → 无线调试已重开，切换新端口。
        //    loopback 方案下不再按 host 匹配（存档是 127.0.0.1，mDNS 报的是局域网 IP，
        //    永不相等）；新端口按 127.0.0.1 优先依次尝试，验证不过自动回滚旧端口。
        if (directUsable) {
            setPhase(R.string.check_phase_check_port)
            val snapshot = mdnsDevices.await()
            val newer = snapshot.filter {
                it.kind == NsdDiscovery.Kind.TLS_CONNECT && it.port != cur.adbPort
            }
            if (newer.isNotEmpty()) {
                logger.log(
                    "CHECK",
                    "mDNS 发现端口与存档不同：${newer.joinToString { "${it.host}:${it.port}" }}，逐一尝试验证"
                )
                for (dev in newer) {
                    for ((host, port) in addrCandidates(dev.port, dev.host)) {
                        setPhase(R.string.check_phase_verifying, host, port)
                        when (tryAdopt(host, port)) {
                            Verify.OK -> {
                                logger.log("CHECK", "新端口 $port 可用（echo 复验通过），已更新为 $host:$port")
                                setPhase()
                                return@withContext StartupCheckResult.PortUpdated(host, cur.adbPort, port)
                            }
                            Verify.DEAD -> {
                                sawDeadAdbd = true
                                logger.log("CHECK", "候选 $host:$port 握手成功但命令执行失败（adbd 半死），继续")
                            }
                            Verify.UNREACHABLE ->
                                logger.log("CHECK", "候选 $host:$port 连接/握手失败，继续")
                        }
                    }
                }
                // 新端口全部不可用：保住本来就能用的旧端口
                logger.log("CHECK", "mDNS 新端口均不可用，回滚并保持 ${cur.adbHost}:${cur.adbPort}")
                rollbackTo(cur.adbHost, cur.adbPort)
            } else {
                logger.log(
                    "CHECK",
                    if (snapshot.any { it.kind == NsdDiscovery.Kind.TLS_CONNECT }) {
                        "mDNS 确认 ${cur.adbPort} 就是当前无线调试端口，保持连接"
                    } else {
                        "mDNS ${MDNS_WINDOW_MS}ms 内未发现无线调试服务" +
                            "（${snapshot.format().ifBlank { "无任何发现" }}），保持现有端口"
                    }
                )
            }
            setPhase()
            return@withContext StartupCheckResult.Ready(cur.adbHost, cur.adbPort)
        }

        // ④ 直连失败：先做一次 loopback 迁移尝试（老版本存档是局域网 IP 的一次性修复），
        //    随后用 mDNS 端口救活连接（无线调试重开过则端口必变）。
        if (direct?.isSuccess == true) {
            logger.log("CHECK", "直连握手成功但无法执行命令（adbd 半死）")
        } else {
            logger.log("CHECK", "直连 ${cur.adbHost}:${cur.adbPort} 失败：${direct?.exceptionOrNull()?.message}")
        }

        if (cur.adbHost != LOOPBACK && cur.adbPort > 0) {
            setPhase(R.string.check_phase_trying, LOOPBACK, cur.adbPort)
            when (tryAdopt(LOOPBACK, cur.adbPort)) {
                Verify.OK -> {
                    logger.log("CHECK", "loopback 同端口连通（echo 复验通过），存档已迁移为 127.0.0.1")
                    setPhase()
                    return@withContext StartupCheckResult.Ready(LOOPBACK, cur.adbPort)
                }
                Verify.DEAD -> {
                    sawDeadAdbd = true
                    logger.log("CHECK", "loopback:${cur.adbPort} 握手成功但命令执行失败（adbd 半死），等待 mDNS 结果")
                }
                Verify.UNREACHABLE ->
                    logger.log("CHECK", "loopback:${cur.adbPort} 不通，等待 mDNS 结果")
            }
        }

        setPhase(R.string.check_phase_scanning)
        val devices = mdnsDevices.await()
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
        // 只信 TLS 服务（明文 _adb._tcp 需 adb tcpip 预开启，极少见，保留兜底）。
        // 端口相同的（幽灵缓存）排后、新端口优先——重开场景下新端口才有救。
        val candidates = devices
            .filter { it.kind != NsdDiscovery.Kind.PAIRING }
            .sortedWith(
                compareBy(
                    { it.kind != NsdDiscovery.Kind.TLS_CONNECT },
                    { it.port == cur.adbPort }
                )
            )
        for (dev in candidates) {
            for ((host, port) in addrCandidates(dev.port, dev.host)) {
                setPhase(R.string.check_phase_trying, host, port)
                when (tryAdopt(host, port)) {
                    Verify.OK -> {
                        logger.log("CHECK", "采用 $host:$port（echo 复验通过）")
                        setPhase()
                        return@withContext StartupCheckResult.PortUpdated(host, cur.adbPort, port)
                    }
                    Verify.DEAD -> {
                        sawDeadAdbd = true
                        logger.log("CHECK", "候选 $host:$port 握手成功但命令执行失败（adbd 半死，疑为幽灵通告）")
                    }
                    Verify.UNREACHABLE ->
                        logger.log("CHECK", "候选 $host:$port 连接/握手失败")
                }
            }
        }
        if (candidates.isNotEmpty()) {
            logger.log("CHECK", "全部 ${candidates.size} 个候选地址均验证失败，回滚原配置")
            rollbackTo(cur.adbHost, cur.adbPort)
        }

        // ⑤ 幽灵缓存防御：mDNS 只报出与存档**同端口**的服务 = 系统缓存的"幽灵"通告
        //    （无线调试重开端口必变；关闭后通告也该消失）。
        //    ★ 此时不能立即断定未开启：无线调试**刚重开**时，新端口的 mDNS 通告
        //    常常要数秒才会被系统发现（实测可晚 11 秒+），自动再扫一轮，
        //    扫到新端口就直接尝试并复验，把"手动重试"变成"自动等待"。
        //    （loopback 方案下只比较端口；存档若是局域网 IP，上一步迁移失败后
        //    host 也视为"与幽灵一致"。）
        val staleCacheOnly = candidates.isNotEmpty() && candidates.all { it.port == cur.adbPort }
        if (staleCacheOnly) {
            setPhase(R.string.check_phase_waiting)
            logger.log("CHECK", "mDNS 仅报出存档端口（疑为幽灵缓存），自动再扫一轮等待新服务通告")
            val freshList = runCatching {
                nsd.discover(MDNS_RESCAN_WINDOW_MS)
                    .firstOrNull { snap -> snap.any { d -> d.kind != NsdDiscovery.Kind.PAIRING && d.port != cur.adbPort } }
                    ?.filter { it.kind != NsdDiscovery.Kind.PAIRING && it.port != cur.adbPort }
                    .orEmpty()
            }.getOrNull().orEmpty()
            if (freshList.isNotEmpty()) {
                logger.log("CHECK", "mDNS 第 2 轮发现 ${freshList.size} 个新候选：${freshList.format()}")
            }
            for (dev in freshList.sortedBy { it.kind != NsdDiscovery.Kind.TLS_CONNECT }) {
                for ((host, port) in addrCandidates(dev.port, dev.host)) {
                    setPhase(R.string.check_phase_trying, host, port)
                    when (tryAdopt(host, port)) {
                        Verify.OK -> {
                            logger.log("CHECK", "第 2 轮新地址验证通过（echo 复验 OK），连接成功")
                            setPhase()
                            return@withContext StartupCheckResult.PortUpdated(host, cur.adbPort, port)
                        }
                        Verify.DEAD -> {
                            sawDeadAdbd = true
                            logger.log("CHECK", "候选 $host:$port 握手成功但命令执行失败（adbd 半死）")
                        }
                        Verify.UNREACHABLE ->
                            logger.log("CHECK", "候选 $host:$port 连接/握手失败")
                    }
                }
            }
            rollbackTo(cur.adbHost, cur.adbPort)
            logger.log("CHECK", "第 2 轮无可用新候选，确认无线调试未开启")
            setPhase()
            return@withContext StartupCheckResult.DebugOff
        }

        // ⑥ 下结论：mDNS 完全无发现且扫描器没有报"发现但解析失败/启动失败" →
        //    未开启（最常见的唯一原因）；其余受限场景保留详细诊断文案。
        val diag = nsd.lastScanDiagnostics
        val mdnsHealthy = diag == null || (
            diag.servicesFound == 0 && diag.startFailed.isEmpty()
        )
        if (sawDeadAdbd) {
            logger.log("CHECK", "结论：无线调试未开启（存在 adbd 半死信号：握手成功但命令执行失败；mDNS 通告为幽灵缓存，不作数）")
            setPhase()
            return@withContext StartupCheckResult.DebugOff
        }
        val result = when {
            devices.isEmpty() && mdnsHealthy -> StartupCheckResult.DebugOff
            devices.isEmpty() -> scanSummary().let { (res, args) -> StartupCheckResult.NotReachable(res, args) }
            else -> direct?.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }?.let { err ->
                StartupCheckResult.NotReachable(
                    R.string.status_found_cant_connect_fmt, listOf<Any>(devices.size, err)
                )
            } ?: StartupCheckResult.NotReachable(
                R.string.status_found_cant_connect_plain, listOf<Any>(devices.size)
            )
        }
        logger.log("CHECK", "结论：${result.message}")
        setPhase()
        result
    }

    private fun List<NsdDiscovery.Device>.format(): String =
        if (isEmpty()) "" else "：" + joinToString { "${it.host}:${it.port}[${it.label}]" }

    /** 把 mDNS 扫描诊断翻译成人话，区分"没扫到"与"扫到但解析失败" */
    private fun scanSummary(): Pair<Int, List<Any>> {
        val d = nsd.lastScanDiagnostics
        return when {
            d == null ->
                R.string.status_scan_none to emptyList()
            d.servicesFound > 0 ->
                R.string.status_scan_resolve_fmt to listOf<Any>(d.servicesFound)
            d.startFailed.isNotEmpty() ->
                R.string.status_scan_startfail_fmt to listOf<Any>(d.startFailed.joinToString("、"))
            else ->
                R.string.status_scan_noresult to emptyList()
        }
    }
}
