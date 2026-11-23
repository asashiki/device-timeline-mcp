package com.example.healthapp.data.protocol.auth

import com.example.healthapp.data.protocol.MiWearLog
import com.example.healthapp.data.protocol.WearPacketChannel
import com.example.healthapp.data.protocol.crypto.MiWearCrypto
import com.example.healthapp.data.protocol.proto.Account
import com.example.healthapp.data.protocol.proto.AccountError
import com.example.healthapp.data.protocol.proto.AuthAppConfirm
import com.example.healthapp.data.protocol.proto.AuthAppVerify
import com.example.healthapp.data.protocol.proto.AuthDeviceConfirm
import com.example.healthapp.data.protocol.proto.AuthDeviceVerify
import com.example.healthapp.data.protocol.proto.CompanionDevice
import com.example.healthapp.data.protocol.proto.WearPacket
import java.security.SecureRandom

/**
 * 小米穿戴的四步认证握手。
 *
 * 流程（详见 docs/DESIGN.md §3.5）：
 * ```
 * App  → Band  AUTH_VERIFY(26)  AppVerify{app_random}
 * Band → App   AUTH_VERIFY(26)  DeviceVerify{device_random, device_sign}
 *              [本地校验 HMAC(dec_key, w_random||p_random) == device_sign]
 * App  → Band  AUTH_CONFIRM(27) AppConfirm{app_sign, enc_companion}
 * Band → App   AUTH_CONFIRM(27) DeviceConfirm{confirm_result}
 * ```
 */
class AuthHandshake(
    private val channel: WearPacketChannel,
    private val authKey: ByteArray,
    private val appName: String = "HealthAPP",
) {

    class SessionKeys(
        val encKey: ByteArray,
        val decKey: ByteArray,
        val encNonce: ByteArray,
        val decNonce: ByteArray,
        val deviceCapability: Int?,
        val deviceCapability2: Int?,
        val elapsedMs: Long,
    )

    /** 认证失败时抛出，带上可读原因 */
    class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun run(): SessionKeys {
        require(authKey.size == 16) { "authKey 必须是 16 字节，实际 ${authKey.size}" }
        val startedAt = System.currentTimeMillis()

        // ---------------- step 1: 发 AuthVerify ----------------
        val pRandom = ByteArray(16).also { SecureRandom().nextBytes(it) }
        MiWearLog.i("AUTH step1 TX (app_random len=16)")

        val verifyPacket = channel.request(
            WearPacket(
                type = WearPacket.TYPE_ACCOUNT,
                id = Account.ID_AUTH_VERIFY,
                account = Account(AuthAppVerify(pRandom)),
            ),
            predicate = { isAuthReply(it, AuthDeviceVerify::class.java) },
        )

        val verify = when (val p = verifyPacket.account?.payload) {
            is AuthDeviceVerify -> p
            is AccountError -> throw AuthException(
                "设备在认证第 1 步返回错误：${AccountError.errorName(p.code)} (code=${p.code})",
            )
            else -> throw AuthException("认证第 1 步收到意外载荷：${verifyPacket.account?.payload}")
        }

        val wRandom = verify.deviceRandom
        val wSign = verify.deviceSign
        if (wRandom.size != 16) throw AuthException("device_random 长度错误：${wRandom.size}")
        if (wSign.size != 32) throw AuthException("device_sign 长度错误：${wSign.size}")
        MiWearLog.i("AUTH step2 RX (device_random len=16, sign len=32)")

        // ---------------- step 2: 派生密钥 + 校验设备签名 ----------------
        val okm = MiWearCrypto.kdfMiwear(authKey, pRandom, wRandom)
        val decKey = okm.copyOfRange(0, 16)
        val encKey = okm.copyOfRange(16, 32)
        val decNonce = okm.copyOfRange(32, 36)
        val encNonce = okm.copyOfRange(36, 40)

        val expectedSign = MiWearCrypto.hmacSha256(decKey, wRandom, pRandom)
        if (!expectedSign.contentEquals(wSign)) {
            throw AuthException("配对认证失败，请检查已配对手环的资料。")
        }
        MiWearLog.i("AUTH HMAC verify OK")

        // 密钥已确认，立即启用解密 —— 之后设备无论明文还是加密回复都能处理。
        // 注意此时还**不能**启用加密发送：AUTH_CONFIRM 必须明文发出（与设备行为一致）。
        channel.enableDecryption(decKey)

        // ---------------- step 3: 发 AuthConfirm ----------------
        val appSign = MiWearCrypto.hmacSha256(encKey, pRandom, wRandom)

        val companion = CompanionDevice(
            deviceType = CompanionDevice.TYPE_ANDROID,
            deviceName = appName,
            appCapability = -1, // 0xFFFFFFFF 作为 uint32
        ).encode()

        // 12 字节 CCM nonce：4B enc_nonce + 4B counterHi + 4B counterLo（都是 0）
        val pktNonce = ByteArray(12).also { System.arraycopy(encNonce, 0, it, 0, 4) }
        val encCompanion = MiWearCrypto.aesCcmEncrypt(encKey, pktNonce, ByteArray(0), companion)

        val confirmPacket = channel.request(
            WearPacket(
                type = WearPacket.TYPE_ACCOUNT,
                id = Account.ID_AUTH_CONFIRM,
                account = Account(AuthAppConfirm(appSign, encCompanion)),
            ),
            predicate = { isAuthReply(it, AuthDeviceConfirm::class.java) },
        )

        val confirm = when (val p = confirmPacket.account?.payload) {
            is AuthDeviceConfirm -> p
            is AccountError -> throw AuthException(
                "设备在认证第 3 步返回错误：${AccountError.errorName(p.code)} (code=${p.code})",
            )
            else -> throw AuthException("认证第 3 步收到意外载荷：${confirmPacket.account?.payload}")
        }

        if (!confirm.confirmResult) {
            throw AuthException("设备拒绝认证（confirm_result=false）")
        }

        // ---------------- 认证成功，开始加密发送 ----------------
        channel.enableEncryption(encKey)

        val elapsed = System.currentTimeMillis() - startedAt
        MiWearLog.i(
            "AUTH step4 confirm_result=true, authed=true, elapsed=${elapsed}ms, " +
                "cap=${confirm.deviceCapability}, cap2=${confirm.deviceCapability2}",
        )

        return SessionKeys(
            encKey = encKey,
            decKey = decKey,
            encNonce = encNonce,
            decNonce = decNonce,
            deviceCapability = confirm.deviceCapability,
            deviceCapability2 = confirm.deviceCapability2,
            elapsedMs = elapsed,
        )
    }

    private fun isAuthReply(packet: WearPacket, expected: Class<out Any>): Boolean {
        if (packet.type != WearPacket.TYPE_ACCOUNT) return false
        val payload = packet.account?.payload ?: return false
        return expected.isInstance(payload) || payload is AccountError
    }
}
