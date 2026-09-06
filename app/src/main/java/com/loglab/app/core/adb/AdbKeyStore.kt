package com.loglab.app.core.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * ADB RSA 密钥管理。
 *
 * 私钥保存在 Android Keystore 中（不可导出），仅用于签名 adbd 下发的 token；
 * 公钥可导出为 ADB 公钥格式（adbkey.pub），用于：
 *  1. 展示给用户；
 *  2. 在拥有 shell/root 权限时写入 /data/misc/adb/adb_keys 完成自授权。
 */
class AdbKeyStore(context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "logcat_grabber_adb_rsa_v1"
        private const val KEY_SIZE = 2048
        private const val RSA_WORDS = KEY_SIZE / 32 // 64 个 uint32
        private val MASK32 = BigInteger.valueOf(0xFFFFFFFFL)
        private const val COMMENT = " logcat-grabber@android"
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var cached: KeyPair? = null

    /** 获取（或首次生成）ADB RSA 密钥对 */
    fun keyPair(): KeyPair {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(KEY_SIZE)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
                    .apply { initialize(spec) }
                    .generateKeyPair()
            }
            val refreshed = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val public = refreshed.getCertificate(ALIAS).publicKey
            val private = refreshed.getKey(ALIAS, null) as java.security.PrivateKey
            return KeyPair(public, private).also { cached = it }
        }
    }

    /** 对 adbd 下发的 20 字节 token 做 SHA1withRSA 签名 */
    fun signToken(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA").apply {
            initSign(keyPair().private)
            update(token)
        }
        return signature.sign()
    }

    /** 导出 ADB 格式的公钥（base64 结构 + 注释），等价于 adbkey.pub 的内容 */
    fun exportPublicKeyText(): String =
        Base64.encodeToString(encodeRsaPublicKeyBlob(), Base64.NO_WRAP) + COMMENT

    /** 公钥指纹（前 16 位十六进制），便于在设置页比对 */
    fun fingerprint(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(exportPublicKeyText().substringBefore(" ").toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 序列化为 adb 的 RSAPublicKey 结构（全部小端 uint32）：
     *   int32 len(=64) | uint32 n0inv | uint32 n[64] | uint32 rr[64] | int32 exponent
     */
    private fun encodeRsaPublicKeyBlob(): ByteArray {
        val publicKey = keyPair().public as RSAPublicKey
        val modulus: BigInteger = publicKey.modulus
        val exponent: Int = publicKey.publicExponent.toInt()

        val blob = ByteArray(4 + 4 + 4 * RSA_WORDS + 4 * RSA_WORDS + 4)
        writeLe32(blob, 0, RSA_WORDS)

        // n0inv = -(n^-1) mod 2^32，牛顿迭代求逆
        val n0 = modulus.and(MASK32).toLong()
        var inv = 1L
        repeat(6) { inv = (inv * (2 - n0 * inv)) and 0xFFFFFFFFL }
        writeLe32(blob, 4, ((-inv) and 0xFFFFFFFFL).toInt())

        var offset = 8
        for (i in 0 until RSA_WORDS) {
            writeLe32(blob, offset, modulus.shiftRight(32 * i).and(MASK32).toInt())
            offset += 4
        }
        // rr = (2^2048)^2 mod n
        val rr = BigInteger.ONE.shiftLeft(2 * KEY_SIZE).mod(modulus)
        for (i in 0 until RSA_WORDS) {
            writeLe32(blob, offset, rr.shiftRight(32 * i).and(MASK32).toInt())
            offset += 4
        }
        writeLe32(blob, offset, exponent)
        return blob
    }
}
