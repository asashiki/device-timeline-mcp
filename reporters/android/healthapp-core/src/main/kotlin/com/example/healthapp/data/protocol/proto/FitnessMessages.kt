package com.example.healthapp.data.protocol.proto

/**
 * `Fitness` 消息的 payload（oneof）。
 *
 * `wear_fitness.proto` 里有 80+ 个分支，这里**只实现已验证的部分**，
 * 其余一律落到 [UnknownFitnessPayload] 并保留原始字节 —— M3 探路阶段
 * 最需要的就是「未知分支的原始字节」。
 */
sealed interface FitnessPayload

/**
 * `Fitness.BasicData`（oneof 字段 **39**）—— 对应 `Id.GET_BASIC_DATA = 44`。
 *
 * ```protobuf
 * message BasicData {
 *     required uint32 steps       = 1;
 *     required uint32 calories    = 2;
 *     optional uint32 distance    = 3;
 *     optional uint32 heart_rate  = 4;
 *     optional uint32 intensity   = 5;
 *     optional uint32 valid_stand = 6;
 * }
 * ```
 *
 * 注意 `steps` / `calories` 是 **required**，按 proto2 语义应当恒有值。
 */
class BasicData(
    val steps: Int = 0,
    val calories: Int = 0,
    val distance: Int? = null,
    val heartRate: Int? = null,
    /** 中高强度活动分钟数 */
    val intensity: Int? = null,
    /** 站立次数 */
    val validStand: Int? = null,
) : FitnessPayload {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.varint(FIELD_STEPS, steps.toLong())
        w.varint(FIELD_CALORIES, calories.toLong())
        distance?.let { w.varint(FIELD_DISTANCE, it.toLong()) }
        heartRate?.let { w.varint(FIELD_HEART_RATE, it.toLong()) }
        intensity?.let { w.varint(FIELD_INTENSITY, it.toLong()) }
        validStand?.let { w.varint(FIELD_VALID_STAND, it.toLong()) }
        return w.toByteArray()
    }

    override fun toString(): String =
        "BasicData(steps=$steps, kcal=$calories, distance=$distance, " +
            "hr=$heartRate, intensity=$intensity, stand=$validStand)"

    companion object {
        const val FIELD_STEPS = 1
        const val FIELD_CALORIES = 2
        const val FIELD_DISTANCE = 3
        const val FIELD_HEART_RATE = 4
        const val FIELD_INTENSITY = 5
        const val FIELD_VALID_STAND = 6

        fun decode(bytes: ByteArray): BasicData {
            var steps = 0
            var calories = 0
            var distance: Int? = null
            var heartRate: Int? = null
            var intensity: Int? = null
            var validStand: Int? = null

            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_STEPS -> steps = r.readVarint().toInt()
                    FIELD_CALORIES -> calories = r.readVarint().toInt()
                    FIELD_DISTANCE -> distance = r.readVarint().toInt()
                    FIELD_HEART_RATE -> heartRate = r.readVarint().toInt()
                    FIELD_INTENSITY -> intensity = r.readVarint().toInt()
                    FIELD_VALID_STAND -> validStand = r.readVarint().toInt()
                    else -> r.skip()
                }
            }
            return BasicData(steps, calories, distance, heartRate, intensity, validStand)
        }
    }
}

/** 未识别的 Fitness 分支 —— 保留字段号与原始字节，供后续对照 proto 分析 */
class UnknownFitnessPayload(val field: Int, val raw: ByteArray) : FitnessPayload {
    override fun toString(): String =
        "UnknownFitnessPayload(field=$field, len=${raw.size}, hex=${bytesToHexLower(raw)})"
}

private fun bytesToHexLower(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/** `Fitness` 消息解析入口 */
object FitnessMessage {

    // ---- Fitness.payload 的 oneof 字段号（只列用到的）----
    const val FIELD_IDS = 2
    const val FIELD_ID = 3
    const val FIELD_SYNC_PARAM = 5
    const val FIELD_BASIC_DATA = 39

    // ---- Id 枚举（只列用到的）----
    const val ID_GET_BASIC_DATA = 44
    const val ID_REPORT_BASIC_DATA = 47

    fun decode(bytes: ByteArray): FitnessPayload {
        var payload: FitnessPayload = UnknownFitnessPayload(0, ByteArray(0))
        val r = ProtoReader(bytes)
        while (r.hasNext()) {
            when (val field = r.nextField()) {
                FIELD_BASIC_DATA -> payload = BasicData.decode(r.readBytes())
                else -> {
                    val raw = if (r.lastWireType == WireType.LEN) {
                        r.readBytes()
                    } else {
                        r.skip()
                        ByteArray(0)
                    }
                    payload = UnknownFitnessPayload(field, raw)
                }
            }
        }
        return payload
    }

    /**
     * 把 [BasicData] 包进 `Fitness` 的 oneof。
     *
     * 与 `SystemMessage.encodeDeviceInfo` 同理：`WearPacket.fitness` 的载荷是
     * `Fitness` 消息，oneof 在它里面，**多一层包装**。
     */
    fun encodeBasicData(data: BasicData): ByteArray {
        val w = ProtoWriter()
        w.message(FIELD_BASIC_DATA, data.encode())
        return w.toByteArray()
    }

    // ------------------------------------------------ 请求形状候选（M3 探路用）
    //
    // `wear_fitness.proto` 没有公开实现，`GET_BASIC_DATA(44)` 到底要不要带载荷、
    // 带什么载荷只能靠试。下面几个是 `Fitness.payload` 里最像「请求参数」的分支。

    /** `Fitness.ids`（字段 2）—— 看起来是「我要哪些 ID」的列表 */
    fun encodeIds(ids: ByteArray): ByteArray {
        val w = ProtoWriter()
        w.bytes(FIELD_IDS, ids)
        return w.toByteArray()
    }

    /** `Fitness.id`（字段 3）—— 单个 ID */
    fun encodeId(id: ByteArray): ByteArray {
        val w = ProtoWriter()
        w.bytes(FIELD_ID, id)
        return w.toByteArray()
    }

    /** `Fitness.sync_param`（字段 5），`SyncParam { required uint32 reason = 1 }` */
    fun encodeSyncParam(reason: Int): ByteArray {
        val inner = ProtoWriter().varint(1, reason.toLong()).toByteArray()
        val w = ProtoWriter()
        w.message(FIELD_SYNC_PARAM, inner)
        return w.toByteArray()
    }
}
