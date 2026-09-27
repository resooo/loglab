package com.loglab.app.core.adb

import android.util.Base64
import com.flyfishxu.kadb.cert.KadbCert
import com.loglab.app.data.repository.SettingsRepository

/**
 * Kadb 配对密钥持久化。
 *
 * ## 背景：为什么需要这个类
 *
 * Kadb 把 TLS 客户端证书/私钥存在 [KadbCert] 的**内存静态字段**里，
 * App 进程一重启（切后台被杀、系统回收、重装）字段即清空；
 * 而设备 adbd 只信任「配对那一刻」的密钥。
 *
 * 进程重启后 Kadb 的 `loadKeyPair()` 发现字段为空，会生成一把**全新密钥**，
 * adbd 校验证书指纹不匹配，以 `SSLV3_ALERT_CERTIFICATE_UNKNOWN` 拒绝。
 * 表现为「配对成功 → 当时能用 → 过一会儿或重启后连接失败」。
 *
 * ## 解决
 *
 * 配对成功后立即把内存中的 cert/key 导出存入 DataStore（[save]）；
 * 每次建立连接前用存档**覆盖**内存态（[restoreIfNeeded]）。
 *
 * ## ⚠️ 关键教训：恢复必须无条件执行
 *
 * 曾用 `if (内存非空) return` 做条件，结果仍然失败。原因是**时序**：
 * 进程重启后字段确实为空，但在我们调用 `restoreIfNeeded()` 之前，
 * Kadb 自己可能已经跑过 `loadKeyPair()` 并写入了一把**新生成的随机密钥** ——
 * 此时字段非空，我们却跳过了恢复，拿着这把从未配对过的密钥去握手，
 * 于是继续报 `CERTIFICATE_UNKNOWN`。
 *
 * 判断依据因此改为「**存档是否存在**」（存档是配对那一刻的真密钥，
 * 唯一被 adbd 信任的一份），而不是「内存是否为空」。
 */
class KadbCertPersistence(private val settings: SettingsRepository) {

    /** 配对成功后调用：导出 KadbCert 内存中的 cert/key 并持久化 */
    suspend fun save() {
        val cert = readStaticField("cert")
        val key = readStaticField("key")
        if (cert == null || key == null) {
            // 反射读不到字段 —— 通常意味着 Kadb 版本变了（字段改名/改类型）。
            // 这种情况必须显式报错，否则表现为"配对成功但重启后连不上"，
            // 排查起来毫无线索（存档是空的，看起来像从未配对过）。
            error(
                "无法从 KadbCert 读取密钥（cert=${if (cert == null) "字段缺失" else "${cert.size}B"}，" +
                    "key=${if (key == null) "字段缺失" else "${key.size}B"}）——" +
                    "Kadb 版本可能已变化，请检查 KadbCertPersistence 的反射字段名"
            )
        }
        if (cert.isEmpty() || key.isEmpty()) {
            error("KadbCert 中的密钥为空（cert=${cert.size}B, key=${key.size}B），配对未真正完成")
        }
        settings.update {
            it.copy(
                adbCertB64 = Base64.encodeToString(cert, Base64.NO_WRAP),
                adbKeyB64 = Base64.encodeToString(key, Base64.NO_WRAP),
            )
        }
    }

    /**
     * 建立连接前调用：用存档中的配对密钥覆盖 Kadb 内存态。
     *
     * ## 为什么必须无条件覆盖，而不是"内存为空才恢复"
     *
     * 原实现是 `if (memoryHasCert()) return` —— 只要 KadbCert 的静态字段非空
     * 就跳过恢复。这个条件**不够强**：
     *
     * 进程重启后字段确实为空，但在我们调用本方法**之前**，Kadb 自己可能已经
     * 执行过 `loadKeyPair()`，它发现字段为空会**生成一把全新的随机密钥**并写回
     * 静态字段。于是 `memoryHasCert()` 返回 true → 我们跳过恢复 → 拿着这把
     * 从未与设备配对过的新密钥去握手 → adbd 校验证书指纹不匹配 →
     * 以 `SSLv3_ALERT_CERTIFICATE_UNKNOWN` 拒绝（真机实测就是这个报错）。
     *
     * 而存档里的密钥是**配对成功那一刻**导出的，是唯一被 adbd 信任的一份。
     * 因此判断依据应该是"存档是否存在"，而不是"内存是否为空"。
     *
     * ## 幂等性
     *
     * 重复调用只是把同样的字节再 set 一次，`KadbCert.set()` 内部会校验
     * 证书有效期，无副作用。
     */
    suspend fun restoreIfNeeded() {
        val cur = settings.current()
        if (cur.adbCertB64.isBlank() || cur.adbKeyB64.isBlank()) {
            // 没有配对存档：可能是旧版本升级上来的用户，或从未配对过。
            // 此时保持 Kadb 自身行为即可（未配对场景本来就连不上）。
            return
        }
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

    /** Kadb 未暴露读取接口，反射读静态字段（app 自身依赖内的类，无 hidden API 限制） */
    private fun readStaticField(name: String): ByteArray? = runCatching {
        val field = KadbCert::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(null) as? ByteArray
    }.getOrNull()
}
