package com.example.healthapp.data.protocol.proto

/**
 * 本地绑定（LOCAL BIND）用的 protobuf 消息。
 *
 * 字段号来自 `wear_account.proto`（astrobox-pb 里那份，与本项目手写 proto 一致）。
 *
 * 这套流程的产出是 **authkey**：App 与手环做一次 P-256 ECDH 协商，
 * HKDF 派生出 64 字节，其中 `[40,56)` 就是后续 AUTH 用的 authkey。
 * 所以「换设备没有 authkey」不需要用户去别处抄，直接在这里协商出来。
 *
 * 流程（id 为 Account.AccountID 数值）：
 * ```
 * App  → Band  BIND_START_V2(17)  BindStartV2{hash_user_id}
 * Band → App   BIND_START_V2(17)  BindInfoV2{verify_mode=APP_LOCAL, oob_mode}
 * App  → Band  BIND_VERIFY(18)    BindLocal.AppVerify{app_device_id, app_public_key}
 * Band → App   BIND_VERIFY(18)    BindLocal.DeviceVerify{device_public_key, device_random, device_sign}
 * App  → Band  BIND_CONFIRM(19)   BindLocal.AppConfirm{app_random, app_sign}
 * Band → App   BIND_CONFIRM(19)   BindLocal.DeviceConfirm{confirm_result}
 * App  → Band  BIND_RESULT_V2(25) BindResultV2{encrypt_result_info = CCM(keys[16,32), ...)}
 * Band → App   BIND_RESULT_V2(25) ErrorCode(0)
 * ```
 */

/** `BindStartV2`：发起绑定 */
class BindStartV2(
    /** 是否要求动态验证码；本地绑定用 false */
    val checkDynamicCode: Boolean,
    /** MD5(user_id)，16 字节 */
    val hashUserId: ByteArray,
    val deviceName: String,
) : AccountPayload {
    fun encode(): ByteArray = ProtoWriter()
        .bool(FIELD_CHECK_DYNAMIC_CODE, checkDynamicCode)
        .bytes(FIELD_HASH_USER_ID, hashUserId)
        .string(FIELD_DEVICE_NAME, deviceName)
        .toByteArray()

    companion object {
        const val FIELD_CHECK_DYNAMIC_CODE = 1
        const val FIELD_HASH_USER_ID = 2
        const val FIELD_DEVICE_NAME = 3
        const val FIELD_PID = 4
    }
}

/** `BindInfoV2`：设备告诉我们用哪种绑定方式 */
class BindInfoV2(
    /** 1=SERVER_PSK（需要服务器），2=APP_LOCAL（本地绑定，我们要的） */
    val verifyMode: Int,
    val serialNumber: String?,
    val mac: String?,
    val model: String?,
    /** 0=NO_OOB，3=BUTTON_CONFIRM（手环上点确认） */
    val oobMode: Int,
    val deviceRandom: ByteArray?,
    val did: String?,
) : AccountPayload {
    companion object {
        const val VERIFY_MODE_SERVER_PSK = 1
        const val VERIFY_MODE_APP_LOCAL = 2
        const val OOB_NONE = 0
        const val OOB_BUTTON_CONFIRM = 3

        fun decode(bytes: ByteArray): BindInfoV2 {
            var verifyMode = 0
            var serial: String? = null
            var mac: String? = null
            var model: String? = null
            var oob = 0
            var random: ByteArray? = null
            var did: String? = null
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    1 -> verifyMode = r.readVarint().toInt()
                    2 -> serial = r.readString()
                    3 -> mac = r.readString()
                    4 -> model = r.readString()
                    5 -> oob = r.readVarint().toInt()
                    6 -> random = r.readBytes()
                    7 -> did = r.readString()
                    else -> r.skip()
                }
            }
            return BindInfoV2(verifyMode, serial, mac, model, oob, random, did)
        }
    }
}

/** `BindLocal.AppVerify`：把 App 的 P-256 公钥发过去 */
class LocalAppVerify(val appDeviceId: String, val appPublicKey: ByteArray) : AccountPayload {
    fun encode(): ByteArray = ProtoWriter()
        .string(FIELD_APP_DEVICE_ID, appDeviceId)
        .bytes(FIELD_APP_PUBLIC_KEY, appPublicKey)
        .toByteArray()

    companion object {
        const val FIELD_APP_DEVICE_ID = 1
        const val FIELD_APP_PUBLIC_KEY = 2
    }
}

/** `BindLocal.DeviceVerify`：设备公钥 + 它对我们 nonce 的签名 */
class LocalDeviceVerify(
    val devicePublicKey: ByteArray,
    val deviceRandom: ByteArray,
    val deviceSign: ByteArray,
) : AccountPayload {
    companion object {
        fun decode(bytes: ByteArray): LocalDeviceVerify {
            var pub = ByteArray(0)
            var random = ByteArray(0)
            var sign = ByteArray(0)
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    1 -> pub = r.readBytes()
                    2 -> random = r.readBytes()
                    3 -> sign = r.readBytes()
                    else -> r.skip()
                }
            }
            return LocalDeviceVerify(pub, random, sign)
        }
    }
}

/** `BindLocal.AppConfirm`：回传我们的 nonce 与签名 */
class LocalAppConfirm(val appRandom: ByteArray, val appSign: ByteArray) : AccountPayload {
    fun encode(): ByteArray = ProtoWriter()
        .bytes(FIELD_APP_RANDOM, appRandom)
        .bytes(FIELD_APP_SIGN, appSign)
        .toByteArray()

    companion object {
        const val FIELD_APP_RANDOM = 1
        const val FIELD_APP_SIGN = 2
    }
}

/** `BindLocal.DeviceConfirm`：手环上点确认后的结果 */
class LocalDeviceConfirm(val confirmResult: Boolean, val deviceCapability: Long?) : AccountPayload {
    companion object {
        fun decode(bytes: ByteArray): LocalDeviceConfirm {
            var ok = false
            var cap: Long? = null
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    1 -> ok = r.readBool()
                    2 -> cap = r.readVarint()
                    else -> r.skip()
                }
            }
            return LocalDeviceConfirm(ok, cap)
        }
    }
}

/** `BindResultV2`：把加密后的 ResultInfo 交回去 */
class BindResultV2(val encryptResultInfo: ByteArray) : AccountPayload {
    fun encode(): ByteArray = ProtoWriter()
        .bytes(FIELD_ENCRYPT_RESULT_INFO, encryptResultInfo)
        .toByteArray()

    companion object {
        const val FIELD_ENCRYPT_RESULT_INFO = 1
    }
}

/** `ResultInfo`：绑定结果里带的账号与设备信息（会被 CCM 加密） */
class BindResultInfo(val userId: String, val companionDevice: CompanionDevice) {
    fun encode(): ByteArray = ProtoWriter()
        .string(FIELD_USER_ID, userId)
        .message(FIELD_COMPANION_DEVICE, companionDevice.encode())
        .toByteArray()

    companion object {
        const val FIELD_USER_ID = 1
        const val FIELD_COMPANION_DEVICE = 2
    }
}
