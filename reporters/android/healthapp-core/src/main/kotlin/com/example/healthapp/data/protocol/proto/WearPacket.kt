package com.example.healthapp.data.protocol.proto

/**
 * WearPacket —— 所有业务报文的最外层容器。
 *
 * 对应 `wear.proto`：
 * ```protobuf
 * message WearPacket {
 *     required Type type = 1;
 *     required uint32 id = 2;
 *     oneof payload {
 *         Account account = 3;
 *         System  system  = 4;
 *         Fitness fitness = 10;
 *         ErrorCode error_code = 100;   // 注意这是 enum，varint 编码
 *         ...
 *     }
 * }
 * ```
 */
class WearPacket(
    val type: Int,
    val id: Int,
    val account: Account? = null,
    /** System 的载荷在 T9 才需要细化，先保留原始字节 */
    val systemRaw: ByteArray? = null,
    /** Fitness 的载荷（oneof），保留原始字节由 `FitnessMessage.decode` 解析 */
    val fitnessRaw: ByteArray? = null,
    /** WearPacket.ErrorCode（wear_common.proto 的 enum，varint） */
    val errorCode: Int? = null,
) {

    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.varint(FIELD_TYPE, type.toLong())
        w.varint(FIELD_ID, id.toLong())
        account?.let { w.message(FIELD_ACCOUNT, it.encode()) }
        systemRaw?.let { w.message(FIELD_SYSTEM, it) }
        fitnessRaw?.let { w.message(FIELD_FITNESS, it) }
        errorCode?.let { w.varint(FIELD_ERROR_CODE, it.toLong()) }
        return w.toByteArray()
    }

    override fun toString(): String = buildString {
        append("WearPacket(type=").append(typeName(type))
        append(", id=").append(id)
        account?.let { append(", account=").append(it) }
        systemRaw?.let { append(", system(len=").append(it.size).append(")") }
        fitnessRaw?.let { append(", fitness(len=").append(it.size).append(")") }
        errorCode?.let { append(", errorCode=").append(errorCodeName(it)) }
        append(")")
    }

    companion object {
        const val TYPE_OTHER = 0
        const val TYPE_ACCOUNT = 1
        const val TYPE_SYSTEM = 2
        const val TYPE_WATCH_FACE = 4
        const val TYPE_NFC = 5
        const val TYPE_NOTIFICATION = 7
        const val TYPE_FITNESS = 8
        const val TYPE_LPA = 9
        const val TYPE_WEATHER = 10
        const val TYPE_STOCK = 11
        const val TYPE_CALENDAR = 12
        const val TYPE_FACTORY = 13
        const val TYPE_AIVS = 14
        const val TYPE_MARKET = 15
        const val TYPE_GNSS = 16
        const val TYPE_CLOCK = 17
        const val TYPE_MEDIA = 18
        const val TYPE_ALEXA = 19
        const val TYPE_THIRDPARTY_APP = 20
        const val TYPE_CONTACT = 21
        const val TYPE_MASS = 22
        const val TYPE_INTERCONNECTION = 23

        const val FIELD_TYPE = 1
        const val FIELD_ID = 2
        const val FIELD_ACCOUNT = 3
        const val FIELD_SYSTEM = 4
        const val FIELD_FITNESS = 10
        const val FIELD_ERROR_CODE = 100

        fun typeName(t: Int): String = when (t) {
            TYPE_OTHER -> "OTHER"
            TYPE_ACCOUNT -> "ACCOUNT"
            TYPE_SYSTEM -> "SYSTEM"
            TYPE_WATCH_FACE -> "WATCH_FACE"
            TYPE_NFC -> "NFC"
            TYPE_NOTIFICATION -> "NOTIFICATION"
            TYPE_FITNESS -> "FITNESS"
            TYPE_LPA -> "LPA"
            TYPE_WEATHER -> "WEATHER"
            TYPE_STOCK -> "STOCK"
            TYPE_CALENDAR -> "CALENDAR"
            TYPE_FACTORY -> "FACTORY"
            TYPE_AIVS -> "AIVS"
            TYPE_MARKET -> "MARKET"
            TYPE_GNSS -> "GNSS"
            TYPE_CLOCK -> "CLOCK"
            TYPE_MEDIA -> "MEDIA"
            TYPE_ALEXA -> "ALEXA"
            TYPE_THIRDPARTY_APP -> "THIRDPARTY_APP"
            TYPE_CONTACT -> "CONTACT"
            TYPE_MASS -> "MASS"
            TYPE_INTERCONNECTION -> "INTERCONNECTION"
            else -> "UNKNOWN($t)"
        }

        /** wear_common.proto 的 ErrorCode enum */
        fun errorCodeName(code: Int): String = when (code) {
            0 -> "NO_ERROR"
            1 -> "NOT_SUPPORT"
            2 -> "DEPENDENCY_NOT_READY"
            3 -> "SET_FAILED"
            4 -> "PARAM_ERROR"
            5 -> "BUSY_IN_OTA"
            6 -> "BUSY_IN_UPLOAD_LOG"
            7 -> "BUSY_IN_A2DP"
            255 -> "UNKNOWN"
            else -> "ERROR_$code"
        }

        fun decode(bytes: ByteArray): WearPacket {
            var type = 0
            var id = 0
            var account: Account? = null
            var systemRaw: ByteArray? = null
            var fitnessRaw: ByteArray? = null
            var errorCode: Int? = null

            val r = ProtoReader(bytes)
            while (r.hasNext()) {
                when (r.nextField()) {
                    FIELD_TYPE -> type = r.readVarint().toInt()
                    FIELD_ID -> id = r.readVarint().toInt()
                    FIELD_ACCOUNT -> account = Account.decode(r.readBytes())
                    FIELD_SYSTEM -> systemRaw = r.readBytes()
                    FIELD_FITNESS -> {
                        val fragment = r.readBytes()
                        fitnessRaw = fitnessRaw?.plus(fragment) ?: fragment
                    }
                    FIELD_ERROR_CODE -> errorCode = r.readVarint().toInt()
                    else -> r.skip()
                }
            }
            return WearPacket(type, id, account, systemRaw, fitnessRaw, errorCode)
        }
    }
}
