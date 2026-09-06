package com.loglab.app.core.channel

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

enum class ChannelType { ADB, BRIDGE }

/** 通道强制策略 */
@Serializable
enum class ChannelPolicy { AUTO, ADB_ONLY, BRIDGE_ONLY }

data class ChannelState(
    val type: ChannelType?,
    val connected: Boolean = false,
    val deviceLabel: String = "",
    val detail: String = ""
)

/**
 * 日志抓取通道抽象（对应插件里的 ADB 直连 / HostBridge 双通道）。
 */
interface Channel {
    val type: ChannelType

    /** 探测通道是否可用（轻量，不抛异常） */
    suspend fun probe(): Boolean

    /** 执行 shell 命令并返回完整输出 */
    suspend fun execute(command: String): Result<String>

    /** 执行 shell 命令并逐行流式返回（ADB 为真流式，HostBridge 为轮询） */
    fun executeStream(command: String): Flow<String>

    /** 通道可读名称，用于状态栏展示 */
    suspend fun label(): String

    fun close()
}
