package com.loglab.app.core.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

/**
 * ADB 后端抽象：同一份「通道」语义，两种实现。
 *
 * - [BuiltinAdbBackend]：自研内嵌 ADB 协议（CNXN/AUTH/OPEN/WRTE），用 Android Keystore 中的 RSA 密钥签名；
 * - [KadbAdbBackend]：Kadb 库连接，用于「已通过 Kadb 完成无线调试配对」的场景。
 */
interface AdbBackend {
    suspend fun probe(): Boolean
    suspend fun execute(command: String): String
    fun stream(command: String): Flow<String>
    suspend fun label(): String
}

/**
 * 自研内嵌 ADB 协议后端（对应设计文档 core/adb 模块）。
 */
class BuiltinAdbBackend(
    private val keyStore: AdbKeyStore,
    private val hostProvider: suspend () -> String,
    private val portProvider: suspend () -> Int
) : AdbBackend {

    override suspend fun probe(): Boolean = runCatching {
        val connection = AdbConnection(hostProvider(), portProvider(), keyStore)
        connection.connect()
        connection.close()
        true
    }.getOrDefault(false)

    override suspend fun execute(command: String): String {
        val connection = AdbConnection(hostProvider(), portProvider(), keyStore)
        return try {
            connection.connect()
            val stream = connection.open("shell:$command")
            val output = StringBuilder()
            while (true) {
                val chunk = stream.readChunk() ?: break
                output.append(String(chunk, Charsets.UTF_8))
            }
            output.toString()
        } finally {
            connection.close()
        }
    }

    override fun stream(command: String): Flow<String> = flow {
        val connection = AdbConnection(hostProvider(), portProvider(), keyStore)
        try {
            connection.connect()
            val stream = connection.open("shell:$command")
            val lineBuffer = ByteLineBuffer()
            while (currentCoroutineContext().isActive) {
                val chunk = stream.readChunk() ?: break
                for (line in lineBuffer.append(chunk)) {
                    if (line.isNotEmpty()) emit(line)
                }
            }
            lineBuffer.drainRemaining()?.let { if (it.isNotEmpty()) emit(it) }
        } finally {
            runCatching { connection.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun label(): String = "${hostProvider()}:${portProvider()}"
}

/**
 * Kadb 后端：配对完成后，用 Kadb 自己的密钥连接设备（与配对时使用的密钥一致）。
 *
 * 关键：Kadb 的密钥只存在内存静态字段，进程重启即丢——每次连接前先从存档恢复，
 * 否则 Kadb 会生成新密钥，被 adbd 以 SSLV3_ALERT_CERTIFICATE_UNKNOWN 拒绝。
 */
class KadbAdbBackend(
    private val certPersistence: com.loglab.app.core.adb.KadbCertPersistence,
    private val hostProvider: suspend () -> String,
    private val portProvider: suspend () -> Int,
    private val logger: com.loglab.app.core.report.AppLogger
) : AdbBackend {

    /** 最近一次 probe/connect 的真实错误（用于把被吞掉的异常暴露给上层 UI） */
    var lastError: String? = null
        private set

    private suspend fun connect(socketTimeoutMs: Int = 5000): com.flyfishxu.kadb.Kadb {
        // 进程重启后 KadbCert 静态字段为空，先从 DataStore 存档恢复配对时使用的密钥
        certPersistence.restoreIfNeeded()
        val host = hostProvider()
        val port = portProvider()
        // socketTimeout 默认取 5s：短命令（pidof/ps/logcat -d）快速失败；
        // 持续流场景由 stream() 传入超大超时，避免日志间隔稍长即被切断
        logger.log("ADB", "连接 $host:$port（connectTimeout=4000, socketTimeout=$socketTimeoutMs）")
        return com.flyfishxu.kadb.Kadb.create(host, port, 4000, socketTimeoutMs)
    }

    private fun describe(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return if (msg.contains("CERTIFICATE_UNKNOWN")) {
            "设备不再信任本 App 的密钥（多为无线调试重启/授权重置所致），请在设置页重新配对一次"
        } else {
            msg
        }
    }

    override suspend fun probe(): Boolean {
        val host = runCatching { hostProvider() }.getOrDefault("?")
        val port = runCatching { portProvider() }.getOrDefault(-1)
        lastError = null
        return runCatching {
            val kadb = connect()
            try {
                kadb.connectionCheck()
                logger.log("ADB", "probe 成功 $host:$port")
                true
            } finally {
                runCatching { kadb.close() }
            }
        }.onFailure { t ->
            // 关键：把真实异常记录下来，而不是被 getOrDefault(false) 吞掉，
            // 否则上层永远只能看到误导性的"ADB 未配对"。
            lastError = "Kadb 连接 $host:$port 失败：${describe(t)}"
            logger.log("ADB", "probe 失败 $host:$port：${describe(t)}", t)
        }.getOrDefault(false)
    }

    override suspend fun execute(command: String): String {
        // 每次操作独立建立 TLS 连接；握手偶发抖动会让整条解析链断掉，这里重试一次
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val kadb = connect()
                try {
                    val out = kadb.shell(command).allOutput
                    lastError = null
                    return out
                } finally {
                    runCatching { kadb.close() }
                }
            } catch (t: Throwable) {
                last = t
                lastError = "Kadb shell 失败(第${attempt + 1}次)：${describe(t)}"
                if (attempt == 0) kotlinx.coroutines.delay(300)
            }
        }
        throw last ?: IllegalStateException("Kadb shell 失败")
    }

    override fun stream(command: String): Flow<String> = flow {
        // 持续流（logcat 不带 -d 持续输出）必须用"无限"socket 超时的独立连接：
        // 若沿用 execute 的 5s 超时，目标应用日志间隔一旦超过 5s，底层 read 就会
        // 抛 SocketTimeoutException，此前被静默 break——表现为"只有已连接一行，
        // 再无任何日志且无中断提示"。
        val kadb = connect(socketTimeoutMs = Int.MAX_VALUE)
        try {
            val shell = kadb.openShell(command)
            logger.log("TAIL", "开始流式读取：$command")
            val lineBuffer = ByteLineBuffer()
            while (currentCoroutineContext().isActive) {
                val packet = try {
                    // runInterruptible：用户停止跟踪取消协程时中断阻塞读，
                    // 避免线程卡死在 NIO read 上
                    runInterruptible { shell.read() }
                } catch (_: java.io.EOFException) {
                    // adbd 关闭流：logcat 进程退出（目标应用被杀时常见），正常结束
                    break
                } catch (e: java.io.IOException) {
                    // 真实断流（网络抖动/设备断开/TLS 异常）：不再静默吞掉，
                    // 抛出让上层显示"跟踪中断: <原因>"
                    val reason = "ADB 日志流中断：${describe(e)}"
                    lastError = reason
                    logger.log("TAIL", "流中断：$reason", e)
                    throw java.io.IOException(reason, e)
                }
                when (packet) {
                    is com.flyfishxu.kadb.shell.AdbShellPacket.StdOut -> {
                        for (line in lineBuffer.append(packet.payload)) {
                            if (line.isNotEmpty()) emit(line)
                        }
                    }
                    is com.flyfishxu.kadb.shell.AdbShellPacket.StdError -> {
                        // logcat 命令本身的报错走 stderr（如 --pid 不被支持），
                        // 必须显示而不是干等
                        val text = String(packet.payload, Charsets.UTF_8).trim()
                        if (text.isNotEmpty()) {
                            lastError = text
                            emit(text)
                        }
                    }
                    is com.flyfishxu.kadb.shell.AdbShellPacket.Exit -> break
                    else -> {}
                }
            }
            lineBuffer.drainRemaining()?.let { if (it.isNotEmpty()) emit(it) }
        } finally {
            runCatching { kadb.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun label(): String = "${hostProvider()}:${portProvider()}"
}
