package com.loglab.app.data.model

import com.loglab.app.core.channel.ChannelPolicy
import com.loglab.app.core.logcat.LogBuffer
import kotlinx.serialization.Serializable

/** 应用设置（整体 JSON 序列化后存 DataStore） */
@Serializable
data class AppSettings(
    val adbHost: String = "127.0.0.1",
    val adbPort: Int = 5555,
    /** 是否已完成无线调试配对（决定 ADB 后端优先顺序） */
    val adbPaired: Boolean = false,
    /** Kadb 配对密钥存档（Base64 PEM）：Kadb 只把密钥放内存，进程重启即丢，设备只认配对时那把 */
    val adbCertB64: String = "",
    val adbKeyB64: String = "",
    val bridgeUrl: String = "http://127.0.0.1:7980",
    val channelPolicy: ChannelPolicy = ChannelPolicy.AUTO,
    /** 默认浅色主题（可在设置页手动开启深色） */
    val darkTheme: Boolean = false,
    val dynamicColor: Boolean = true,
    val fontSize: Int = 13,
    val monoFont: Boolean = true,
    val maxLines: Int = 500,
    val defaultBuffer: LogBuffer = LogBuffer.MAIN,
    val keepScreenOn: Boolean = false,
    val recentPackages: List<String> = emptyList(),
    val onboarded: Boolean = false
)
