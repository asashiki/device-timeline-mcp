package com.example.healthapp.data.protocol.fitness

/**
 * full_day_sleep（activityType=8, fileType=1, typeByte=33）报告解析器。
 *
 * 依据（全部来自官方反编译源码，不是猜）：
 * - `FitnessDataValidity.getAllDaySleepValidityLen`：位图长度 v1-4→1B、v5→2B、v6→3B
 * - `AllDaySleepParser`（native，v≤4）：固定标量表 + 位图 1 字节
 * - `full_day_sleep_report_v5/v6.json`（schema）：标量恒占字节（valid 只标可信），
 *   数组块按 exists + count>0 条件出现
 * - `OneDimenDataParser`：位序 = 首字节 MSB 起；**valid=0 的字段仍占字节**；
 *   body 为**小端**（官方 ByteBuffer.order(LITTLE_ENDIAN)）
 * - `parseSleepAssistInfo`：数组块 = interval u16 + count u16 + [firstTime u32 (v≥2)] + count×elem
 *
 * v≤4 位序（native 位表按 supportVersion 过滤后的顺序）：
 * ```
 * bit0(sleepFull u8) bit1(bedTime u32) bit2(wakeupTime u32) bit3(sleepQuality u8)
 * bit4(hr数组) bit5(spo2数组) bit6(snore数组,元素u32)
 * ```
 * v5/v6 位序见 `research/sleep_schema_summary.txt`（schema JSON 权威位表）。
 */
object FitnessSleepReportParser {

    /** 解析结果（手环报告不含深/浅/REM 分段 —— 那是手机端算法的产物） */
    data class SleepReport(
        val dataId: FitnessDataId,
        /** sleepFull：1=睡眠已结束（完整报告） */
        val sleepFull: Int,
        val startTimeSec: Long?,
        val endTimeSec: Long?,
        val sleepQuality: Int?,
        val sleepEfficiency: Int?,
        /** 实睡时长（分钟） */
        val asleepDurationMin: Long?,
        val goBedTimeSec: Long?,
        val liveBedTimeSec: Long?,
        val avgHrv: Int?,
        /** 心率打点：从 [firstTimeSec] 起每 [intervalSec] 秒一个 */
        val heartRates: List<Int>,
        val heartRateFirstTimeSec: Long?,
        val heartRateIntervalSec: Int,
        val bloodOxygens: List<Int>,
        val bloodOxygenFirstTimeSec: Long?,
        val bloodOxygenIntervalSec: Int,
        /** 鼾声打点（分贝；旧格式为 float 位模式，原样给出原始整数） */
        val snores: List<Long>,
    )

    fun parse(frame: FitnessFileFrame): SleepReport? =
        parse(frame.bitmap, frame.dataId.version, frame.body)?.copy(dataId = frame.dataId)

    /**
     * @param bitmap validityFlags（长度由 [validityFlagsLen] 决定，FitnessFileFrame 已切好）
     * @param body   数据段（首字节 sleepFull）
     */
    fun parse(bitmap: ByteArray, version: Int, body: ByteArray): SleepReport? {
        if (version >= 5) return parseSchema(version, bitmap, body)
        return parseNative(version, bitmap, body)
    }

    /** validityFlags 字节数（官方 getAllDaySleepValidityLen） */
    fun validityFlagsLen(version: Int): Int = when (version) {
        in 1..4 -> 1
        5 -> 2
        6 -> 3
        else -> -1
    }

    // ------------------------------------------------------------------
    // v1-v4：native 固定布局
    // ------------------------------------------------------------------

    private fun parseNative(version: Int, bitmap: ByteArray, body: ByteArray): SleepReport? {
        if (bitmap.isEmpty()) return null

        // 位序**不是固定表**：官方 OneDimenDataParser.parseDataValid 按 dataTypeArray
        // 顺序遍历，只有 `supportVersion <= 当前 version` 的字段才占用一位：
        //
        //   i = 0
        //   for (t in dataTypeArray) if (t.type >= 0) {
        //       if (t.supportVersion > version) valid = false      // 不占位！
        //       else { valid = bit(7 - i % 8); i++ }
        //   }
        //
        // 所以 v4 的 bedTime 在 bit7、wakeup 在 bit6、quality 在 bit5、hr 在 bit4 ——
        // 之前按固定偏移写，整体错了一位，导致 wakeup 被判无效（真机 2026-11-05 踩到）。
        fun validIndex(name: String): Int = NATIVE_FIELD_ORDER
            .filter { it.supportVersion <= version }
            .indexOfFirst { it.name == name }
            .takeIf { it >= 0 } ?: -1

        fun bit(name: String): Boolean {
            val i = validIndex(name)
            if (i < 0) return false
            val byte = i / 8
            if (byte >= bitmap.size) return false
            return (bitmap[byte].toInt() and (1 shl (7 - i % 8))) != 0
        }

        var pos = 0
        fun need(n: Int) = pos + n <= body.size
        fun u8(): Int? = if (need(1)) body[pos++].toInt() and 0xFF else null
        fun u16(): Int? = if (need(2)) {
            (body[pos].toInt() and 0xFF) or ((body[pos + 1].toInt() and 0xFF) shl 8).also { pos += 2 }
        } else null
        fun u32(): Long? = if (need(4)) {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (body[pos + i].toLong() and 0xFF)
            pos += 4
            v
        } else null

        // ---- 标量：无效也占字节，位只标「可信」----
        // type=-1 isSleepFinish 没有 validity 位，恒 1 字节
        val sleepFull = u8() ?: return null
        val bedRaw = u32() ?: return null
        val wakeRaw = u32() ?: return null
        // quality 从 v4 起才有（supportVersion=4）
        val qualityRaw = if (version >= 4) u8() ?: return null else null

        val bedTime = if (bit("bedTime")) bedRaw else null
        val wakeTime = if (bit("wakeup")) wakeRaw else null
        val quality = if (version >= 4 && bit("quality")) qualityRaw else null

        // ---- 数组块：只有位为真才出现（不存在则不占字节）----
        fun series(elem: Int, present: Boolean): Triple<Int, Long?, List<Long>>? {
            if (!present) return Triple(0, null, emptyList())
            val interval = u16() ?: return null
            val count = u16() ?: return null
            if (count <= 0) return Triple(interval, null, emptyList())
            if (count > MAX_SERIES) return null
            val first = if (version >= 2) u32() ?: return null else 0L
            val list = ArrayList<Long>(count)
            repeat(count) {
                val v = when (elem) {
                    1 -> u8()?.toLong()
                    2 -> u16()?.toLong()
                    4 -> u32()
                    else -> null
                } ?: return null
                list.add(v)
            }
            return Triple(interval, first, list)
        }

        val hr = series(1, bit("hr")) ?: return null
        val spo2 = series(1, bit("spo2")) ?: return null
        val snore = series(4, bit("snore")) ?: return null

        return SleepReport(
            dataId = FitnessDataId(timestamp = 0, tzIn15Min = 0, version = version, typeByte = 33),
            sleepFull = sleepFull,
            startTimeSec = bedTime,
            endTimeSec = wakeTime,
            sleepQuality = quality,
            sleepEfficiency = null,
            asleepDurationMin = null,
            goBedTimeSec = null,
            liveBedTimeSec = null,
            avgHrv = null,
            heartRates = hr.third.map { it.toInt() },
            heartRateFirstTimeSec = hr.second,
            heartRateIntervalSec = hr.first,
            bloodOxygens = spo2.third.map { it.toInt() },
            bloodOxygenFirstTimeSec = spo2.second,
            bloodOxygenIntervalSec = spo2.first,
            snores = snore.third,
        )
    }

    // ------------------------------------------------------------------
    // v5/v6：schema 布局
    // ------------------------------------------------------------------

    private fun parseSchema(version: Int, bitmap: ByteArray, body: ByteArray): SleepReport? {
        // 位图：高位优先拼成整数，bit 号即 schema startBit
        var flags = 0L
        for (b in bitmap) flags = (flags shl 8) or (b.toLong() and 0xFF)
        fun valid(bit: Int): Boolean {
            if (bit >= bitmap.size * 8) return false
            return (flags ushr bit) and 1L == 1L
        }

        val V6 = version >= 6
        val BIT_START = if (V6) 23 else 15
        val BIT_HR = if (V6) 4 else 7
        val BIT_SPO2 = if (V6) 3 else 6
        val BIT_SNORE = if (V6) 1 else 5

        var pos = 0
        fun need(n: Int) = pos + n <= body.size
        fun u8(): Int? = if (need(1)) body[pos++].toInt() and 0xFF else null
        fun u16(): Int? = if (need(2)) {
            val v = (body[pos].toInt() and 0xFF) or ((body[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            v
        } else null
        fun u32(): Long? = if (need(4)) {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (body[pos + i].toLong() and 0xFF)
            pos += 4
            v
        } else null

        // 标量恒占字节；valid 位只决定值是否可信
        val sleepFull = u8() ?: return null

        fun scalar32(bit: Int): Long? {
            val raw = u32() ?: return null
            return if (valid(bit)) raw else null
        }
        fun scalar8(bit: Int): Int? {
            val raw = u8() ?: return null
            return if (valid(bit)) raw else null
        }

        val startTime = scalar32(BIT_START)
        val endTime = scalar32(BIT_START - 1)
        val quality = scalar8(BIT_START - 2)
        val efficiency = scalar8(BIT_START - 3)
        val asleep = scalar32(BIT_START - 4)
        scalar32(BIT_START - 5) // liebBedDuratio（暂不消费）
        val goBed = scalar32(BIT_START - 6)
        val liveBed = scalar32(BIT_START - 7)

        // v6 HRV 标量：bit15..bit5 共 11 项，顺序 hrvAvg/Std/Median/Lower/Upper/Middle(u16)、
        // hrvTimestamp(u32)、Max/Min/BaselineMax/BaselineMin(u16)；恒占字节，valid 只标可信
        var avgHrv: Int? = null
        if (V6) {
            val hrvBits = listOf(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5)
            hrvBits.forEachIndexed { i, bit ->
                if (bit == 9) {
                    u32() ?: return null
                } else {
                    val raw = u16() ?: return null
                    if (i == 0 && valid(bit)) avgHrv = raw
                }
            }
        }

        fun series(elem: Int, existsBit: Int): Triple<Int, Long?, List<Long>>? {
            if (!valid(existsBit)) return Triple(0, null, emptyList())
            val interval = u16() ?: return null
            val count = u16() ?: return null
            if (count <= 0) return Triple(interval, null, emptyList())
            if (count > MAX_SERIES) return null
            val first = u32() ?: return null
            val list = ArrayList<Long>(count)
            repeat(count) {
                val v = when (elem) {
                    1 -> u8()?.toLong()
                    2 -> u16()?.toLong()
                    4 -> u32()
                    else -> null
                } ?: return null
                list.add(v)
            }
            return Triple(interval, first, list)
        }

        val hr = series(1, BIT_HR) ?: return null
        val spo2 = series(1, BIT_SPO2) ?: return null
        if (V6 && valid(2)) { // hrvValues 数组（元素 u16），暂不消费
            val interval = u16() ?: return null
            val count = u16() ?: return null
            if (count > MAX_SERIES) return null
            if (count > 0) {
                u32() ?: return null
                repeat(count) { u16() ?: return null }
            }
        }
        val snore = series(4, BIT_SNORE) ?: return null

        return SleepReport(
            dataId = FitnessDataId(timestamp = 0, tzIn15Min = 0, version = version, typeByte = 33),
            sleepFull = sleepFull,
            startTimeSec = startTime,
            endTimeSec = endTime,
            sleepQuality = quality,
            sleepEfficiency = efficiency,
            asleepDurationMin = asleep,
            goBedTimeSec = goBed,
            liveBedTimeSec = liveBed,
            avgHrv = avgHrv,
            heartRates = hr.third.map { it.toInt() },
            heartRateFirstTimeSec = hr.second,
            heartRateIntervalSec = hr.first,
            bloodOxygens = spo2.third.map { it.toInt() },
            bloodOxygenFirstTimeSec = spo2.second,
            bloodOxygenIntervalSec = spo2.first,
            snores = snore.third,
        )
    }

    private const val MAX_SERIES = 60 * 24

    /**
     * v1–v4 原生布局的字段顺序与最低支持版本。
     *
     * 直接来自官方 `AllDaySleepParser.dataTypeArray`（**顺序不能改**）：
     * 有效性位就是按这个顺序、且只给 `supportVersion <= version` 的字段分配的。
     * `type = -1` 的 isSleepFinish 没有 validity 位，所以不在此表内。
     *
     * ```
     * (-1,1,1) isSleepFinish  → 无位
     * ( 0,4,1) bedTime        ( 1,4,1) wakeupTime   ( 2,1,4) sleepQuality
     * ( 6,1,5) sleepEfficiency( 7,4,5) entrySleep   ( 8,4,5) linBed
     * ( 9,4,5) goBedTime      (10,4,5) leaveBed     ( 3,0,1) hr
     * ( 4,0,1) sp02           ( 5,0,3) snore        (11,0,6) hrv
     * ```
     */
    private val NATIVE_FIELD_ORDER = listOf(
        NativeField("bedTime", 1),
        NativeField("wakeup", 1),
        NativeField("quality", 4),
        NativeField("efficiency", 5),
        NativeField("entrySleep", 5),
        NativeField("linBed", 5),
        NativeField("goBed", 5),
        NativeField("leaveBed", 5),
        NativeField("hr", 1),
        NativeField("spo2", 1),
        NativeField("snore", 3),
        NativeField("hrv", 6),
    )

    private class NativeField(val name: String, val supportVersion: Int)
}
