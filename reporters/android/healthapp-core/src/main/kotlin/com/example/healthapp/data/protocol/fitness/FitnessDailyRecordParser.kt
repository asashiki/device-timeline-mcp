package com.example.healthapp.data.protocol.fitness

/**
 * 日常活动记录的解析器。
 *
 * 移植自小米运动健康：
 * - `com.xiaomi.fit.fitness.parser.daily.DailyRecordParser`（v1/v2 schema 表）
 * - `com.xiaomi.fit.fitness.parser.base.FourDimenDataParser`（位图 → 记录）
 *
 * ## 三个关键点（都是和实测字节对撞出来的，不是猜）
 *
 * 1. **`exist` 是 bit3**。官方 `FourDimenDataValid(exist, high, middle, low)` 的第一个
 *    参数是 exist，而 `parseDataValid` 传的是 `(nib&8, nib&4, nib&2, nib&1)` ——
 *    所以 exist=bit3、high=bit2、middle=bit1、low=bit0。这一位搞反会导致记录长度
 *    少算 3 字节。
 *
 * 2. **记录内字节偏移由 schema 表的顺序累加决定**；而表里 `FourDimenType(high, middle, low)`
 *    的数字是 **DataItemType 的 ID**（`hr=8`、`steps=2`、`calories=4`…），只决定
 *    「取出来的数写进哪个字段」。两者完全无关，极易混淆。
 *
 * 3. **`DataItemLen` 有两种构造**：
 *    - `DataItemLen(n)` = 整个 n 字节字段就是该值（`byteSize == 记录字节数` 时直接返回原值）
 *    - `DataItemLen(startBit, bitSize)` = 在该字段里按位取
 *
 * ## 已验证
 * 用真机抓到的帧跑：记录长度 = 16、5 条、残留 0；
 * 其中一条记录解出的心率 = **66**，与小米运动健康界面显示一致。
 *
 * ## 未验证
 * - v1 schema 表（`version == 1`）未移植，目前 v1/v2 都按 v2 处理
 * - `isUnsigned`：官方表里是 `false`，但步数/心率必须非负，这里一律按无符号读
 * - 动态字段（`dataType == null` 那一项）在本固件样本里恒不触发，未实现
 */
object FitnessDailyRecordParser {

    /** `DailyRecordParser.DataItemType` 的官方 ID */
    object ItemType {
        const val INCLUDE_SLEEP = 0
        const val INCLUDE_AB_HR = 1
        const val STEPS = 2
        const val ACTIVITY_TYPE = 3
        const val CALORIES = 4
        const val ACTIVITY_HI_LEVEL = 5
        const val SPORT_TYPE = 6
        const val DISTANCE = 7
        const val HR = 8
        const val ENERGY = 9
        const val SLEEP_MODE = 10
        const val TOTAL_CAL = 11
        const val ENERGY_STATE = 12
        const val ENERGY_STATE_VALUE = 13
        const val SPO2 = 14
        const val PRESSURE = 15
        const val HR_PRE_ABNORMAL = 16
        const val LIGHT_VALUE = 17
        const val BODY_MOMENTUM = 18
    }

    /** 位段描述：字段占 [byteSize] 字节；为 0 时表示按 [startBit]/[bitSize] 取位 */
    data class BitField(val byteSize: Int, val startBit: Int, val bitSize: Int)

    /** `FourDimenType(high, middle, low)`，值为 DataItemType 的 ID */
    private data class RecordType(val high: Int, val middle: Int?, val low: Int?)

    private val RECORD_TYPES = listOf(
        RecordType(0, 1, 2),        // 0
        RecordType(3, 4, null),     // 1
        RecordType(5, 6, null),     // 2
        RecordType(7, null, null),  // 3
        RecordType(8, null, null),  // 4
        RecordType(9, null, null),  // 5
        RecordType(10, 12, 13),     // 6
        RecordType(11, 12, 13),     // 7
        RecordType(14, null, null), // 8
        RecordType(15, null, null), // 9
        RecordType(16, null, null), // 10
        RecordType(17, null, null), // 11
        RecordType(18, null, null), // 12
    )

    private fun whole(byteSize: Int) = BitField(byteSize, 0, 0)
    private fun bits(startBit: Int, bitSize: Int) = BitField(0, startBit, bitSize)

    /** `v2DataInfoArray`：12 项，**顺序即记录内字段先后** */
    private data class Item(
        val typeIndex: Int?,
        val byteSize: Int,
        val supportVersion: Int,
        val high: BitField?,
        val middle: BitField?,
        val low: BitField?,
    )

    private val V2 = listOf(
        Item(0, 2, 2, null, bits(14, 1), bits(0, 14)),
        Item(1, 1, 2, bits(6, 2), bits(0, 6), null),
        Item(2, 1, 2, bits(5, 3), bits(0, 5), null),
        Item(3, 2, 2, whole(2), null, null),
        Item(4, 1, 2, whole(1), null, null),
        Item(5, 1, 2, whole(1), null, null),
        Item(7, 2, 2, bits(10, 6), bits(8, 2), bits(0, 8)),
        Item(8, 1, 3, whole(1), null, null),
        Item(9, 1, 3, whole(1), null, null),
        Item(null, 1, 2, whole(1), null, null),   // 动态字段，不计入记录长度
        Item(11, 2, 4, whole(2), null, null),
        Item(12, 2, 4, whole(2), null, null),
    )

    data class Valid(val exist: Boolean, val high: Boolean, val middle: Boolean, val low: Boolean)

    fun parseValid(bitmap: ByteArray, version: Int): Map<Int, Valid> {
        val out = HashMap<Int, Valid>()
        var consumed = 0
        for (item in V2) {
            val idx = item.typeIndex ?: continue
            val byteIndex = consumed / 2
            if (item.supportVersion > version || byteIndex >= bitmap.size) {
                out[idx] = Valid(false, false, false, false)
                continue
            }
            val b = bitmap[byteIndex].toInt() and 0xFF
            val nibble = if (consumed % 2 == 0) (b and 0xF0) ushr 4 else b and 0x0F
            out[idx] = Valid(
                exist = nibble and 0x8 != 0,
                high = nibble and 0x4 != 0,
                middle = nibble and 0x2 != 0,
                low = nibble and 0x1 != 0,
            )
            consumed++
        }
        return out
    }

    fun recordLength(valid: Map<Int, Valid>): Int {
        var total = 0
        for (item in V2) {
            val idx = item.typeIndex ?: continue
            if (valid[idx]?.exist == true) total += item.byteSize
        }
        return total
    }

    /** 解析所有记录；每条是 DataItemType ID → 值 */
    fun parse(frame: FitnessFileFrame): List<Map<Int, Long>> {
        val valid = parseValid(frame.bitmap, frame.dataId.version)
        val recordLen = recordLength(valid)
        if (recordLen <= 0) return emptyList()

        val out = ArrayList<Map<Int, Long>>(frame.body.size / recordLen)
        var pos = 0
        while (pos + recordLen <= frame.body.size) {
            out.add(parseOne(frame.body, pos, valid))
            pos += recordLen
        }
        return out
    }

    private fun parseOne(buf: ByteArray, start: Int, valid: Map<Int, Valid>): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        var offset = 0
        for (item in V2) {
            val idx = item.typeIndex ?: continue
            val v = valid[idx] ?: continue
            if (!v.exist) continue

            val raw = readUnsigned(buf, start + offset, item.byteSize)
            val type = RECORD_TYPES[idx]
            item.high?.let { if (v.high) result[type.high] = extract(it, raw, item.byteSize) }
            item.middle?.let {
                val target = type.middle
                if (v.middle && target != null) result[target] = extract(it, raw, item.byteSize)
            }
            item.low?.let {
                val target = type.low
                if (v.low && target != null) result[target] = extract(it, raw, item.byteSize)
            }
            offset += item.byteSize
        }
        return result
    }

    private fun extract(f: BitField, raw: Long, recordByteSize: Int): Long =
        if (f.byteSize == recordByteSize) {
            raw
        } else {
            val mask = (1L shl f.bitSize) - 1
            (raw ushr f.startBit) and mask
        }

    private fun readUnsigned(buf: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) {
            v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        }
        return v
    }
}
