package com.loglab.app.core.adb

/**
 * ADB 协议常量与消息编解码。
 *
 * 参考：https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 *
 * 消息头 24 字节（小端）：
 *   command(4) | arg0(4) | arg1(4) | dataLength(4) | dataChecksum(4) | magic(4)
 *   magic = command xor 0xFFFFFFFF
 *   dataChecksum = payload 各字节按无符号求和（不是 CRC32）
 */
object AdbProtocol {
    const val A_SYNC = 0x434e5953   // "SYNC"
    const val A_CNXN = 0x4e584e43   // "CNXN"
    const val A_AUTH = 0x48545541   // "AUTH"
    const val A_OPEN = 0x4e45504f   // "OPEN"
    const val A_OKAY = 0x59414b4f   // "OKAY"
    const val A_CLSE = 0x45534c43   // "CLSE"
    const val A_WRTE = 0x45545257   // "WRTE"

    /** ADB 协议版本 */
    const val A_VERSION = 0x01000000

    /** 我们声明的单次最大负载（1MB），adbd 会取双方最小值 */
    const val MAX_DATA = 1024 * 1024

    /** AUTH 类型 */
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val HEADER_SIZE = 24

    fun commandName(command: Int): String = buildString {
        repeat(4) { i -> append((command ushr (8 * i)).toByte().toInt().toChar()) }
    }

    fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return sum
    }
}

/** 一条 ADB 消息 */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0)
) {
    override fun toString(): String =
        "AdbMessage(${AdbProtocol.commandName(command)}, arg0=$arg0, arg1=$arg1, len=${payload.size})"

    companion object {
        fun cnxn(payload: ByteArray) =
            AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA, payload)

        fun auth(type: Int, payload: ByteArray) =
            AdbMessage(AdbProtocol.A_AUTH, type, 0, payload)

        fun open(localId: Int, service: String) =
            AdbMessage(AdbProtocol.A_OPEN, localId, 0, service.toByteArray(Charsets.UTF_8))

        fun okay(localId: Int, remoteId: Int) =
            AdbMessage(AdbProtocol.A_OKAY, localId, remoteId)

        fun write(localId: Int, remoteId: Int, data: ByteArray) =
            AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, data)

        fun close(localId: Int, remoteId: Int) =
            AdbMessage(AdbProtocol.A_CLSE, localId, remoteId)
    }
}

/** 小端写入 32 位整数 */
fun writeLe32(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

fun readLe32(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

/** 消息 → 24字节头 + payload */
fun AdbMessage.encode(): ByteArray {
    val buf = ByteArray(AdbProtocol.HEADER_SIZE + payload.size)
    writeLe32(buf, 0, command)
    writeLe32(buf, 4, arg0)
    writeLe32(buf, 8, arg1)
    writeLe32(buf, 12, payload.size)
    writeLe32(buf, 16, AdbProtocol.checksum(payload))
    writeLe32(buf, 20, command xor -1)
    System.arraycopy(payload, 0, buf, AdbProtocol.HEADER_SIZE, payload.size)
    return buf
}
