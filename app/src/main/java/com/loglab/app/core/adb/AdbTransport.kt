package com.loglab.app.core.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADB TCP 传输层：负责 socket 连接与 ADB 消息的读写。
 * 所有方法都是阻塞式，调用方需放在 IO 线程。
 */
class AdbTransport(
    private val host: String,
    private val port: Int
) : Closeable {

    @Volatile
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(timeoutMs: Int = 4000) {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        // 握手阶段超时保护：连到 TLS 端口（如无线调试）或非 ADB 服务的端口时，
        // 对端不会回明文 CNXN，soTimeout=0 会让 readMessage 永久阻塞、UI 卡在"正在连接"。
        // 这里只给握手设短超时；AdbConnection 在握手完成后会把它设回 0 以支持长流读取。
        s.soTimeout = 8000
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
    }

    /** 调整后续读取超时（毫秒）。握手完成后由调用方设回 0 以支持长流读取 */
    fun setSoTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    fun send(message: AdbMessage) {
        val out = output ?: throw IOException("未连接")
        out.write(message.encode())
        out.flush()
    }

    /** 读取一条完整消息，连接关闭时返回 null */
    fun readMessage(): AdbMessage? {
        val input = this.input ?: throw IOException("未连接")
        val header = ByteArray(AdbProtocol.HEADER_SIZE)
        return try {
            input.readFully(header)
            val command = readLe32(header, 0)
            val magic = readLe32(header, 20)
            if (magic != (command xor -1)) throw IOException("ADB 消息 magic 校验失败")
            val arg0 = readLe32(header, 4)
            val arg1 = readLe32(header, 8)
            val length = readLe32(header, 12)
            val checksum = readLe32(header, 16)
            if (length < 0 || length > AdbProtocol.MAX_DATA) throw IOException("非法的负载长度: $length")
            val payload = ByteArray(length)
            input.readFully(payload)
            if (AdbProtocol.checksum(payload) != checksum) {
                throw IOException("ADB 消息 checksum 校验失败")
            }
            AdbMessage(command, arg0, arg1, payload)
        } catch (e: IOException) {
            throw e
        }
    }

    override fun close() {
        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        input = null
        output = null
        socket = null
    }
}
