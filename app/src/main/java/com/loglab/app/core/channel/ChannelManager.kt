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

    suspend fun disconnect() {
        _activeChannel.value?.close()
        _activeChannel.value = null
        _state.value = ChannelState(null, false)
    }
}
