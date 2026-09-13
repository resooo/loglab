package com.loglab.app.core.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通过 NSD/mDNS 自动发现局域网内开启了「无线调试」的 adbd。
 *
 * 无线调试（Android 11+）的 adbd 通过 mDNS 广播 `_adb-tls-connect._tcp.`（TLS 加密）；
 * 通过 `adb tcpip` 开启的网络 adb 广播 `_adb._tcp.`（明文）。两者都发现，由用户选择。
 *
 * 可靠性要点（针对“服务明明存在却空手而归”）：
 *  1. NsdManager 同一时刻只允许一个 pending resolve——两种服务类型并发 resolve 时
 *     第二个会立即 FAILURE_ALREADY_ACTIVE。改为单消费者串行队列逐个解析；
 *  2. 解析失败（系统 mDNS 限流常见）延时 300ms 自动重试一次；
 *  3. 单次 resolve 3s 超时兜底，避免回调丢失导致协程挂死；
 *  4. 记录诊断计数（发现数/解析成功/解析失败/启动失败），供上层给出精确提示。
 *
 * 注意：部分厂商 ROM（如 ColorOS / OPPO）会禁用 mDNS 组播，导致扫描不到设备，
 * 此时应引导用户手动填写「无线调试」主界面显示的 IP:端口。
 */
@Singleton
class NsdDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * mDNS 服务种类。同一台手机开启「USB 调试」后可能同时通告多个 ADB 服务：
     * 无线调试 TLS 端口、明文 adb tcpip 端口，以及配对端口（同样是 TLS，
     * 但每次点开配对弹窗都重新随机、配对后即失效，绝不能当抓取端口用）。
     */
    enum class Kind { TLS_CONNECT, PAIRING, PLAIN, UNKNOWN }

    data class Device(
        val host: String,
        val port: Int,
        val serviceName: String,
        /** 是否为「无线调试」的 TLS 连接端口（唯一可用于抓日志的无线通道） */
        val tls: Boolean,
        val kind: Kind = Kind.UNKNOWN,
        val serviceType: String = ""
    ) {
        val label: String
            get() = when (kind) {
                Kind.TLS_CONNECT -> "无线调试(TLS)"
                Kind.PAIRING -> "配对端口(仅配对用，不可抓日志)"
                Kind.PLAIN -> "明文 adb tcpip"
                Kind.UNKNOWN -> "未知服务"
            }
    }

    /** 最近一轮扫描的诊断数据（每次 discover() 开始时重置） */
    data class ScanDiagnostics(
        val servicesFound: Int,
        val resolveSucceeded: Int,
        val resolveFailed: Int,
        val lastResolveError: Int,
        val startFailed: List<String>
    )

    @Volatile
    var lastScanDiagnostics: ScanDiagnostics? = null
        private set

    private class ResolveFailure(val errorCode: Int) : Exception("resolve failed: $errorCode")

    fun discover(timeoutMs: Long = 7000): Flow<List<Device>> = callbackFlow {
        val found = LinkedHashMap<String, Device>()
        val lock = Any()

        fun snapshot(): List<Device> = synchronized(lock) { ArrayList(found.values) }

        // ---- 诊断计数（回调在主线程，用 AtomicInteger 保证跨线程可见性）----
        val servicesFound = AtomicInteger(0)
        val resolveOk = AtomicInteger(0)
        val resolveFail = AtomicInteger(0)
        val lastResolveError = AtomicInteger(Int.MIN_VALUE)
        val startFailed = mutableListOf<String>()

        fun publishDiag() {
            lastScanDiagnostics = ScanDiagnostics(
                servicesFound = servicesFound.get(),
                resolveSucceeded = resolveOk.get(),
                resolveFailed = resolveFail.get(),
                lastResolveError = lastResolveError.get(),
                startFailed = synchronized(startFailed) { startFailed.toList() }
            )
        }

        fun accept(serviceInfo: NsdServiceInfo) {
            // host 在 Android 13+ 已废弃且可能返回 null，优先用 hostAddresses
            val inet = serviceInfo.hostAddresses?.firstOrNull() ?: serviceInfo.host ?: return
            val host = inet.hostAddress ?: return
            val port = serviceInfo.port
            if (port <= 0) return
            resolveOk.incrementAndGet()
            val kind = kindOf(serviceInfo)
            synchronized(lock) {
                found["$host:$port"] = Device(
                    host = host,
                    port = port,
                    serviceName = serviceInfo.serviceName,
                    tls = kind == Kind.TLS_CONNECT,
                    kind = kind,
                    serviceType = serviceInfo.serviceType ?: ""
                )
            }
            publishDiag()
            trySend(snapshot())
        }

        @Suppress("DEPRECATION")
        suspend fun resolveOnce(info: NsdServiceInfo): NsdServiceInfo =
            suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resumeWith(Result.failure(ResolveFailure(errorCode)))
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        if (cont.isActive) cont.resumeWith(Result.success(si))
                    }
                }
                val submitted = runCatching { nsd.resolveService(info, listener) }
                if (submitted.isFailure && cont.isActive) {
                    cont.resumeWith(Result.failure(ResolveFailure(-1)))
                }
            }

        /** 失败延时重试一次 + 3s 超时兜底，全部失败返回 null */
        suspend fun resolveWithRetry(info: NsdServiceInfo): NsdServiceInfo? {
            repeat(2) { attempt ->
                try {
                    return withTimeout(3_000) { resolveOnce(info) }
                } catch (_: TimeoutCancellationException) {
                    resolveFail.incrementAndGet()
                } catch (e: ResolveFailure) {
                    resolveFail.incrementAndGet()
                    lastResolveError.set(e.errorCode)
                }
                if (attempt == 0) delay(300)
            }
            return null
        }

        // 串行解析队列：避免并发 resolve 触发 FAILURE_ALREADY_ACTIVE
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val resolver = launch {
            for (info in resolveQueue) {
                val resolved = resolveWithRetry(info)
                if (resolved != null) accept(resolved)
            }
        }

        // 必须显式订阅 _adb-tls-pairing：它只在「使用配对码配对设备」弹窗打开期间广播，
        // 配对流程（自动识别端口）完全依赖它；不订阅则配对端口永远扫不到。
        val types = listOf("_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.", "_adb._tcp.")
        val listeners = types.map { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onDiscoveryStopped(regType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    servicesFound.incrementAndGet()
                    publishDiag()
                    resolveQueue.trySend(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    synchronized(startFailed) { startFailed += "$type(code=$errorCode)" }
                    publishDiag()
                }

                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) = Unit
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure {
                    synchronized(startFailed) { startFailed += "$type(${it.message})" }
                    publishDiag()
                }
            activeListeners.add(listener)
            type to listener
        }

        launch {
            delay(timeoutMs)
            publishDiag()
            trySend(snapshot())
            resolveQueue.close()
            close()
        }

        awaitClose {
            listeners.forEach { (_, listener) ->
                runCatching { nsd.stopServiceDiscovery(listener) }
                activeListeners.remove(listener)
            }
            resolveQueue.close()
        }
    }

    /**
     * 精确判定服务种类：只看 serviceType，不能用 "是否包含 tls" 粗略判断——
     * 配对服务 `_adb-tls-pairing._tcp.` 同样含 tls，误当抓取端口会导致连接必然失败。
     */
    private fun kindOf(si: NsdServiceInfo): Kind {
        val type = (si.serviceType ?: "").lowercase()
        return when {
            type.contains("_adb-tls-pairing") -> Kind.PAIRING
            type.contains("_adb-tls-connect") -> Kind.TLS_CONNECT
            type.contains("_adb._tcp") -> Kind.PLAIN
            else -> Kind.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // 活跃发现注册表 + 退出清理
    //
    // 背景：无线调试每次重开都会随机新端口，而 Android 系统 mDNS 解析器
    // （NsdManager 底层的 mdnsd）会缓存服务记录到 TTL 到期。App 重启后拿到
    // 的可能仍是**几小时前的旧端口**，表现就是"扫到了但连不上"。
    //
    // 系统没有提供清空 mDNS 缓存的 API，可行的缓解手段是：
    //  1. App 退出时显式 stopServiceDiscovery——停止 discovery session 会让
    //     解析器释放该 session 关联的缓存条目，下次启动重新发起查询；
    //  2. 退出时对已知端口做一次 TCP 探活，把已失效端口记入黑名单，下次启动
    //     即便系统仍返回旧记录也不会被采用（兜底，见 [isStale]）。
    // ------------------------------------------------------------------

    /** 当前活跃的 discovery listener（退出时强制停止用；弱引用避免泄漏） */
    private val activeListeners: MutableSet<NsdManager.DiscoveryListener> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                java.util.WeakHashMap<NsdManager.DiscoveryListener, Boolean>()
            )
        )

    /**
     * 已被证实失效的端口 → 证伪时间戳。仅作跨重启的短期兜底，不持久化
     * （无线调试重开后同端口复用的概率极低，但仍留观察窗）。
     */
    private val stalePorts = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private fun markStale(port: Int) {
        stalePorts[port] = System.currentTimeMillis()
    }

    /** 该端口是否处于「近期被证伪」状态（观察窗内不应被优先采用） */
    fun isStale(port: Int): Boolean {
        val at = stalePorts[port] ?: return false
        return System.currentTimeMillis() - at < STALE_WINDOW_MS
    }

    /**
     * 停止全部活跃的 mDNS 发现。
     *
     * App 退出（ON_STOP）时调用：停止 discovery session 会让系统 mDNS 解析器
     * 释放该 session 的缓存关联，避免下次启动直接命中过期记录。
     */
    fun stopAll() {
        val snapshot = synchronized(activeListeners) { activeListeners.toList() }
        snapshot.forEach { listener ->
            runCatching { nsd.stopServiceDiscovery(listener) }
            synchronized(activeListeners) { activeListeners.remove(listener) }
        }
    }

    /**
     * 对给定端口做 TCP 探活（只判断能否建立连接，不做 TLS 握手）。
     * 用于退出时快速证伪旧端口，比完整 ADB 连接轻得多。
     */
    fun probePort(port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
        if (port <= 0) return false
        return runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(LOOPBACK_HOST, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * 退出清理：对已知端口探活，失效的记入黑名单，并停止全部发现。
     *
     * @param knownPorts 配置端口 + 本轮扫描到的端口；已关闭的会被标记 stale，
     *                   下次启动优先尝试其他候选。
     */
    fun cleanupOnExit(knownPorts: Collection<Int>) {
        val ports = knownPorts.filter { it > 0 }.distinct()
        ports.forEach { port -> if (!probePort(port)) markStale(port) }
        stopAll()
    }

    private companion object {
        /** 证伪端口观察窗：30 分钟 */
        const val STALE_WINDOW_MS = 30L * 60 * 1000

        /** 探活超时：回环连接毫秒级返回，400ms 足够 */
        const val PROBE_TIMEOUT_MS = 400

        /** 只探回环：App 与 adbd 同机，回环端口开放即说明 adbd 在该端口监听 */
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
