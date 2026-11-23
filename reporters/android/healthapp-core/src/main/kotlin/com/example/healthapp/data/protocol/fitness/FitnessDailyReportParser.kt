package com.example.healthapp.data.protocol.fitness

/**
 * 当天汇总（日报）解析器。
 *
 * 字段定义**直接来自 APK 内的权威 schema**
 * `assets/schemas.zip → daily_activity/report/daily_activity_report_v6.json`，
 * 不是反编译猜的。header 之后的 `validityFlags` 逐位标记每个字段是否可信 ——
 * **不可信的字段必须当成 null，不能拿来显示**。
 *
 * 真机踩过的坑：`totalSteps` 读到 159，而手表显示 1614。
 * 查 validityFlags 发现 `totalStepsValid`(bit31) = 0，设备明确说这个字段无效。
 * 看着「合理」的那些（静息心率 66、最大心率 89、平均压力 35）恰好都标记为有效。
 * 所以「当天步数」不能靠日报，得靠分钟记录累积（见 `FitnessSync.aggregate`）。
 *
 * 依赖本解析器之前，先确认设备真的推 report 帧；不发请求是收不到的
 * （`FitnessSync.buildTodayIdsQuery` / `buildHistoryIdsQuery`）。
 */
object FitnessDailyReportParser {

    /**
     * 字段表：(名称, 字节数)，顺序即 body 里的字节顺序，v6 共 **34 个字段 / 61 字节**。
     *
     * 与 schema `daily_activity_report_v6.json` 的 `body.properties` 顺序逐条对应；
     * validityFlags 的位号是 **bit39 递减到 bit6**（40 位里用低 34 位）。
     */
    private val FIELDS = listOf(
        "totalSteps" to 4,
        "totalactiveCalories" to 2,
        "heartRate" to 1,
        "restingHeartRate" to 1,
        "maxHeartRate" to 1,
        "maxHeartRateTime" to 4,
        "minHeartRate" to 1,
        "minHeartRateTime" to 4,
        "avgHeartRate" to 1,
        "avgStress" to 1,
        "maxStress" to 1,
        "minStress" to 1,
        "standingindicator" to 3,
        "totalCalories" to 2,
        "residualrecoverytime" to 2,
        "oxygenSaturation" to 1,
        "maxOxygenSaturation" to 1,
        "maxOxygenSaturationTime" to 4,
        "minOxygenSaturation" to 1,
        "minOxygenSaturationTime" to 4,
        "avgOxygenSaturation" to 1,
        "totaltrainingLoad" to 2,
        "totalPast7daystrainingLoad" to 2,
        "trainingLoadLevel" to 1,
        "lowIntensityearnedPoints" to 1,
        "mediumIntensityearnedPoints" to 1,
        "maxIntensityearnedPoints" to 1,
        "totalPast7dayearnedPoints" to 2,
        "targetNextearnedPointsType" to 1,
        "targetNextearnedPointsTiem" to 1,
        "tsb" to 2,
        "ctl" to 2,
        "atl" to 2,
        "tss" to 2,
    )

    /** 有符号字段（schema 里是 int16，其余都是 uint） */
    private val SIGNED = setOf("tsb")

    /**
     * 一份日报。字段为 `null` 表示 `validityFlags` 标记它**无效**（设备没算出来/不适用）。
     */
    data class DailyReport(
        // ---- 活动 ----
        val totalSteps: Long?,
        val totalActiveCalories: Long?,
        val totalCalories: Long?,
        /**
         * 有效活动指示 —— **24 位逐小时打点掩码**，不是次数本身。
         *
         * 实机验证：当天读到 `0x0D80`（3456），二进制 `0000 1101 1000 0000`，
         * 置位 4 个（bit 7/8/10/11），与小米运动健康显示的「活动次数 4 次」一致。
         *
         * 取次数请用 [standingCount]（置位数）。
         */
        val standingIndicator: Long?,
        // ---- 心率 ----
        val heartRate: Long?,
        val restingHeartRate: Long?,
        val maxHeartRate: Long?,
        val maxHeartRateTime: Long?,
        val minHeartRate: Long?,
        val minHeartRateTime: Long?,
        val avgHeartRate: Long?,
        // ---- 压力 ----
        val avgStress: Long?,
        val maxStress: Long?,
        val minStress: Long?,
        // ---- 血氧 ----
        val oxygenSaturation: Long?,
        val maxOxygenSaturation: Long?,
        val maxOxygenSaturationTime: Long?,
        val minOxygenSaturation: Long?,
        val minOxygenSaturationTime: Long?,
        val avgOxygenSaturation: Long?,
        // ---- 恢复 ----
        /** 剩余恢复时间（分钟） */
        val residualRecoveryTime: Long?,
        // ---- 训练负荷 ----
        val totalTrainingLoad: Long?,
        val totalPast7daysTrainingLoad: Long?,
        val trainingLoadLevel: Long?,
        /** TSB 体能状态（int16，可能为负） */
        val tsb: Long?,
        /** CTL 体能水平 */
        val ctl: Long?,
        /** ATL 疲劳度 */
        val atl: Long?,
        /** TSS 训练压力 */
        val tss: Long?,
        // ---- 元气 ----
        val lowIntensityEarnedPoints: Long?,
        val mediumIntensityEarnedPoints: Long?,
        val maxIntensityEarnedPoints: Long?,
        val totalPast7daysEarnedPoints: Long?,
        /** 达到下个元气目标建议的活动类型（0 表示无建议） */
        val targetNextEarnedPointsType: Long?,
        /** 达到下个元气目标建议的活动时长 */
        val targetNextEarnedPointsTime: Long?,
        /** 本次 valid 的字段数 / 总字段数，便于日志判断数据成熟度 */
        val validCount: Int,
        val fieldCount: Int,
    ) {
        /** 当日元气总分（三档强度之和）；三档都无效时为 null */
        val earnedPoints: Long?
            get() {
                val low = lowIntensityEarnedPoints
                val mid = mediumIntensityEarnedPoints
                val max = maxIntensityEarnedPoints
                if (low == null && mid == null && max == null) return null
                return (low ?: 0) + (mid ?: 0) + (max ?: 0)
            }

        /** 活动次数 = 掩码里置位的位数（官方口径） */
        val standingCount: Int?
            get() = standingIndicator?.countOneBits()

        /** 有任何一个字段可用 */
        val hasAnyValue: Boolean get() = validCount > 0
    }

    /** schema 里 body 的固定长度（版本 6 的字段集，34 个字段合计 61 字节） */
    fun recordLength(): Int = FIELDS.sumOf { it.second }

    /** 字段数（日志用） */
    fun fieldCount(): Int = FIELDS.size

    /**
     * 位图长度。schema 里 v6 的 `validityFlags` 是 5 字节（bit39..bit0）；
     * 旧版本（v1/v2 → 2B、v3/v4 → 3B、v5 → 4B）见 `FitnessDataValidity`。
     */
    fun validityLength(version: Int): Int = when (version) {
        1, 2 -> 2
        3, 4 -> 3
        5 -> 4
        6, 7 -> 5
        else -> -1
    }

    fun parse(validityFlags: ByteArray, version: Int, body: ByteArray): DailyReport {
        // validityFlags 当小端无符号整数读，最高位对应 totalStepsValid
        var flags = 0L
        for (i in validityFlags.indices.reversed()) {
            flags = (flags shl 8) or (validityFlags[i].toLong() and 0xFF)
        }
        val totalBits = validityFlags.size * 8

        fun validAt(index: Int): Boolean {
            // 字段 i 对应 bit (totalBits - 1 - i)；位图短于字段数时按最高位对齐左侧
            val bit = totalBits - 1 - index
            if (bit < 0) return false
            return (flags ushr bit) and 1L == 1L
        }

        val values = HashMap<String, Long?>()
        var offset = 0
        var valid = 0
        FIELDS.forEachIndexed { index, (name, size) ->
            if (offset + size > body.size) {
                values[name] = null
                offset += size
                return@forEachIndexed
            }
            // 无效字段在 body 里**仍然占字节**，只是不给值
            values[name] = if (validAt(index)) {
                valid++
                val raw = readUnsigned(body, offset, size)
                if (name in SIGNED && size == 2 && raw > 0x7FFF) raw - 0x10000 else raw
            } else {
                null
            }
            offset += size
        }

        return DailyReport(
            totalSteps = values["totalSteps"],
            totalActiveCalories = values["totalactiveCalories"],
            totalCalories = values["totalCalories"],
            standingIndicator = values["standingindicator"],
            heartRate = values["heartRate"],
            restingHeartRate = values["restingHeartRate"],
            maxHeartRate = values["maxHeartRate"],
            maxHeartRateTime = values["maxHeartRateTime"],
            minHeartRate = values["minHeartRate"],
            minHeartRateTime = values["minHeartRateTime"],
            avgHeartRate = values["avgHeartRate"],
            avgStress = values["avgStress"],
            maxStress = values["maxStress"],
            minStress = values["minStress"],
            oxygenSaturation = values["oxygenSaturation"],
            maxOxygenSaturation = values["maxOxygenSaturation"],
            maxOxygenSaturationTime = values["maxOxygenSaturationTime"],
            minOxygenSaturation = values["minOxygenSaturation"],
            minOxygenSaturationTime = values["minOxygenSaturationTime"],
            avgOxygenSaturation = values["avgOxygenSaturation"],
            residualRecoveryTime = values["residualrecoverytime"],
            totalTrainingLoad = values["totaltrainingLoad"],
            totalPast7daysTrainingLoad = values["totalPast7daystrainingLoad"],
            trainingLoadLevel = values["trainingLoadLevel"],
            tsb = values["tsb"],
            ctl = values["ctl"],
            atl = values["atl"],
            tss = values["tss"],
            lowIntensityEarnedPoints = values["lowIntensityearnedPoints"],
            mediumIntensityEarnedPoints = values["mediumIntensityearnedPoints"],
            maxIntensityEarnedPoints = values["maxIntensityearnedPoints"],
            totalPast7daysEarnedPoints = values["totalPast7dayearnedPoints"],
            targetNextEarnedPointsType = values["targetNextearnedPointsType"],
            targetNextEarnedPointsTime = values["targetNextearnedPointsTiem"],
            validCount = valid,
            fieldCount = FIELDS.size,
        )
    }

    private fun readUnsigned(buf: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) {
            v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        }
        return v
    }
}
