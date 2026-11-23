package com.example.healthapp.data.domain

/**
 * 一条**按分钟**的健康记录。
 *
 * 字段与 APK 内 `assets/schemas.zip → daily_activity/record/daily_activity_record_v4.json`
 * 一一对应 —— 那才是权威定义。所有量都是**增量**（`newSteps` / `newDistance` /
 * `activityCalories` / `newCalories`），所以当天总量必须由这些记录**累加**得到。
 *
 * 为什么要落库：手环只保留「尚未同步」的分钟窗口（实测约 1 小时），
 * 一次读取拿不到全天。小米运动健康也是靠本地库长期累积才能显示当天总量。
 */
data class FitnessRecord(
    /** 该分钟槽的起点，UTC 秒 */
    val minuteTimestamp: Long,
    /** bit13..0 of heartRateAndStep —— 新增步数 */
    val steps: Int = 0,
    /** newDistance —— 新增里程 */
    val distance: Int = 0,
    /** bit5..0 of activeTypeAndCalories —— 新增活动卡路里 */
    val activityCalories: Int = 0,
    /** bit15..10 of caloriesAndEnergy —— 新增总卡路里 */
    val totalCalories: Int = 0,
    /** heartRate */
    val heartRate: Int = 0,
    /** bloodOxygen */
    val spo2: Int = 0,
    /** curPressure */
    val stress: Int = 0,
    /** bit7..6 of activeTypeAndCalories：0 未知 / 1 静止 / 2 走 / 3 跑 */
    val activityType: Int = 0,
    /** bit7..5 of activeStrengthAndSportType：0 静止 … 5 剧烈 / 7 未知 */
    val activityStrength: Int = 0,
    /** bit4..0 of activeStrengthAndSportType */
    val sportType: Int = 0,
) {
    /** 这一分钟什么都没有，不值得入库 */
    val isBlank: Boolean
        get() = steps == 0 && distance == 0 && activityCalories == 0 &&
            totalCalories == 0 && heartRate == 0 && spo2 == 0 && stress == 0
}

/** 某一天的汇总 */
data class DaySummary(
    val steps: Long,
    val distance: Long,
    val activityCalories: Long,
    val totalCalories: Long,
    val maxHeartRate: Int,
    val latestSpo2: Int?,
    val latestStress: Int?,
    val minutes: Int,
    val firstMinute: Long,
    val lastMinute: Long,
) {
    val isEmpty: Boolean get() = minutes == 0

    companion object {
        val EMPTY = DaySummary(0, 0, 0, 0, 0, null, null, 0, 0, 0)
    }
}

/**
 * 汇总算法。
 *
 * - 步数 / 距离 / 卡路里：**求和**（都是增量）
 * - 心率：取**最大**
 * - 血氧 / 压力：取**时间上最后一个非零值**
 */
object DaySummaryCalculator {

    fun summarize(records: List<FitnessRecord>): DaySummary {
        if (records.isEmpty()) return DaySummary.EMPTY

        var steps = 0L
        var distance = 0L
        var activityCal = 0L
        var totalCal = 0L
        var maxHr = 0
        var spo2: Int? = null
        var stress: Int? = null
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE

        for (r in records) {
            steps += r.steps
            distance += r.distance
            activityCal += r.activityCalories
            totalCal += r.totalCalories
            if (r.heartRate > maxHr) maxHr = r.heartRate
            if (r.minuteTimestamp < first) first = r.minuteTimestamp
            if (r.minuteTimestamp > last) last = r.minuteTimestamp
        }

        // 血氧/压力取最后一个非零：先按时间排好再扫
        for (r in records.sortedBy { it.minuteTimestamp }) {
            if (r.spo2 > 0) spo2 = r.spo2
            if (r.stress > 0) stress = r.stress
        }

        return DaySummary(
            steps = steps,
            distance = distance,
            activityCalories = activityCal,
            totalCalories = totalCal,
            maxHeartRate = maxHr,
            latestSpo2 = spo2,
            latestStress = stress,
            minutes = records.size,
            firstMinute = if (first == Long.MAX_VALUE) 0 else first,
            lastMinute = if (last == Long.MIN_VALUE) 0 else last,
        )
    }
}
