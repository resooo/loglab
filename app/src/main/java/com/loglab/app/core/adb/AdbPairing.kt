package com.loglab.app.core.adb

import com.loglab.app.data.repository.SettingsRepository
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADB 无线调试配对（Android 11+）。
 *
 * 配对协议是 SPAKE2(Curve25519) + TLS + AES-128-GCM：先用配对码完成 PAKE 握手，
 * 再把本客户端的 RSA 公钥交给设备，设备写入已信任列表。
 * 这里复用 Kadb 中经过验证的实现，避免自造密码学轮子。
 *
 * 配对成功后，本 App 的密钥即被 adbd 信任，后续可用内嵌 ADB 协议直连 127.0.0.1:<无线调试端口>。
 */
@Singleton
class AdbPairing @Inject constructor(
    private val settings: SettingsRepository,
    private val certPersistence: KadbCertPersistence
) {

    /** 与设备完成配对。port 为「无线调试 - 使用配对码配对设备」显示的端口，code 为 6 位配对码 */
    suspend fun pair(host: String, port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(code.length >= 6) { "配对码应为 6 位数字" }
            Kadb.pair(host, port, code, "logcat-grabber")
            // 关键：Kadb 的密钥只在内存，必须在配对成功当下导出存档，
            // 否则进程重启后密钥丢失，设备会拒绝新密钥（CERTIFICATE_UNKNOWN）
            certPersistence.save()
            settings.setAdbPaired(true)
        }
    }

    suspend fun isPaired(): Boolean = settings.current().adbPaired

    suspend fun reset() = settings.setAdbPaired(false)
}
