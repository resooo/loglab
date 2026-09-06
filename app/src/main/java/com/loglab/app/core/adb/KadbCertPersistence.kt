package com.loglab.app.core.adb

import android.util.Base64
import com.flyfishxu.kadb.cert.KadbCert
import com.loglab.app.data.repository.SettingsRepository

/**
 * Kadb 配对密钥持久化。
 *
 * 背景：Kadb 把 TLS 客户端证书/私钥存在 [KadbCert] 的**内存静态字段**里，
 * App 进程一重启（切后台被杀、重装、系统回收）字段即清空；而设备 adbd 只信任
 * 「配对那一刻」的密钥。进程重启后 Kadb 的 loadKeyPair() 会生成一把全新密钥，
 * 被 adbd 以 SSLV3_ALERT_CERTIFICATE_UNKNOWN 拒绝——表现为
 * 「配对成功 → 当时能用 → 过一会儿/重启后连接失败」。
 *
 * 解决：配对成功后立即把内存中的 cert/key 导出存入 DataStore；
 * 每次建立连接前若内存为空则恢复（set() 是 Kadb 的公开 API）。
 */
class KadbCertPersistence(private val settings: SettingsRepository) {

    /** 配对成功后调用：导出 KadbCert 内存中的 cert/key 并持久化 */
    suspend fun save() {
        val cert = readStaticField("cert") ?: return
        val key = readStaticField("key") ?: return
        if (cert.isEmpty() || key.isEmpty()) return
        val certB64 = Base64.encodeToString(cert, Base64.NO_WRAP)
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        settings.update { it.copy(adbCertB64 = certB64, adbKeyB64 = keyB64) }
    }

    /** 建立连接前调用：内存字段为空且有存档时恢复（幂等，不覆盖非空内存态） */
    suspend fun restoreIfNeeded() {
        if (memoryHasCert()) return
        val cur = settings.current()
        if (cur.adbCertB64.isBlank() || cur.adbKeyB64.isBlank()) return
        runCatching {
            val cert = Base64.decode(cur.adbCertB64, Base64.NO_WRAP)
            val key = Base64.decode(cur.adbKeyB64, Base64.NO_WRAP)
            // Kadb 的公开 API；内部会校验证书有效期
            KadbCert.set(cert, key)
        }
    }

    /** 清除存档（例如用户主动重置配对） */
    suspend fun clear() {
        settings.update { it.copy(adbCertB64 = "", adbKeyB64 = "") }
    }

    /** cert/key 两个字段都非空才视为内存中已有密钥（与 Kadb loadKeyPair 的判断一致） */
    private fun memoryHasCert(): Boolean {
        val cert = readStaticField("cert")
        val key = readStaticField("key")
        return cert != null && cert.isNotEmpty() && key != null && key.isNotEmpty()
    }

    /** Kadb 未暴露读取接口，反射读静态字段（app 自身依赖内的类，无 hidden API 限制） */
    private fun readStaticField(name: String): ByteArray? = runCatching {
        val field = KadbCert::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(null) as? ByteArray
    }.getOrNull()
}
