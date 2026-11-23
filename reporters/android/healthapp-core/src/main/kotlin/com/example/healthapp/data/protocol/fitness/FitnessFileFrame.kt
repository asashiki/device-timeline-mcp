package com.example.healthapp.data.protocol.fitness

import java.util.zip.CRC32

/**
 * `FILE_FITNESS(5)` 通道的载荷帧。
 *
 * 布局（真机字节 + 反编译 `FitnessDataHeader` 双向确认）：
 * ```
 * [0:4)    01 00 01 00      帧头（magic，不参与校验）
 * [4:11)   dataId 7 字节
 * [11]     00               分隔符
 * [12:18)  有效性位图 6 字节（version 4/5 时）
 * [18:-4)  记录体
 * [-4:)    CRC32 (LE)，覆盖 [4:-4)
 * ```
 *
 * `headerLen = dataId(7) + 1 + 位图长度`，与官方
 * `FitnessDataHeader.headerLen = serverDataIdLen + 1 + dataValidityLen` 对齐。
 */
class FitnessFileFrame(
    val dataId: FitnessDataId,
    val bitmap: ByteArray,
    val body: ByteArray,
) {
    /**
     * version → 位图长度。对应 `FitnessDataValidity.getDailyRecordValidityLen()`：
     * 1/2 → 4、3 → 5、4/5 → 6，其余不支持。
     */
    companion object {
        private val MAGIC = byteArrayOf(0x01, 0x00, 0x01, 0x00)

        fun bitmapLengthFor(version: Int): Int = when (version) {
            1, 2 -> 4
            3 -> 5
            4, 5 -> 6
            else -> -1
        }

        /** 解析整帧；magic / CRC 不对或版本不支持时返回 null */
        fun parse(payload: ByteArray): FitnessFileFrame? {
            if (payload.size < MAGIC.size + FitnessDataId.SIZE + 1 + 4) return null
            for (i in MAGIC.indices) {
                if (payload[i] != MAGIC[i]) return null
            }

            val bodyEnd = payload.size - 4
            val expected = readUInt32Le(payload, bodyEnd)
            if (crc32(payload, 4, bodyEnd) != expected) return null

            val dataId = FitnessDataId.parse(payload, MAGIC.size) ?: return null
            // 位图长度按（类型, 文件类, 版本）查表 —— 不同 schema 的 validityFlags 宽度不同
            // （官方 FitnessDataValidity.getDataValidityLen）：
            // - 日报 (dataType 0, fileType 1)：v1/2→2 v3/4→3 v5→4 v6/7→5
            // - full_day_sleep (dataType 8, fileType 1)：v1-4→1 v5→2 v6→3
            // - 其余日常：v1/2→4 v3→5 v4/5→6
            val bitmapLen = when {
                // 运动数据（bit7=1）必须先判，否则 dataType==8 的运动会被当成睡眠报告
                dataId.normalDataFlag == 1 -> sportValidityLen(payload, dataId)
                dataId.dataType == 0 && dataId.fileType == 1 ->
                    FitnessDailyReportParser.validityLength(dataId.version)
                dataId.dataType == 8 && dataId.fileType == 1 ->
                    FitnessSleepReportParser.validityFlagsLen(dataId.version)
                else -> bitmapLengthFor(dataId.version)
            }
            if (bitmapLen <= 0) return null

            val bitmapStart = MAGIC.size + FitnessDataId.SIZE + 1
            val bodyStart = bitmapStart + bitmapLen
            if (bodyStart > bodyEnd) return null

            return FitnessFileFrame(
                dataId = dataId,
                bitmap = payload.copyOfRange(bitmapStart, bodyStart),
                body = payload.copyOfRange(bodyStart, bodyEnd),
            )
        }

        /**
         * 运动报告/打点的 validityFlags 长度。
         *
         * **不能按 dataType 查表**：运动与日常共用 dataType 编号，
         * 自由训练（dataType 8）与 full_day_sleep（dataType 8）会撞车，
         * 用睡眠的表去查运动帧会得到 -1，整帧被判为「解析失败」。
         *
         * 运动各版本的位图宽度在 schema 里没有统一规律（v14 是 8 字节，
         * 实测自由训练 v10 是 6 字节），所以这里**用数据自证**：
         * 所有运动报告的第一个字段都是 `startTime`（u32 时间戳），
         * 在 4..8 里逐个试，取「读出来的值接近 dataId 时间戳」的那个长度。
         *
         * 这比写死版本表稳：换固件/换版本也不用改代码。
         */
        private fun sportValidityLen(payload: ByteArray, dataId: FitnessDataId): Int {
            val bitmapStart = MAGIC.size + FitnessDataId.SIZE + 1
            val bodyEnd = payload.size - 4
            for (candidate in 4..8) {
                val at = bitmapStart + candidate
                if (at + 4 > bodyEnd) continue
                val startTime = readUInt32Le(payload, at)
                // 与 dataId 时间戳相差 2 天内即认为切分正确
                if (kotlin.math.abs(startTime - dataId.timestamp) <= 2 * 86_400L) return candidate
            }
            return -1
        }

        private fun crc32(bytes: ByteArray, from: Int, to: Int): Long {
            val crc = CRC32()
            crc.update(bytes, from, to - from)
            return crc.value
        }
    }
}
