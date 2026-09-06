package com.loglab.app.core.channel

import com.loglab.app.core.adb.AdbBackend
import com.loglab.app.core.adb.BuiltinAdbBackend
import com.loglab.app.core.adb.KadbAdbBackend
import com.loglab.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADB 直连通道：优先自研内嵌协议，配对场景下自动切到 Kadb 后端。
 *
 * 每次操作独立建立连接（本地 TCP + RSA 签名），避免并发复用带来的状态问题。
 */
@Singleton
class AdbChannel @Inject constructor(
    private val builtin: BuiltinAdbBackend,
    private val kadb: KadbAdbBackend,
    private val settings: SettingsRepository
) : Channel {

    override val type: ChannelType = ChannelType.ADB

    /** 最近一次 probe 的真实错误（由 KadbAdbBackend 透传），供 UI 显示具体失败原因 */
    var lastError: String? = null
        private set

    private suspend fun orderedBackends(): List<AdbBackend> =
        // 无线调试（开发者选项的 Wireless debugging）是 ADB over TLS，自研明文协议连不上，
        // 因此配对成功（已用 Kadb 的密钥完成 TLS 配对）后只走 Kadb 后端，绝不回退到明文 builtin。
        // 未配对时（例如用户自行 `adb tcpip 5555` 开启了明文端口）才走 builtin。
        if (settings.current().adbPaired) listOf(kadb) else listOf(builtin)

    override suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        orderedBackends().any { backend ->
            val ok = runCatching { backend.probe() }.getOrDefault(false)
            if (!ok && backend is KadbAdbBackend) lastError = backend.lastError
            ok
        }
    }

    override suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var lastError: Throwable? = null
            for (backend in orderedBackends()) {
                try {
                    return@runCatching backend.execute(command)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw lastError ?: IllegalStateException("ADB 不可用")
        }
    }

    override fun executeStream(command: String): Flow<String> = flow {
        val backends = orderedBackends()
        val chosen = backends.firstOrNull { backend -> runCatching { backend.probe() }.getOrDefault(false) }
            ?: backends.first()
        emitAll(chosen.stream(command))
    }.flowOn(Dispatchers.IO)

    override suspend fun label(): String = withContext(Dispatchers.IO) {
        // 不再额外 probe：调用方（ChannelManager.autoConnect）刚探测过，
        // 这里再做一次会多一轮完整 TLS 握手（日志里表现为同一秒连两次）
        val chosen = orderedBackends().first()
        val suffix = if (chosen === builtin) "内嵌协议" else "Kadb"
        "${chosen.label()} · $suffix"
    }

    override fun close() = Unit
}
