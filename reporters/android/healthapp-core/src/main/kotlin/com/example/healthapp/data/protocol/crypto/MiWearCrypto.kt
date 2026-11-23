package com.example.healthapp.data.protocol.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 小米 Vela 穿戴协议的密码学原语。
 *
 * 纯 JVM 实现，不依赖 Android —— 可以直接在单元测试里跑。
 */
object MiWearCrypto {

    /** CCM 的 tag 长度：小米用 4 字节（JCE 默认 16 字节，所以必须走 BouncyCastle） */
    private const val CCM_TAG_BITS = 32

    private const val KDF_TAG = "miwear-auth"

    // ---------------------------------------------------------------- AES-CTR

    /**
     * AES-128-CTR。
     *
     * **关键易错点**：小米协议里 IV 直接复用 key 本身（16 字节），
     * 既不是随机数也不是从 nonce 派生的。见 docs/DESIGN.md §3.4。
     *
     * CTR 是对称流密码，加解密用同一个函数。
     */
    fun aesCtr(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(key), // ← IV == key
        )
        return cipher.doFinal(data)
    }

    // ------------------------------------------------------------------- HMAC

    /** HMAC-SHA256，支持多段拼接输入（等价于把各段先连接再算） */
    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    // -------------------------------------------------------------- AES-CCM

    /**
     * AES-128-CCM 加密。
     *
     * @param nonce 12 字节
     * @param aad   附加认证数据，可为空
     * @return 密文 ‖ 4 字节 tag
     */
    fun aesCcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        require(key.size == 16) { "key must be 16 bytes, got ${key.size}" }
        require(nonce.size == 12) { "nonce must be 12 bytes, got ${nonce.size}" }

        val engine = CCMBlockCipher.newInstance(AESEngine.newInstance())
        engine.init(true, AEADParameters(KeyParameter(key), CCM_TAG_BITS, nonce, aad))

        val out = ByteArray(engine.getOutputSize(plain.size))
        var pos = engine.processBytes(plain, 0, plain.size, out, 0)
        pos += engine.doFinal(out, pos)
        return out.copyOf(pos)
    }

    /**
     * AES-128-CCM 解密。
     *
     * @param cipherAndTag 必须是 密文 ‖ 4 字节 tag
     * @throws org.bouncycastle.crypto.InvalidCipherTextException tag 校验失败
     */
    fun aesCcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        cipherAndTag: ByteArray,
    ): ByteArray {
        require(key.size == 16) { "key must be 16 bytes, got ${key.size}" }
        require(nonce.size == 12) { "nonce must be 12 bytes, got ${nonce.size}" }

        val engine = CCMBlockCipher.newInstance(AESEngine.newInstance())
        engine.init(false, AEADParameters(KeyParameter(key), CCM_TAG_BITS, nonce, aad))

        val out = ByteArray(engine.getOutputSize(cipherAndTag.size))
        var pos = engine.processBytes(cipherAndTag, 0, cipherAndTag.size, out, 0)
        pos += engine.doFinal(out, pos)
        return out.copyOf(pos)
    }

    // -------------------------------------------------------------------- KDF

    /**
     * 小米穿戴认证密钥派生（kdf_miwear）。返回 64 字节：
     * ```
     * [0..16)   decKey
     * [16..32)  encKey
     * [32..36)  decNonce
     * [36..40)  encNonce
     * ```
     *
     * 算法见 docs/DESIGN.md §3.6。两个致命易错点：
     * 1) initKey 是 HMAC 的 **key**，authKey 是**消息**（不是反过来）
     * 2) 扩展循环里 prev 初始是**长度 0 的空数组**，不是 32 个 0
     */
    fun kdfMiwear(authKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
        require(authKey.size == 16) { "authKey must be 16 bytes, got ${authKey.size}" }
        require(phoneNonce.size == 16) { "phoneNonce must be 16 bytes, got ${phoneNonce.size}" }
        require(watchNonce.size == 16) { "watchNonce must be 16 bytes, got ${watchNonce.size}" }

        val initKey = phoneNonce + watchNonce        // 32B
        val hmacKey = hmacSha256(initKey, authKey)   // 32B
        val tag = KDF_TAG.toByteArray(Charsets.US_ASCII)

        val okm = ByteArray(64)
        var prev = ByteArray(0)                       // ← 空数组，不是 32 个 0
        var offset = 0
        for (counter in 1..3) {
            prev = hmacSha256(hmacKey, prev, tag, byteArrayOf(counter.toByte()))
            val n = minOf(32, 64 - offset)
            System.arraycopy(prev, 0, okm, offset, n)
            offset += n
        }
        return okm
    }

    // ------------------------------------------------------------------ 工具

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "hex length must be even, got ${clean.length}" }
        return ByteArray(clean.length / 2) {
            val hi = Character.digit(clean[it * 2], 16)
            val lo = Character.digit(clean[it * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex char at index ${it * 2}" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
