package com.example.healthapp.data.protocol.fitness

import com.example.healthapp.data.domain.FitnessRecord
import com.example.healthapp.data.protocol.proto.FitnessMessage
import com.example.healthapp.data.protocol.proto.WearPacket

/**
 * 健康数据同步：发请求 + 把收到的帧汇总成当天数字。
 *
 * ## 请求报文（与真机抓到的字节逐字节一致）
 *
 * ```
 * 08 08 | 10 05 | 52 09 | 1a 07 <7 字节 dataId>
 *  ↑       ↑       ↑       ↑
 * type=8  id=5   fitness  field3(id)=dataId
 * ```
 *
 * 也就是 `WearPacket(type=FITNESS, id=5, fitness{ field3 = 7 字节 dataId })`。
 * 设备随后在 `FILE_FITNESS(5)` 通道把记录发回来。
 *
 * ## 语义假设（未完全验证，标出来）
 *
 * - 记录是**按分钟**的桶（`processDailyRecordList` 里每条 +60 秒），
 *   所以步数/卡路里/距离按**求和**处理
 * - `hr` / `spo2` / `pressure` 按**取最大值**（不同分钟的值不能相加）
 */
object FitnessSync {

    // ---- 消息 ID（官方 FitnessEcoPbImpl 里直接读出来的，不是猜）----
    //   id=1  问「今天有哪些 dataId」，带 fitness.sync_param(reason)
    //   id=2  问「历史有哪些 dataId」，无参数
    //   id=3  请求**多个** dataId 的文件，fitness.ids = 拼接的 7 字节
    //   id=4  请求**单个** dataId 的文件，fitness.id  = 7 字节
    //
    // ⚠️ 抓包里还见过 `id=5` + 单个 dataId，但它**不会触发设备推送**（真机实测无响应），
    //    保留 [buildRequest] 只是为了复现抓包字节，正式流程不要用。

    /** 问「今天有哪些 dataId」 */
    const val ID_QUERY_TODAY = 1

    /** 问「历史有哪些 dataId」 */
    const val ID_QUERY_HISTORY = 2

    /** 请求多个 dataId 的文件 */
    const val ID_REQUEST_FILES = 3

    /** 请求单个 dataId 的文件 */
    const val ID_REQUEST_FILE = 4

    /** 抓包里观察到但不触发推送的那个（勿用于正式流程） */
    const val ID_OBSERVED_FILE = 5

    /**
     * 这个 dataId 是不是「日常活动记录」—— 只有这种能交给
     * [FitnessDailyRecordParser] 解。
     *
     * 官方 `FitnessDataParser.getDailyDataParserInstance`：dailyType==0 时
     * fileType==0 → DailyRecordParser；fileType==1 → DailyReportParser（另一套 schema）。
     * 其余 dailyType（睡眠/体重/心电图…）又是别的解析器。
     */
    fun isDailyRecord(id: FitnessDataId): Boolean = id.normalDataFlag == 0 && id.dataType == 0 && id.fileType == 0

    /**
     * 运动数据（bit7=1）。
     *
     * 运动与日常**共用同一条 FILE_FITNESS 通道与同一套请求 API**，
     * 只靠 dataId 第 7 字节的 bit7 区分：
     * `typeByte = (dataType<<7) + (类型<<2) + fileType`
     * 证据：`com/xiaomi/fit/data/common/data/mi/FitnessDataId.genDataTypeByte()`。
     *
     * 一次运动最多 4 个文件（打点/报告/GPS/hrr），dataId 只有低 2 位不同。
     */
    fun isSport(id: FitnessDataId): Boolean = id.normalDataFlag == 1

    /** 运动报告（bit7=1 且 fileType=1）—— 列表与详情都用它 */
    fun isSportReport(id: FitnessDataId): Boolean = isSport(id) && id.fileType == 1

    /** 运动过程打点（bit7=1 且 fileType=0） */
    fun isSportRecord(id: FitnessDataId): Boolean = isSport(id) && id.fileType == 0

    /** 运动 GPS 轨迹（bit7=1 且 fileType=2） */
    fun isSportGps(id: FitnessDataId): Boolean = isSport(id) && id.fileType == 2

    /** 是不是「当天汇总」（日报）—— 总步数等累计值在这里 */
    fun isDailyReport(id: FitnessDataId): Boolean = id.normalDataFlag == 0 && id.dataType == 0 && id.fileType == 1

    // ---- 睡眠分类（index.json $types/activityType/health，权威映射） ----
    // typeByte = normalDataFlag(1bit)<<7 | activityType(5bit)<<2 | fileType(2bit)
    //   daytime_sleep: activityType=2 → 2<<2|1 = 9   （白天小睡，报告）
    //   night_sleep:   activityType=3 → 3<<2|1 = 13  （夜间睡眠，报告）
    //   full_day_sleep: activityType=8 → 8<<2|1 = 33 （全天睡眠报告，手环 9 Pro 主用）

    /** 全天睡眠报告（activityType=8, fileType=1）—— 手环 9 Pro 睡眠主类型 */
    fun isFullDaySleep(id: FitnessDataId): Boolean =
        id.normalDataFlag == 0 && id.dataType == 8 && id.fileType == 1

    /** 夜间睡眠报告（activityType=3, fileType=1） */
    fun isNightSleep(id: FitnessDataId): Boolean =
        id.normalDataFlag == 0 && id.dataType == 3 && id.fileType == 1

    /** 白天小睡报告（activityType=2, fileType=1） */
    fun isDaytimeSleep(id: FitnessDataId): Boolean =
        id.normalDataFlag == 0 && id.dataType == 2 && id.fileType == 1

    /** 任意睡眠类型（全天 / 夜间 / 小睡） */
    fun isSleep(id: FitnessDataId): Boolean = isFullDaySleep(id) || isNightSleep(id) || isDaytimeSleep(id)

    /** 实测设备用的 schema 版本 */
    const val DEFAULT_VERSION = 4

    /** 问一次「今天有哪些待同步的数据」。对应官方 `getTodayFitnessIds` */
    fun buildTodayIdsQuery(reason: Int = 0): WearPacket = WearPacket(
        type = WearPacket.TYPE_FITNESS,
        id = ID_QUERY_TODAY,
        fitnessRaw = FitnessMessage.encodeSyncParam(reason),
    )

    /** 问一次「历史有哪些待同步的数据」。对应官方 `getHistoryFitnessIds` */
    fun buildHistoryIdsQuery(): WearPacket = WearPacket(
        type = WearPacket.TYPE_FITNESS,
        id = ID_QUERY_HISTORY,
    )

    /**
     * 请求 dataId 对应的数据文件。
     *
     * 对应官方 `FitnessEcoPbImpl.requestFitnessIds`：
     * - 恰好 1 个 dataId → `id=4` + `fitness.id`
     * - 多个 → `id=3` + `fitness.ids`
     *
     * 抓包实测的 `id=3` 形式：`08 08 10 03 | 52 10 | 12 0e <dataId1><dataId2>`。
     */
    fun buildDataRequest(ids: ByteArray): WearPacket = when {
        ids.isEmpty() -> buildTodayIdsQuery()
        ids.size == FitnessDataId.SIZE -> WearPacket(
            type = WearPacket.TYPE_FITNESS,
            id = ID_REQUEST_FILE,
            fitnessRaw = FitnessMessage.encodeId(ids),
        )
        else -> WearPacket(
            type = WearPacket.TYPE_FITNESS,
            id = ID_REQUEST_FILES,
            fitnessRaw = FitnessMessage.encodeIds(ids),
        )
    }

    /**
     * 从查询响应里取出 dataId 列表（每 7 字节一个）。
     *
     * 官方响应可能是 `fitness.ids`（多个）或 `fitness.id`（单个），两种都认。
     */
    fun parseIdsReply(fitnessRaw: ByteArray?): List<FitnessDataId> {
        if (fitnessRaw == null || fitnessRaw.isEmpty()) return emptyList()
        val r = com.example.healthapp.data.protocol.proto.ProtoReader(fitnessRaw)
        val ids = linkedSetOf<FitnessDataId>()
        while (r.hasNext()) {
            val field = r.nextField()
            if ((field == FitnessMessage.FIELD_IDS || field == FitnessMessage.FIELD_ID) && r.lastWireType == 2) {
                val bytes = r.readBytes()
                require(bytes.size % FitnessDataId.SIZE == 0) { "Invalid index block length" }
                for (pos in bytes.indices step FitnessDataId.SIZE) {
                    val id = FitnessDataId.parse(bytes, pos) ?: continue
                    if (id.timestamp != 0L || id.version != 0) ids += id
                }
            } else r.skip()
        }
        return ids.toList()
    }

    data class DailyFitness(
        val steps: Long,
        val calories: Long,
        val distance: Long,
        val maxHeartRate: Long,
        val spo2: Long?,
        val pressure: Long?,
        val recordCount: Int,
        val startTimestamp: Long,
        /** true 表示步数/卡路里来自日报（当天累计），而不是分钟明细 */
        val fromReport: Boolean = false,
        /** 手环侧步数（分钟明细累积），Donut 图的手环分段 */
        val bandSteps: Long = 0,
        /** 手机侧步数（系统 provider），Donut 图的手机分段 */
        val phoneSteps: Long = 0,
    ) {
        val isEmpty: Boolean get() = recordCount == 0
    }

    /**
     * 复现抓包里那个 `id=5` 的报文 —— **设备不会因此推送数据**（真机实测无响应）。
     *
     * 只用来做「我们的编码与真机字节一致」的回归测试。
     * 正式流程请用 [buildDataRequest]。
     */
    fun buildRequest(
        startTimestampSec: Long,
        tzIn15Min: Int,
        version: Int = DEFAULT_VERSION,
    ): WearPacket {
        val dataId = FitnessDataId(
            timestamp = startTimestampSec,
            tzIn15Min = tzIn15Min,
            version = version,
            typeByte = 0,                 // dataType=0, dailyType=0, fileType=0 → DailyRecord
        )
        return WearPacket(
            type = WearPacket.TYPE_FITNESS,
            id = ID_OBSERVED_FILE,
            fitnessRaw = FitnessMessage.encodeId(dataId.toByteArray()),
        )
    }

    /** 把一批帧汇总成当天数字；帧可以来自同一次同步的多个片段 */
    fun aggregate(frames: List<FitnessFileFrame>): DailyFitness {
        var steps = 0L
        var calories = 0L
        var distance = 0L
        var maxHr = 0L
        var spo2: Long? = null
        var pressure: Long? = null
        var records = 0
        var start = Long.MAX_VALUE

        for (frame in frames) {
            // 只把「日常记录」帧当分钟明细；日报帧的解法和它完全不同，混进来会得到垃圾数
            if (!isDailyRecord(frame.dataId)) continue
            start = minOf(start, frame.dataId.timestamp)
            for (record in FitnessDailyRecordParser.parse(frame)) {
                records++
                steps += record[FitnessDailyRecordParser.ItemType.STEPS] ?: 0L
                calories += record[FitnessDailyRecordParser.ItemType.CALORIES] ?: 0L
                distance += record[FitnessDailyRecordParser.ItemType.DISTANCE] ?: 0L

                val hr = record[FitnessDailyRecordParser.ItemType.HR] ?: 0L
                if (hr > maxHr) maxHr = hr

                val sp = record[FitnessDailyRecordParser.ItemType.SPO2] ?: 0L
                if (sp > 0) spo2 = sp

                val pr = record[FitnessDailyRecordParser.ItemType.PRESSURE] ?: 0L
                if (pr > 0) pressure = pr
            }
        }

        // 日报里有「当天累计」字段，但**设备会用 validityFlags 标记哪些可信**。
        // 真机教训：totalSteps 读到 159 而手表显示 1614，查位图发现 totalStepsValid=0 ——
        // 设备明确说无效。所以这里只采纳标记为有效的字段。
        var rep: FitnessDailyReportParser.DailyReport? = null
        for (frame in frames) {
            if (!isDailyReport(frame.dataId)) continue
            val candidate = FitnessDailyReportParser.parse(
                frame.bitmap, frame.dataId.version, frame.body,
            )
            // 同一时刻可能有多个日报帧，取有效字段最多的那个
            if (rep == null || candidate.validCount > rep.validCount) rep = candidate
        }

        return DailyFitness(
            steps = rep?.totalSteps ?: steps,
            calories = rep?.totalCalories ?: rep?.totalActiveCalories ?: calories,
            distance = distance,
            maxHeartRate = rep?.maxHeartRate ?: maxHr,
            spo2 = rep?.avgOxygenSaturation ?: spo2,
            pressure = rep?.avgStress ?: pressure,
            recordCount = records,
            startTimestamp = if (start == Long.MAX_VALUE) 0L else start,
            fromReport = rep?.totalSteps != null,
        )
    }

    /**
     * 把「日常记录」帧拆成**按分钟**的记录，供本地库累积。
     *
     * 记录帧的第 i 条对应 `dataId.timestamp + i*60` 秒（官方 `processDailyRecordList`
     * 里就是按 60 秒递增）。整分钟都没数据的记录直接丢弃，不入库。
     */
    fun toRecords(frames: List<FitnessFileFrame>): List<FitnessRecord> {
        val out = ArrayList<FitnessRecord>()
        for (frame in frames) {
            if (!isDailyRecord(frame.dataId)) continue
            val parsed = FitnessDailyRecordParser.parse(frame)
            parsed.forEachIndexed { index, r ->
                fun v(id: Int) = (r[id] ?: 0L).toInt()
                val record = FitnessRecord(
                    minuteTimestamp = frame.dataId.timestamp + index * 60L,
                    steps = v(FitnessDailyRecordParser.ItemType.STEPS),
                    distance = v(FitnessDailyRecordParser.ItemType.DISTANCE),
                    activityCalories = v(FitnessDailyRecordParser.ItemType.CALORIES),
                    totalCalories = v(FitnessDailyRecordParser.ItemType.TOTAL_CAL),
                    heartRate = v(FitnessDailyRecordParser.ItemType.HR),
                    spo2 = v(FitnessDailyRecordParser.ItemType.SPO2),
                    stress = v(FitnessDailyRecordParser.ItemType.PRESSURE),
                    activityType = v(FitnessDailyRecordParser.ItemType.ACTIVITY_TYPE),
                    activityStrength = v(FitnessDailyRecordParser.ItemType.ACTIVITY_HI_LEVEL),
                    sportType = v(FitnessDailyRecordParser.ItemType.SPORT_TYPE),
                )
                if (!record.isBlank) out.add(record)
            }
        }
        return out
    }
}
