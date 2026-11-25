package com.example.healthapp.data.protocol.proto

/**
 * System 消息的 payload（oneof）。
 *
 * 对应 `wear_system.proto` 的 `System.payload`，只实现设备信息相关的分支。
 * 请求报文的 `payload` 是 None —— 只靠 `WearPacket.id`（SystemId）区分请求类型。
 */
sealed interface SystemPayload

/**
 * `System.DeviceInfo` —— 对应 `SystemId.GET_DEVICE_INFO = 2` 的响应。
 *
 * ```protobuf
 * message DeviceInfo {
 *     required string serial_number   = 1;
 *     required string firmware_version= 2;
 *     required string imei            = 3;
 *     required string model           = 4;
 *     required string product_device  = 5;
 * }
 * ```
 */
class DeviceInfo(
    val serialNumber: String = "",
    val firmwareVersion: String = "",
    val imei: String = "",
    val model: String = "",
    val productDevice: String = "",
) : SystemPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.string(FIELD_SERIAL_NUMBER, serialNumber)
        w.string(FIELD_FIRMWARE_VERSION, firmwareVersion)
        w.string(FIELD_IMEI, imei)
        w.string(FIELD_MODEL, model)
        w.string(FIELD_PRODUCT_DEVICE, productDevice)
        return w.toByteArray()
    }

    override fun toString(): String =
        "DeviceInfo(model=$model, fw=$firmwareVersion, sn=$serialNumber, product=$productDevice)"

    companion object {
        const val FIELD_SERIAL_NUMBER = 1
        const val FIELD_FIRMWARE_VERSION = 2
        const val FIELD_IMEI = 3
        const val FIELD_MODEL = 4
        const val FIELD_PRODUCT_DEVICE = 5

        fun decode(bytes: ByteArray): DeviceInfo {
            var serialNumber = ""
            var firmwareVersion = ""
            var imei = ""
            var model = ""
            var productDevice = ""

            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_SERIAL_NUMBER -> serialNumber = r.readString()
                    FIELD_FIRMWARE_VERSION -> firmwareVersion = r.readString()
                    FIELD_IMEI -> imei = r.readString()
                    FIELD_MODEL -> model = r.readString()
                    FIELD_PRODUCT_DEVICE -> productDevice = r.readString()
                    else -> r.skip()
                }
            }
            return DeviceInfo(serialNumber, firmwareVersion, imei, model, productDevice)
        }
    }
}

/**
 * `System.DeviceStatus.Battery`。
 *
 * ```protobuf
 * message Battery {
 *     required uint32 capacity      = 1;   // 电量百分比
 *     optional ChargeStatus charge_status = 2;
 *     optional ChargeInfo charge_info     = 3;
 * }
 * ```
 */
class Battery(
    /** 电量百分比 0..100 */
    val capacity: Int = 0,
    val chargeStatus: Int? = null,
) {

    val isCharging: Boolean
        get() = chargeStatus == CHARGE_STATUS_CHARGING || chargeStatus == CHARGE_STATUS_FULL

    val chargeStatusName: String
        get() = when (chargeStatus) {
            CHARGE_STATUS_UNKNOWN -> "未知"
            CHARGE_STATUS_CHARGING -> "充电中"
            CHARGE_STATUS_NOT_CHARGING -> "未充电"
            CHARGE_STATUS_FULL -> "已充满"
            else -> "未知"
        }

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.varint(FIELD_CAPACITY, capacity.toLong())
        chargeStatus?.let { w.varint(FIELD_CHARGE_STATUS, it.toLong()) }
        return w.toByteArray()
    }

    override fun toString(): String = "Battery(capacity=$capacity, status=$chargeStatusName)"

    companion object {
        const val CHARGE_STATUS_UNKNOWN = 0
        const val CHARGE_STATUS_CHARGING = 1
        const val CHARGE_STATUS_NOT_CHARGING = 2
        const val CHARGE_STATUS_FULL = 3

        const val FIELD_CAPACITY = 1
        const val FIELD_CHARGE_STATUS = 2
        const val FIELD_CHARGE_INFO = 3

        fun decode(bytes: ByteArray): Battery {
            var capacity: Int? = null
            var chargeStatus: Int? = null
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_CAPACITY -> capacity = r.readVarint().toInt()
                    FIELD_CHARGE_STATUS -> chargeStatus = r.readVarint().toInt()
                    else -> r.skip()
                }
            }
            return Battery(requireNotNull(capacity).also {require(it in 0..100)}, chargeStatus)
        }
    }
}

/**
 * `System.DeviceStatus` —— 对应 `SystemId.GET_DEVICE_STATUS = 1` 的响应。
 *
 * ```protobuf
 * message DeviceStatus {
 *     required Battery battery = 1;
 * }
 * ```
 */
class DeviceStatus(val battery: Battery) : SystemPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.message(FIELD_BATTERY, battery.encode())
        return w.toByteArray()
    }

    override fun toString(): String = "DeviceStatus($battery)"

    companion object {
        const val FIELD_BATTERY = 1

        fun decode(bytes: ByteArray): DeviceStatus {
            var battery: Battery? = null
            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_BATTERY -> battery = Battery.decode(r.readBytes())
                    else -> r.skip()
                }
            }
            return DeviceStatus(requireNotNull(battery))
        }
    }
}

/** 未识别的 System 分支 */
class UnknownSystemPayload(val field: Int, val raw: ByteArray) : SystemPayload {
    override fun toString(): String = "UnknownSystemPayload(field=$field, len=${raw.size})"
}

/** `System` 消息解析入口 */
object SystemMessage {

    // System.payload 的 oneof 字段号
    const val FIELD_DEVICE_STATUS = 2
    const val FIELD_DEVICE_INFO = 3

    // SystemId 枚举（只列用到的）
    const val ID_GET_DEVICE_STATUS = 1
    const val ID_GET_DEVICE_INFO = 2
    const val ID_GET_STORAGE_INFO = 62

    fun decode(bytes: ByteArray): SystemPayload {
        var payload: SystemPayload = UnknownSystemPayload(0, ByteArray(0))
        val r = ProtoReader(bytes)
        while (r.hasNext()) {
            when (val field = r.nextField()) {
                FIELD_DEVICE_STATUS -> payload = DeviceStatus.decode(r.readBytes())
                FIELD_DEVICE_INFO -> payload = DeviceInfo.decode(r.readBytes())
                else -> {
                    val raw = if (r.lastWireType == WireType.LEN) {
                        r.readBytes()
                    } else {
                        r.skip()
                        ByteArray(0)
                    }
                    payload = UnknownSystemPayload(field, raw)
                }
            }
        }
        return payload
    }

    /**
     * 把 [DeviceInfo] 包进 `System` 的 oneof 里。
     *
     * 注意这里多一层包装：`WearPacket.system` 的载荷是 `System` 消息，
     * `System.payload` 才是 oneof。直接塞 `DeviceInfo.encode()` 会少一层，
     * 解析时会落到「未知分支」。
     */
    fun encodeDeviceInfo(info: DeviceInfo): ByteArray {
        val w = ProtoWriter()
        w.message(FIELD_DEVICE_INFO, info.encode())
        return w.toByteArray()
    }

    /** 同上，包装 [DeviceStatus] */
    fun encodeDeviceStatus(status: DeviceStatus): ByteArray {
        val w = ProtoWriter()
        w.message(FIELD_DEVICE_STATUS, status.encode())
        return w.toByteArray()
    }
}
