package com.example.healthapp.data.protocol.fitness

/**
 * 7 字节 dataId —— 就是官方 schema 里的 **header 前 7 字节**。
 *
 * 字节布局以 APK 内 `assets/schemas.zip` 的
 * `daily_activity/report|record/daily_activity_*.json` 为准（权威定义，不是反编译猜的）：
 * ```
 * [0:4)  timestamp   uint32 LE  秒级
 * [4]    timezone    int8      / 15 分钟；UTC+8 → 32
 * [5]    version     uint8     schema 版本
 * [6]    fileType    bitField  u8
 *          normalDataFlag  bit 7     0=日常数据, 1=运动数据
 *          dataType        bit 6..2  0=步行 1=跑步 … 16=日常步行 32=心率 33=睡眠 34=血压 35=血氧
 *          fileType        bit 1..0  0=打点(记录) 1=报告 2=GPS
 * ```
 * header 后面还有 `encryptionCompressionMode(1B)` + `validityFlags(N B)`，
 * 也就是帧里「分隔字节 + 位图」那两段。
 *
 * 实测样本：`c4 d6 ea 6a 20 04 00` → ts=1793775300, tz=32(+8h), version=4, typeByte=0
 */
data class FitnessDataId(
    val timestamp: Long,
    val tzIn15Min: Int,
    val version: Int,
    val typeByte: Int,
) {
    /** bit7：0 = 日常数据，1 = 运动数据 */
    val normalDataFlag: Int get() = (typeByte ushr 7) and 0x01

    /** bit6..2：日常/运动类型。0 = 步行，16 = 日常步行，32 = 心率 … */
    val dataType: Int get() = (typeByte ushr 2) and 0x1F

    /** bit1..0：0 = 打点（记录），1 = 报告，2 = GPS */
    val fileType: Int get() = typeByte and 0x03

    val utcOffsetMinutes: Int get() = tzIn15Min * 15

    /** 旧名字，等价于 [dataType]，保留以免调用点全改 */
    val dailyType: Int get() = dataType

    /** 该 dataId 在服务端编码下的长度：dataType==0 时 6，否则 7 */
    val serverDataIdLen: Int get() = if (dataType == 0) 6 else 7

    fun toByteArray(): ByteArray {
        val out = ByteArray(SIZE)
        writeUInt32Le(out, 0, timestamp)
        out[4] = tzIn15Min.toByte()
        out[5] = version.toByte()
        out[6] = typeByte.toByte()
        return out
    }

    companion object {
        const val SIZE = 7

        fun parse(bytes: ByteArray, offset: Int = 0): FitnessDataId? {
            if (offset < 0 || offset + SIZE > bytes.size) return null
            return FitnessDataId(
                timestamp = readUInt32Le(bytes, offset),
                tzIn15Min = bytes[offset + 4].toInt() and 0xFF,
                version = bytes[offset + 5].toInt() and 0xFF,
                typeByte = bytes[offset + 6].toInt() and 0xFF,
            )
        }
    }
}

internal fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 3 downTo 0) {
        v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
    }
    return v
}

internal fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 4) {
        bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}
