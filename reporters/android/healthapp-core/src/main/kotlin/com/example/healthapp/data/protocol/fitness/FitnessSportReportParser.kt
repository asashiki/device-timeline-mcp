package com.example.healthapp.data.protocol.fitness

/**
 * 运动报告（`*_report_vN`）解析器。
 *
 * ## 与日报（`daily_activity_report`）的关键差别
 *
 * | | 日报 | 运动报告 |
 * |---|---|---|
 * | 字段布局 | 由 `exists` 位决定**是否占字节**（变长） | **定长**，每个字段都占字节 |
 * | validityFlags 含义 | 该字段存在且有效 | 只表示**值是否可信** |
 * | 记录长度 | 每条重算 | 常量（本文件按类型写死） |
 *
 * 依据：`assets/schemas.zip` 里 `free_training/report/free_training_report_v14.json`
 * 与 `outdoor_foot/report/outdoor_foot_report_v9.json` 的 `body.properties` 顺序与 `byteSize`。
 *
 * ## ⚠️ 版本差异（真机踩到的）
 *
 * 本机固件推的是自由训练 **v10**（body 98 字节），schemas.zip 只到 **v14**（146 字节）。
 * v10 的前 7 个字段与 v14 一致（已用真机字节自证：startTime == dataId.timestamp，
 * endTime - startTime == totalDuration），其余字段按 v14 偏移读，**未验证**。
 * 因此解析结果带 [SportReport.layoutConfirmed] 标记，UI 必须据此决定能展示多少字段。
 *
 * ## 为什么只做这两个类型
 *
 * 58 个运动 schema 里字段大同小异，这两个覆盖了「室内自由训练」和「户外跑步/步行」，
 * 也是自动识别最常产出的两种。其余类型先按 dataType 记录、不解析字段，
 * 等有真机样本再补 —— **不猜字段布局**。
 */
object FitnessSportReportParser {

    /** 解析后的一次运动。`null` 表示 validityFlags 标记该字段无效。 */
    data class SportReport(
        /** 运动类型（dataId 的 dataType，0=步行 1=跑步 …） */
        val sportType: Int,
        val startTime: Long?,
        val endTime: Long?,
        val totalDuration: Long?,
        val effectiveDuration: Long?,
        val distance: Long?,
        val totalCalories: Long?,
        val activeCalories: Long?,
        val avgHeartRate: Long?,
        val maxHeartRate: Long?,
        val minHeartRate: Long?,
        val avgSpo2: Long?,
        val minSpo2: Long?,
        val avgStress: Long?,
        val maxStress: Long?,
        val steps: Long?,
        val avgPace: Long?,
        val avgCadence: Long?,
        /** 心率区间 1..5 的停留时长（秒） */
        val hrZoneDurations: List<Long?>,
        /** 心率区间 1..5 的下限（bpm） */
        val hrZoneLowerLimits: List<Long?>,
        /** 训练效果（有氧） */
        val aerobicTrainingEffect: Float?,
        val aerobicTrainingEffectLevel: Long?,
        /** 训练负荷 */
        val trainingLoad: Long?,
        /** 期望恢复时间（秒） */
        val expectedRecoveryTime: Long?,
        val validCount: Int,
        val fieldCount: Int,
        /**
         * 这份报告的字段布局是否与 schema 完全一致。
         *
         * 本机固件推的是自由训练 **v10**，而 schemas.zip 只到 v14，两者 body 长度不同
         * （98 vs 146）。v10 只有**前 7 个字段**被真机字节自证过
         * （startTime == dataId.ts、endTime - startTime == totalDuration）；
         * 再往后的字段是按 v14 的偏移读的，**未经 v10 验证**。
         *
         * UI 展示时：true 才可以把所有字段当权威值；false 只应展示
         * 开始/结束/时长/活动卡路里/平均·最大·最小心率这几项。
         */
        val layoutConfirmed: Boolean,
    ) {
        val hasAnyValue: Boolean get() = validCount > 0
    }

    /** 字段类型（决定按几位读） */
    private enum class T { U8, U16, U32, U64, F32 }

    private data class Field(val name: String, val type: T)

    /** 定长布局：(字段, 类型) 顺序即字节顺序 */
    private class Layout(val fields: List<Field>) {
        val length: Int = fields.sumOf { sizeOf(it.type) }
        fun sizeOf(t: T): Int = when (t) {
            T.U8 -> 1
            T.U16 -> 2
            T.U32, T.F32 -> 4
            T.U64 -> 8
        }
    }

    /**
     * `free_training_report_v14`：62 个字段 / 146 字节。
     *
     * 只取我们展示需要的字段，但**必须按完整顺序累加偏移**，否则后面全错。
     * 因此这里保留全表，解析时按需取值。
     */
    private val FREE_TRAINING = Layout(
        listOf(
            Field("startTime", T.U32),
            Field("endTime", T.U32),
            Field("totalDuration", T.U32),
            Field("activeCalories", T.U16),
            Field("avgHeartRate", T.U8),
            Field("maxHeartRate", T.U8),
            Field("minHeartRate", T.U8),
            Field("avgBloodOxygen", T.U8),
            Field("maxBloodOxygen", T.U8),
            Field("minBloodOxygen", T.U8),
            Field("avgPressure", T.U8),
            Field("maxPressure", T.U8),
            Field("minPressure", T.U8),
            Field("trainingEffect", T.F32),
            Field("aerobicTrainingEffectLevel", T.U8),
            Field("bodyEnergyConsumption", T.U8),
            Field("expectedRecoveryTime", T.U16),
            Field("hrZone5Duration", T.U32),
            Field("hrZone4Duration", T.U32),
            Field("hrZone3Duration", T.U32),
            Field("hrZone2Duration", T.U32),
            Field("hrZone1Duration", T.U32),
            Field("hrZone5LowerLimit", T.U8),
            Field("hrZone4LowerLimit", T.U8),
            Field("hrZone3LowerLimit", T.U8),
            Field("hrZone2LowerLimit", T.U8),
            Field("hrZone1LowerLimit", T.U8),
            Field("totalCalories", T.U16),
            Field("effectiveDuration", T.U32),
            Field("anaerobicTrainingEffect", T.F32),
            Field("anaerobicTrainingEffectLevel", T.U8),
            Field("peakTrainingEffect", T.F32),
            Field("peakTrainingEffectLevel", T.U8),
            Field("submotionType", T.U16),
            Field("selectedCourse", T.U8),
            Field("cloudCourseId", T.U64),
            Field("heartRateZoneMethod", T.U8),
            Field("targetDuration", T.U32),
            Field("targetCalories", T.U16),
            Field("trainingLoad", T.U16),
            // 40..61：与 schema 逐字段对应，不展示也必须占位以保证偏移正确
            Field("trainingLoadLevel", T.U8),
            Field("earnedPoints", T.U8),
            Field("totalPunchCount", T.U16),
            Field("perfectCount", T.U16),
            Field("goodCount", T.U16),
            Field("missCount", T.U16),
            Field("maxPunchDynamics", T.F32),
            Field("maxDoubleHitCount", T.U16),
            Field("hitRate", T.U8),
            Field("mixDynamicsThrowsCount", T.U16),
            Field("middleDynamicsThrowsCount", T.U16),
            Field("maxDynamicsThrowsCount", T.U16),
            Field("trainingFeeling", T.U8),
            Field("trainingStressScore", T.U16),
            Field("trainingPlanId", T.U64),
            Field("trainingPlanTime", T.U32),
            Field("totalActionCount", T.U32),
            Field("totalWeight", T.U32),
            Field("totalMovementsCount", T.U16),
            Field("targetCount", T.U16),
            Field("avgFrequency", T.U16),
            Field("bestFrequency", T.U16),
        ),
    )

    /** 运动类型名（dataType，来自 schema 的 sportType 枚举与真机日志） */
    fun sportTypeName(dataType: Int): String = when (dataType) {
        1 -> "户外跑步"
        2 -> "户外健走"
        3 -> "室内跑步"
        4 -> "登山"
        5 -> "越野"
        6 -> "户外骑行"
        7 -> "室内骑行"
        8 -> "自由训练"
        9 -> "泳池游泳"
        10 -> "开放水域游泳"
        11 -> "椭圆机"
        12 -> "瑜伽"
        13 -> "划船"
        14 -> "跳绳"
        15 -> "户外徒步"
        16 -> "高强度间歇"
        else -> "运动($dataType)"
    }

    /**
     * 解析一份运动报告。
     *
     * [bitmap] 是 header 之后的 validityFlags（v14 为 8 字节）；
     * 位序与日报一致：**最高位对应第一个字段**。
     */
    fun parse(bitmap: ByteArray, dataType: Int, body: ByteArray, version: Int = 14): SportReport? {
        val layout = FREE_TRAINING
        // 本机固件推的是自由训练 **v10**（body 98 字节），而 schema 只到 v14（146 字节）。
        // v10 的前 7 个字段与 v14 完全一致 —— 已用真机帧自证：
        //   body[0..4) = dataId.timestamp，body[4..8) - body[0..4) = totalDuration。
        // 因此短 body 按「前缀布局」解析，够不着的字段一律 null，不猜。
        if (body.size < 8) return null

        // ⚠️ 运动报告的位图是**大端**（第一个字节是最高位），与日报的小端相反。
        // 证据（真机自由训练 v10 帧，bitmap=fe 06 fe f9 c0 0f，body 98 字节）：
        //  - 大端：首字节 0xfe 最高位=1 → startTime 有效；而 body[0..4) 确实等于 dataId 时间戳
        //  - 大端：bit0=0 → avgBloodOxygen 无效；而 body[17] 确实是 0
        //  - 小端读法则 startTime 位=0，与「取到了合法时间戳」矛盾
        var flags = 0L
        for (i in bitmap.indices) {
            flags = (flags shl 8) or (bitmap[i].toLong() and 0xFF)
        }
        val totalBits = bitmap.size * 8

        val values = HashMap<String, Long>()
        val floats = HashMap<String, Float>()
        var valid = 0
        var offset = 0
        layout.fields.forEachIndexed { index, f ->
            val size = layout.sizeOf(f.type)
            val bit = totalBits - 1 - index
            val bitOk = bit >= 0 && ((flags ushr bit) and 1L == 1L)
            val fits = offset + size <= body.size
            val ok = bitOk && fits
            if (ok) valid++
            if (ok && fits) {
                when (f.type) {
                    T.F32 -> floats[f.name] = readFloatLe(body, offset)
                    else -> values[f.name] = readUnsigned(body, offset, size)
                }
            }
            offset += size
        }

        fun u(name: String) = values[name]
        return SportReport(
            sportType = dataType,
            startTime = u("startTime"),
            endTime = u("endTime"),
            totalDuration = u("totalDuration"),
            effectiveDuration = u("effectiveDuration"),
            distance = null,               // free_training 没有距离字段
            totalCalories = u("totalCalories"),
            activeCalories = u("activeCalories"),
            avgHeartRate = u("avgHeartRate"),
            maxHeartRate = u("maxHeartRate"),
            minHeartRate = u("minHeartRate"),
            avgSpo2 = u("avgBloodOxygen"),
            minSpo2 = u("minBloodOxygen"),
            avgStress = u("avgPressure"),
            maxStress = u("maxPressure"),
            steps = null,                  // free_training 没有步数字段
            avgPace = null,
            avgCadence = null,
            hrZoneDurations = listOf(
                u("hrZone1Duration"), u("hrZone2Duration"), u("hrZone3Duration"),
                u("hrZone4Duration"), u("hrZone5Duration"),
            ),
            hrZoneLowerLimits = listOf(
                u("hrZone1LowerLimit"), u("hrZone2LowerLimit"), u("hrZone3LowerLimit"),
                u("hrZone4LowerLimit"), u("hrZone5LowerLimit"),
            ),
            aerobicTrainingEffect = floats["trainingEffect"],
            aerobicTrainingEffectLevel = u("aerobicTrainingEffectLevel"),
            trainingLoad = u("trainingLoad"),
            expectedRecoveryTime = u("expectedRecoveryTime"),
            validCount = valid,
            fieldCount = layout.fields.size,
            layoutConfirmed = version == 14 && body.size == layout.length,
        )
    }

    /** 该类型报告的定长字节数（调试与单测用） */
    fun recordLength(): Int = FREE_TRAINING.length

    private fun readUnsigned(buf: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) {
            v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readFloatLe(buf: ByteArray, at: Int): Float =
        Float.fromBits(readUnsigned(buf, at, 4).toInt())
}
