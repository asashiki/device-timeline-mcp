package com.example.healthapp.data.protocol.proto

/**
 * Account 消息的 payload（oneof）。
 *
 * 对应 `wear_account.proto` 的 `Account.payload`，我们只实现认证相关的分支。
 * 未识别的分支保留原始字节，便于调试时打印。
 */
sealed interface AccountPayload

/** `Auth.AppVerify` —— 认证第 1 步，手机 → 设备 */
class AuthAppVerify(
    /** 16 字节随机数，记为 P_RANDOM */
    val appRandom: ByteArray,
    val appDeviceId: String? = null,
    val checkDynamicCode: Boolean? = null,
) : AccountPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.bytes(FIELD_APP_RANDOM, appRandom)
        appDeviceId?.let { w.string(FIELD_APP_DEVICE_ID, it) }
        checkDynamicCode?.let { w.bool(FIELD_CHECK_DYNAMIC_CODE, it) }
        return w.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is AuthAppVerify &&
            appRandom.contentEquals(other.appRandom) &&
            appDeviceId == other.appDeviceId &&
            checkDynamicCode == other.checkDynamicCode

    override fun hashCode(): Int = appRandom.contentHashCode()

    override fun toString(): String = "AuthAppVerify(appRandom.len=${appRandom.size})"

    companion object {
        const val FIELD_APP_RANDOM = 1
        const val FIELD_APP_DEVICE_ID = 2
        const val FIELD_CHECK_DYNAMIC_CODE = 3

        fun decode(bytes: ByteArray): AuthAppVerify {
            var appRandom = ByteArray(0)
            var appDeviceId: String? = null
            var checkDynamicCode: Boolean? = null
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_APP_RANDOM -> appRandom = r.readBytes()
                    FIELD_APP_DEVICE_ID -> appDeviceId = r.readString()
                    FIELD_CHECK_DYNAMIC_CODE -> checkDynamicCode = r.readBool()
                    else -> r.skip()
                }
            }
            return AuthAppVerify(appRandom, appDeviceId, checkDynamicCode)
        }
    }
}

/** `Auth.DeviceVerify` —— 认证第 2 步，设备 → 手机 */
class AuthDeviceVerify(
    /** 16 字节随机数，记为 W_RANDOM */
    val deviceRandom: ByteArray,
    /** 32 字节 HMAC-SHA256 */
    val deviceSign: ByteArray,
) : AccountPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.bytes(FIELD_DEVICE_RANDOM, deviceRandom)
        w.bytes(FIELD_DEVICE_SIGN, deviceSign)
        return w.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is AuthDeviceVerify &&
            deviceRandom.contentEquals(other.deviceRandom) &&
            deviceSign.contentEquals(other.deviceSign)

    override fun hashCode(): Int = 31 * deviceRandom.contentHashCode() + deviceSign.contentHashCode()

    override fun toString(): String =
        "AuthDeviceVerify(deviceRandom.len=${deviceRandom.size}, sign.len=${deviceSign.size})"

    companion object {
        const val FIELD_DEVICE_RANDOM = 1
        const val FIELD_DEVICE_SIGN = 2

        fun decode(bytes: ByteArray): AuthDeviceVerify {
            var deviceRandom = ByteArray(0)
            var deviceSign = ByteArray(0)
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_DEVICE_RANDOM -> deviceRandom = r.readBytes()
                    FIELD_DEVICE_SIGN -> deviceSign = r.readBytes()
                    else -> r.skip()
                }
            }
            return AuthDeviceVerify(deviceRandom, deviceSign)
        }
    }
}

/** `Auth.AppConfirm` —— 认证第 3 步，手机 → 设备 */
class AuthAppConfirm(
    /** 32 字节 HMAC-SHA256 */
    val appSign: ByteArray,
    /** AES-128-CCM 加密后的 CompanionDevice（密文 ‖ 4 字节 tag） */
    val encryptCompanionDevice: ByteArray,
) : AccountPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.bytes(FIELD_APP_SIGN, appSign)
        w.bytes(FIELD_ENCRYPT_COMPANION_DEVICE, encryptCompanionDevice)
        return w.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is AuthAppConfirm &&
            appSign.contentEquals(other.appSign) &&
            encryptCompanionDevice.contentEquals(other.encryptCompanionDevice)

    override fun hashCode(): Int =
        31 * appSign.contentHashCode() + encryptCompanionDevice.contentHashCode()

    override fun toString(): String =
        "AuthAppConfirm(sign.len=${appSign.size}, companion.len=${encryptCompanionDevice.size})"

    companion object {
        const val FIELD_APP_SIGN = 1
        const val FIELD_ENCRYPT_COMPANION_DEVICE = 2

        fun decode(bytes: ByteArray): AuthAppConfirm {
            var appSign = ByteArray(0)
            var encCompanion = ByteArray(0)
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_APP_SIGN -> appSign = r.readBytes()
                    FIELD_ENCRYPT_COMPANION_DEVICE -> encCompanion = r.readBytes()
                    else -> r.skip()
                }
            }
            return AuthAppConfirm(appSign, encCompanion)
        }
    }
}

/** `Auth.DeviceConfirm` —— 认证第 4 步，设备 → 手机 */
class AuthDeviceConfirm(
    val confirmResult: Boolean,
    val deviceCapability: Int? = null,
    val deviceCapability2: Int? = null,
) : AccountPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.bool(FIELD_CONFIRM_RESULT, confirmResult)
        deviceCapability?.let { w.varint(FIELD_DEVICE_CAPABILITY, it.toLong()) }
        deviceCapability2?.let { w.varint(FIELD_DEVICE_CAPABILITY_2, it.toLong()) }
        return w.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is AuthDeviceConfirm &&
            confirmResult == other.confirmResult &&
            deviceCapability == other.deviceCapability &&
            deviceCapability2 == other.deviceCapability2

    override fun hashCode(): Int {
        var result = confirmResult.hashCode()
        result = 31 * result + (deviceCapability ?: 0)
        result = 31 * result + (deviceCapability2 ?: 0)
        return result
    }

    override fun toString(): String =
        "AuthDeviceConfirm(result=$confirmResult, cap=$deviceCapability, cap2=$deviceCapability2)"

    companion object {
        const val FIELD_CONFIRM_RESULT = 1
        const val FIELD_DEVICE_CAPABILITY = 2
        const val FIELD_DEVICE_CAPABILITY_2 = 3

        fun decode(bytes: ByteArray): AuthDeviceConfirm {
            var confirmResult = false
            var cap: Int? = null
            var cap2: Int? = null
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_CONFIRM_RESULT -> confirmResult = r.readBool()
                    FIELD_DEVICE_CAPABILITY -> cap = r.readVarint().toInt()
                    FIELD_DEVICE_CAPABILITY_2 -> cap2 = r.readVarint().toInt()
                    else -> r.skip()
                }
            }
            return AuthDeviceConfirm(confirmResult, cap, cap2)
        }
    }
}

/** `Account.ErrorCode` —— 认证失败时设备可能回这个 */
class AccountError(val code: Int) : AccountPayload {
    override fun toString(): String = "AccountError(code=$code, ${errorName(code)})"

    companion object {
        fun errorName(code: Int): String = when (code) {
            0 -> "NO_ERROR"
            1 -> "HAVE_BOUND"
            2 -> "INFO_MISSING"
            3 -> "INVALID_PARAM"
            4 -> "NO_BOUND"
            5 -> "SERVER_ERROR"
            6 -> "CANCEL"
            7 -> "VERIFY_ERROR"
            8 -> "STATE_NOT_MATCH"
            9 -> "USER_CHANGED"
            10 -> "CONNECTION_REACH_LIMIT"
            100 -> "UNKNOWN"
            else -> "ERR_$code"
        }
    }
}

/** 未识别的 payload 分支，保留原始字节 */
class UnknownAccountPayload(val field: Int, val raw: ByteArray) : AccountPayload {
    override fun toString(): String = "UnknownAccountPayload(field=$field, len=${raw.size})"
}

/**
 * Account 消息。
 *
 * 对应 `wear_account.proto` 的 `message Account`。
 * 注意 `id` 字段不在这里 —— 它在 WearPacket.id 上。
 */
class Account(val payload: AccountPayload) {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        when (val p = payload) {
            is AuthAppVerify -> w.message(FIELD_AUTH_APP_VERIFY, p.encode())
            is AuthDeviceVerify -> w.message(FIELD_AUTH_DEVICE_VERIFY, p.encode())
            is AuthAppConfirm -> w.message(FIELD_AUTH_APP_CONFIRM, p.encode())
            is AuthDeviceConfirm -> w.message(FIELD_AUTH_DEVICE_CONFIRM, p.encode())
            is AccountError -> w.varint(FIELD_ERROR_CODE, p.code.toLong())
            is BindStartV2 -> w.message(FIELD_BIND_START_V2, p.encode())
            // 这三个只从设备往 App 发，没有 encode
            is BindInfoV2, is LocalDeviceVerify, is LocalDeviceConfirm ->
                error("仅用于接收的消息不应被编码：${p::class.simpleName}")
            is LocalAppVerify -> w.message(FIELD_LOCAL_APP_VERIFY, p.encode())
            is LocalAppConfirm -> w.message(FIELD_LOCAL_APP_CONFIRM, p.encode())
            is BindResultV2 -> w.message(FIELD_BIND_RESULT_V2, p.encode())
            is UnknownAccountPayload -> w.bytes(p.field, p.raw)
        }
        return w.toByteArray()
    }

    override fun toString(): String = "Account($payload)"

    companion object {
        // AccountID 枚举里我们关心的项
        const val ID_BIND_STATUS = 0
        const val ID_BIND_START = 1
        const val ID_BIND_RESULT = 2
        const val ID_LOGIN_STATUS = 3
        const val ID_LOGIN_START = 4
        const val ID_ACCOUNT_VERIFY = 5
        const val ID_BOND_APPLY = 16
        const val ID_BIND_START_V2 = 17
        const val ID_BIND_VERIFY = 18
        const val ID_BIND_CONFIRM = 19
        const val ID_BIND_RESULT_V2 = 25
        const val ID_AUTH_VERIFY = 26
        const val ID_AUTH_CONFIRM = 27
        const val ID_APPLY_DEVICE_PASSTOKEN = 28
        const val ID_REPLY_DEVICE_PASSTOKEN = 29
        const val ID_NOTIFY_LOGIN_STATUS_CHANGED = 30

        // Account.payload 的 oneof 字段号
        const val FIELD_ERROR_CODE = 3
        // 绑定（LOCAL BIND）相关字段号，来自 wear_account.proto
        const val FIELD_BIND_START_V2 = 11
        const val FIELD_BIND_INFO_V2 = 12
        const val FIELD_LOCAL_APP_VERIFY = 17
        const val FIELD_LOCAL_DEVICE_VERIFY = 18
        const val FIELD_LOCAL_APP_CONFIRM = 19
        const val FIELD_LOCAL_DEVICE_CONFIRM = 20
        const val FIELD_BIND_RESULT_V2 = 29

        const val FIELD_AUTH_APP_VERIFY = 30
        const val FIELD_AUTH_DEVICE_VERIFY = 31
        const val FIELD_AUTH_APP_CONFIRM = 32
        const val FIELD_AUTH_DEVICE_CONFIRM = 33

        fun decode(bytes: ByteArray): Account {
            var payload: AccountPayload = UnknownAccountPayload(0, ByteArray(0))
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (val field = r.nextField()) {
                    FIELD_ERROR_CODE -> payload = AccountError(r.readVarint().toInt())
                    FIELD_BIND_INFO_V2 -> payload = BindInfoV2.decode(r.readBytes())
                    FIELD_LOCAL_DEVICE_VERIFY -> payload = LocalDeviceVerify.decode(r.readBytes())
                    FIELD_LOCAL_DEVICE_CONFIRM -> payload = LocalDeviceConfirm.decode(r.readBytes())
                    FIELD_AUTH_APP_VERIFY -> payload = AuthAppVerify.decode(r.readBytes())
                    FIELD_AUTH_DEVICE_VERIFY -> payload = AuthDeviceVerify.decode(r.readBytes())
                    FIELD_AUTH_APP_CONFIRM -> payload = AuthAppConfirm.decode(r.readBytes())
                    FIELD_AUTH_DEVICE_CONFIRM -> payload = AuthDeviceConfirm.decode(r.readBytes())
                    else -> {
                        // 未识别的分支：LEN 类型保留原始字节，其余直接跳过
                        val raw = if (r.lastWireType == WireType.LEN) {
                            r.readBytes()
                        } else {
                            r.skip()
                            ByteArray(0)
                        }
                        payload = UnknownAccountPayload(field, raw)
                    }
                }
            }
            return Account(payload)
        }
    }
}

/**
 * CompanionDevice —— 认证第 3 步里被 AES-CCM 加密进 AppConfirm 的载荷。
 *
 * 对应 `wear_account.proto` 的 `message CompanionDevice`。
 * `device_type` 和 `device_name` 是 proto2 的 required 字段，
 * 即使 device_type == ANDROID(0) 也必须显式写出。
 */
class CompanionDevice(
    val deviceType: Int,
    val deviceName: String,
    val systemVersion: Float? = null,
    val appCapability: Int? = null,
    val region: String? = null,
    val serverPrefix: String? = null,
) {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.varint(FIELD_DEVICE_TYPE, deviceType.toLong()) // required，即使是 0 也写
        systemVersion?.let { w.float(FIELD_SYSTEM_VERSION, it) }
        w.string(FIELD_DEVICE_NAME, deviceName)          // required
        appCapability?.let { w.varint(FIELD_APP_CAPABILITY, (it.toLong() and 0xFFFFFFFFL)) }
        region?.let { w.string(FIELD_REGION, it) }
        serverPrefix?.let { w.string(FIELD_SERVER_PREFIX, it) }
        return w.toByteArray()
    }

    override fun toString(): String =
        "CompanionDevice(type=$deviceType, name=$deviceName, cap=$appCapability)"

    companion object {
        const val TYPE_ANDROID = 0
        const val TYPE_IOS = 1
        const val TYPE_VELA = 2
        const val TYPE_OTHER = 15

        const val FIELD_DEVICE_TYPE = 1
        const val FIELD_SYSTEM_VERSION = 2
        const val FIELD_DEVICE_NAME = 3
        const val FIELD_APP_CAPABILITY = 4
        const val FIELD_REGION = 5
        const val FIELD_SERVER_PREFIX = 6

        fun decode(bytes: ByteArray): CompanionDevice {
            var deviceType = TYPE_ANDROID
            var deviceName = ""
            var systemVersion: Float? = null
            var appCapability: Int? = null
            var region: String? = null
            var serverPrefix: String? = null

            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_DEVICE_TYPE -> deviceType = r.readVarint().toInt()
                    FIELD_SYSTEM_VERSION -> systemVersion = r.readFloat()
                    FIELD_DEVICE_NAME -> deviceName = r.readString()
                    FIELD_APP_CAPABILITY -> appCapability = r.readVarint().toInt()
                    FIELD_REGION -> region = r.readString()
                    FIELD_SERVER_PREFIX -> serverPrefix = r.readString()
                    else -> r.skip()
                }
            }
            return CompanionDevice(
                deviceType, deviceName, systemVersion, appCapability, region, serverPrefix,
            )
        }
    }
}
