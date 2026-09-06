package com.loglab.app.core.adb

import java.io.ByteArrayOutputStream
import java.io.IOException

/** 设备未授权此客户端（无线调试未配对 / 未确认 RSA 指纹） */
class AdbUnauthorizedException(message: String) : IOException(message)

/** 设备离线或端口未开放 */
class AdbOfflineException(message: String) : IOException(message)

/**
 * ADB 连接：完成 CNXN 握手 / AUTH 签名，并打开 shell 流。
 *
 * 只实现了 logcat 抓取所需的最小子集：
 *   CNXN / AUTH(SIGNATURE) / OPEN / WRTE / OKAY / CLSE
 * 不声明 shell_v2 feature，因此 adbd 使用 shell v1（原始字节流）。
 */
class AdbConnection(
    private val host: String,
    private val port: Int,
    private val keyStore: AdbKeyStore
) {

    private val transport = AdbTransport(host, port)

    @Volatile
    private var nextLocalId = 1

    var deviceBanner: String = ""
        private set

    fun connect() {
        transport.connect(4000)
        // 声明最小 feature 集合：不带 shell_v2，adbd 会回退到 shell v1 原始流
        val banner = "host::features=cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb"
        transport.send(
            AdbMessage.cnxn(banner.toByteArray(Charsets.UTF_8))
        )

        var message = transport.readMessage()
            ?: throw IOException("连接被设备关闭（端口 $port）")

        if (message.command == AdbProtocol.A_AUTH) {
            when (message.arg0) {
                AdbProtocol.AUTH_TOKEN -> {
                    val signature = keyStore.signToken(message.payload)
                    transport.send(AdbMessage.auth(AdbProtocol.AUTH_SIGNATURE, signature))
                    message = transport.readMessage()
                        ?: throw IOException("签名后连接被关闭")
                }
                else -> throw IOException("不支持的认证类型: ${message.arg0}")
            }
        }

        if (message.command != AdbProtocol.A_CNXN) {
            throw IOException("握手失败，收到 ${AdbProtocol.commandName(message.command)}")
        }
        deviceBanner = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
        // 握手已成功，后续进入 shell 流读取，恢复无限超时（对端会持续 push WRTE）
        transport.setSoTimeout(0)
    }

    /** 打开一个服务流（如 shell:logcat -d） */
    fun open(service: String): AdbStream {
        val localId = nextLocalId++
        transport.send(AdbMessage.open(localId, service))

        while (true) {
            val message = transport.readMessage()
                ?: throw IOException("打开流时连接被关闭")
            when (message.command) {
                AdbProtocol.A_OKAY -> {
                    if (message.arg1 == localId) {
                        return AdbStream(transport, localId, message.arg0)
                    }
                    // 其他流的 OKAY，忽略
                }
                AdbProtocol.A_CLSE -> {
                    if (message.arg1 == localId) {
                        val reason = String(message.payload, Charsets.UTF_8)
                        throw classifyFailure(reason)
                    }
                }
                else -> Unit // WRTE 等忽略
            }
        }
    }

    fun close() = transport.close()

    private fun classifyFailure(reason: String): IOException {
        val lower = reason.lowercase()
        return when {
            lower.contains("unauthorized") -> AdbUnauthorizedException("设备未授权：请先在无线调试中完成配对")
            lower.contains("offline") || lower.contains("closed") -> AdbOfflineException("设备离线：$reason")
            lower.contains("not found") || lower.contains("no such") -> IOException("服务不可用：$reason")
            else -> IOException("设备拒绝打开服务：$reason")
        }
    }

    /**
     * 单个 ADB 流。当前实现为串行单流模型（一条连接同时只跑一个流），
     * 每次抓取/跟踪都新建连接，天然线程安全。
     */
    class AdbStream(
        private val transport: AdbTransport,
        val localId: Int,
        val remoteId: Int
    ) {
        fun write(data: ByteArray) {
            transport.send(AdbMessage.write(localId, remoteId, data))
        }

        /** 读取一块数据；返回 null 表示流已关闭 */
        fun readChunk(): ByteArray? {
            while (true) {
                val message = transport.readMessage() ?: return null
                when (message.command) {
                    AdbProtocol.A_WRTE -> {
                        if (message.arg1 != localId) continue
                        transport.send(AdbMessage.okay(localId, remoteId))
                        return message.payload
                    }
                    AdbProtocol.A_OKAY -> continue
                    AdbProtocol.A_CLSE -> {
                        if (message.arg1 == localId) {
                            transport.send(AdbMessage.close(localId, remoteId))
                            return null
                        }
                    }
                    else -> continue
                }
            }
        }

        fun closeSilently() {
            runCatching { transport.send(AdbMessage.close(localId, remoteId)) }
        }
    }
}

/**
 * 把 ADB 字节流切成完整行（按 \n 切分，避免多字节 UTF-8 被截断）。
 */
class ByteLineBuffer {
    private val buffer = ByteArrayOutputStream()

    fun append(chunk: ByteArray): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (i in chunk.indices) {
            if (chunk[i] == 0x0A.toByte()) {
                buffer.write(chunk, start, i - start)
                lines.add(drain())
                start = i + 1
            }
        }
        if (start < chunk.size) buffer.write(chunk, start, chunk.size - start)
        return lines
    }

    fun drainRemaining(): String? {
        if (buffer.size() == 0) return null
        return drain().takeIf { it.isNotBlank() }
    }

    private fun drain(): String {
        val bytes = buffer.toByteArray()
        buffer.reset()
        return String(bytes, Charsets.UTF_8).trimEnd('\r', '\n')
    }
}
