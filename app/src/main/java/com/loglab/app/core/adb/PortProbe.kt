package com.loglab.app.core.adb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回环端口主动探测器 —— mDNS 失效时的兜底发现手段。
 *
 * **为什么需要它**：
 * Android 无线调试每次重开都会分配新端口，而 adbd 的 mDNS 通告
 * （`_adb-tls-connect._tcp.`）在部分 ROM 上并不可靠：
 *  - 一加 / ColorOS 等实测：关闭再打开无线调试后，系统 mDNS 解析器
 *    **固守关闭前的旧记录**（serviceName + 旧端口），新端口的通告
 *    迟迟不出现，等待 60 秒以上仍不刷新；
 *  - 旧记录对应的端口早已关闭，表现为「mDNS 报的端口全是半死/拒绝连接」，
 *    而真实端口从未出现在通告列表里。
 *
 * 此时唯一可靠的发现途径是**直接探测**：App 与 adbd 同机，
 * `127.0.0.1:<port>` 的回环连接是毫秒级的，全端口范围扫描代价可接受。
 *
 * **扫描策略**（按代价从低到高，命中即停）：
 *  1. 已知端口邻域（±[NEAR_WINDOW]）——端口分配常在上次值附近漂移；
 *  2. 全范围 [PORT_MIN, PORT_MAX] 并发扫描——回环下约 2~4 秒可完成。
 *
 * 注意：只探测「端口是否可建立 TCP 连接」，不做 TLS 握手——
 * 握手与配对校验由上层 [StartupCheck] 的 echo 复验负责，
 * 这里只负责把**候选端口**找出来，误报（非 adbd 的监听端口）
 * 会被后续复验自然淘汰。
 */
@Singleton
class PortProbe @Inject constructor(
    @Suppress("unused") @ApplicationContext private val context: Context
) {

    /**
     * 探测结果：一个可建立 TCP 连接的端口。
     * 不保证是 adbd——需上层复验。
     */
    data class Hit(val port: Int, val nearKnown: Boolean)

    /**
     * 扫描可用的 adb 候选端口。
     *
     * @param knownPorts 已知端口（上次成功连接的、mDNS 报出的），用于邻域优先扫描
     * @param knownFirst 是否先扫已知端口邻域（命中即返回，省时间）
     * @param timeoutMs  全范围扫描的总超时兜底；超时返回已扫到的结果
     * @return 命中的端口列表，邻域命中排在前面
     */
    suspend fun scan(
        knownPorts: Collection<Int> = emptyList(),
        knownFirst: Boolean = true,
        timeoutMs: Long = FULL_SCAN_TIMEOUT_MS,
        maxResults: Int = MAX_RESULTS
    ): List<Hit> = withContext(Dispatchers.IO) {
        val known = knownPorts.filter { it in PORT_MIN..PORT_MAX }.distinct()
        // 用列表保序：邻域命中优先、已知端口次之、全范围兜底最后
        val hits = LinkedHashMap<Int, Hit>()

        // ① 邻域优先：已知端口 ±NEAR_WINDOW。端口重分配常在上次值附近，
        //    这一轮通常几十毫秒内出结果。
        //    注意：邻域里可能混有**其它 App 的监听端口**，它们不是 adbd，
        //    会被上层复验淘汰——但每次复验要花 ~2-3 秒，因此必须限量返回，
        //    否则 8 个误报就是 20+ 秒的等待（这正是"扫描很慢"的根因）。
        if (knownFirst && known.isNotEmpty()) {
            val near = buildSet {
                known.forEach { base ->
                    val lo = (base - NEAR_WINDOW).coerceAtLeast(PORT_MIN)
                    val hi = (base + NEAR_WINDOW).coerceAtMost(PORT_MAX)
                    (lo..hi).forEach { add(it) }
                }
            }.filter { it !in known }
            val nearHits = scanPorts(near).take(maxResults)
            nearHits.forEach { hits[it] = Hit(it, true) }
            known.forEach { hits.putIfAbsent(it, Hit(it, true)) }
            if (hits.isNotEmpty()) return@withContext hits.values.toList()
        }

        // ② 全范围兜底：并发扫描，回环连接毫秒级，实测量级 2~4 秒。
        //    结果同样限量——正确的 adbd 端口通常最先被复验命中。
        val all = (PORT_MIN..PORT_MAX).filter { it !in known && it !in hits }
        val found = withTimeoutOrNull(timeoutMs) {
            scanPorts(all)
        }.orEmpty().take(maxResults)
        found.forEach { hits[it] = Hit(it, false) }

        // 已知端口本身也纳入候选（可能与真实端口重合，交给上层复验）
        known.forEach { hits.putIfAbsent(it, Hit(it, true)) }
        hits.values.toList()
    }

    /**
     * 并发探测给定端口集合，返回其中**可连接**的端口。
     * 用信号量限制并发数，避免瞬时创建上万个 socket 导致 fd 耗尽。
     */
    private suspend fun scanPorts(ports: Collection<Int>): List<Int> = coroutineScope {
        val sem = Semaphore(MAX_CONCURRENCY)
        ports.map { port ->
            async {
                sem.withPermit {
                    if (canConnect(port)) port else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** 单个端口能否建立 TCP 连接（不做任何协议交互） */
    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * 快速校验单个端口当前是否仍可连接（用于复验前的二次确认）。
     */
    fun isOpen(port: Int): Boolean = canConnect(port)

    companion object {
        /**
         * adbd 无线调试的端口分配范围。
         * Android 从 [PORT_MIN, PORT_MAX] 中随机取值（实测 3xxxx~5xxxx），
         * 这里取宽松区间以覆盖不同 ROM。
         */
        const val PORT_MIN = 30000
        const val PORT_MAX = 65000

        /** 邻域窗口：已知端口 ±该值 */
        const val NEAR_WINDOW = 3000

        /** 单端口连接超时：回环毫秒级返回，300ms 足够 */
        const val CONNECT_TIMEOUT_MS = 300

        /** 并发上限：兼顾速度与 fd 占用 */
        const val MAX_CONCURRENCY = 256

        /**
         * 单轮最多返回的候选数。每次候选复验要经历 TLS 握手 + echo（~2-3 秒），
         * 误报过多会让用户等待时间线性膨胀；8 个已能覆盖正常场景。
         */
        const val MAX_RESULTS = 8

        /** 全范围扫描总超时 */
        const val FULL_SCAN_TIMEOUT_MS = 15_000L

        private const val LOOPBACK = "127.0.0.1"
    }
}
