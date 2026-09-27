package com.loglab.app.core.channel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通道管理器。
 *
 * App 专注「无 root 无线 ADB 抓包」，HostBridge 通道已移除：
 * autoConnect 只探测并激活 ADB 通道。[ChannelPolicy] 枚举保留仅为兼容旧存档反序列化。
 */
@Singleton
class ChannelManager @Inject constructor(
    val adbChannel: AdbChannel
) {
    private val _state = MutableStateFlow(ChannelState(null))
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val _activeChannel = MutableStateFlow<Channel?>(null)
    val activeChannel: StateFlow<Channel?> = _activeChannel.asStateFlow()

    suspend fun active(): Channel? = _activeChannel.value

    /** 探测并激活 ADB 通道（policy 参数已废弃，保留兼容旧调用） */
    suspend fun autoConnect(policy: ChannelPolicy = ChannelPolicy.ADB_ONLY): Result<ChannelType> = withContext(Dispatchers.IO) {
        _state.value = ChannelState(null, false, detail = "正在探测通道…")

        if (adbChannel.probe()) {
            val label = runCatching { adbChannel.label() }.getOrDefault("ADB")
            _activeChannel.value = adbChannel
            _state.value = ChannelState(ChannelType.ADB, true, label, "内嵌 ADB 协议 · 真流式")
            return@withContext Result.success(ChannelType.ADB)
        }

        _activeChannel.value = null
        // 把被内部吞掉的 Kadb 真实错误透传出来，避免误导用户为"未配对"
        val adbDetail = adbChannel.lastError
        val message = "ADB 不可用：${adbDetail ?: "请确认已开启无线调试并完成配对，端口正确"}"
        _state.value = ChannelState(null, false, detail = message)
        Result.failure(IllegalStateException(message))
    }

    /**
     * **跳过 probe 预检**，直接建立并激活通道。
     *
     * ## 为什么需要它（与 [autoConnect] 的区别）
     *
     * `autoConnect()` 以 `adbChannel.probe()` 为唯一闸门，而 Kadb 后端的
     * `probe()` 走的是库的 `connectionCheck()`。真机实测该调用对无线调试的
     * TLS 端口会返回 false，于是**真实可用的端口也一律被闸门拦下**，
     * 表现为「端口扫描显示可连接，却被逐个跳过」。
     *
     * 本方法不做预检，直接让后端执行一次真实操作（打开 shell 流读取一条输出），
     * 用真实结果说话 —— 这是太墟 wireless-adb 参考方案的思路：
     * **不设中间闸门，直接连**。
     *
     * 之所以用「执行一条命令」而不是"只建连"：Kadb 的 `create()` 是否真正
     * 完成 TLS 握手与认证，只有在发起第一个请求时才会暴露。
     *
     * @return 成功表示**真实建立了可执行命令的连接**；
     *         失败时 `lastError` 带具体原因（证书被拒 / 端口非 adbd 等）
     */
    suspend fun attach(policy: ChannelPolicy = ChannelPolicy.ADB_ONLY): Result<ChannelType> =
        withContext(Dispatchers.IO) {
            _state.value = ChannelState(null, false, detail = "正在建立连接…")

            // 直接发起一次真实请求：不做 probe 预检，让连接结果本身成为判据。
            val probeResult = runCatching { adbChannel.execute(PROBE_ECHO).getOrThrow() }
            val echoed = probeResult.getOrNull()?.trim()

            if (echoed == PROBE_EXPECT) {
                val label = runCatching { adbChannel.label() }.getOrDefault("ADB")
                _activeChannel.value = adbChannel
                _state.value = ChannelState(ChannelType.ADB, true, label, "内嵌 ADB 协议 · 真流式")
                return@withContext Result.success(ChannelType.ADB)
            }

            // 失败：把真实原因透传出去（不要含糊成"未配对"）
            val reason = probeResult.exceptionOrNull()?.message
                ?: adbChannel.lastError
                ?: "连接建立后命令执行失败"
            _activeChannel.value = null
            _state.value = ChannelState(null, false, detail = "ADB 不可用：$reason")
            Result.failure(IllegalStateException(reason))
        }

    suspend fun disconnect() {
        _activeChannel.value?.close()
        _activeChannel.value = null
        _state.value = ChannelState(null, false)
    }

    private companion object {
        /**
         * [attach] 用的探针命令。
         *
         * 选 `echo` 的理由：不依赖任何外部程序、输出确定、开销最小。
         * 不加引号以避免不同 shell 对引号的处理差异。
         */
        const val PROBE_ECHO = "echo probe-ok"
        const val PROBE_EXPECT = "probe-ok"
    }
}
