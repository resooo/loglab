package com.loglab.app.core.adb

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.loglab.app.core.report.AppLogger
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 退出时的 mDNS 缓存清理器。
 *
 * **问题**：无线调试每次重开都会分配新端口，而 Android 系统 mDNS 解析器
 * （NsdManager 底层的 mdnsd）会把服务记录缓存到 TTL 到期。App 重启后可能
 * 直接命中缓存，拿到几小时前的旧端口 —— 表现为「扫到了却连不上」或
 * 「用旧端口连接被拒」。
 *
 * **约束**：Android 未提供清空系统 mDNS 缓存的公开 API。
 *
 * **对策**（组合拳，覆盖两条路径）：
 *  1. **停止发现**：`stopServiceDiscovery` 会令解析器释放该 discovery session
 *     关联的缓存条目。App 一退出就停掉全部发现，下次启动重新发起查询，
 *     而不是复用旧 session 的缓存结果。
 *  2. **端口证伪**：退出时对「当前配置端口 + 本轮扫描端口」做 TCP 探活，
 *     连不上的记入 [NsdDiscovery] 的 stale 黑名单；下次启动时这些端口
 *     会被降级处理（不优先采用），即便系统缓存仍在也不会踩坑。
 *
 * 注册时机：在 [com.loglab.app.App.onCreate] 中调用 [attach]。
 * 触发时机：`ProcessLifecycleOwner` 的 `ON_STOP`（整个 App 进入后台/被划掉）。
 */
@Singleton
class NsdCacheCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nsd: NsdDiscovery,
    private val settings: SettingsRepository,
    private val logger: AppLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var attached = false

    /** 最近一轮扫描到的端口（由调用方在扫描后上报，供退出时一并探活） */
    @Volatile
    private var lastScannedPorts: Set<Int> = emptySet()

    fun reportScannedPorts(ports: Collection<Int>) {
        lastScannedPorts = ports.filter { it > 0 }.toSet()
    }

    fun attach() {
        if (attached) return
        attached = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // App 退到后台/被划掉：清理 mDNS 缓存关联
                scope.launch { runCatching { cleanup() } }
            }
        })
    }

    /**
     * 执行退出清理。可由 ON_STOP 自动触发，也可手动调用（如设置页的"清理"按钮）。
     */
    suspend fun cleanup() {
        val current = runCatching { settings.current() }.getOrNull()
        val ports = buildSet {
            current?.adbPort?.takeIf { it > 0 }?.let { add(it) }
            addAll(lastScannedPorts)
        }
        if (ports.isEmpty()) {
            nsd.stopAll()
            return
        }

        logger.log("NSD", "退出清理：探活端口 ${ports.sorted()}，并停止全部 mDNS 发现")
        nsd.cleanupOnExit(ports)
        val stale = ports.filter { nsd.isStale(it) }
        if (stale.isNotEmpty()) {
            logger.log("NSD", "已证伪端口（下次启动不优先采用）：${stale.sorted()}")
        } else {
            logger.log("NSD", "全部端口仍可连通，未标记失效")
        }
    }
}
