package com.example.healthapp.data.protocol.fitness

/**
 * `FILE_FITNESS` 载荷的**分片头**。
 *
 * 以前我们把这 4 个字节当成 magic `01 00 01 00`，其实它是分片信息：
 *
 * ```
 * [0] 总片数 total
 * [1] 恒为 0
 * [2] 当前片号 index（1 起）
 * [3] 恒为 0
 * ```
 *
 * 日常活动的文件小，永远是 1 片，所以看起来像固定 magic；睡眠报告这类大文件会被
 * 拆成多片 —— 真机实测 `full_day_sleep` 是 `0a 00 01 00` … `0a 00 0a 00`
 * （共 10 片，每片 4096 字节，前 4 字节是片头）。
 *
 * 只有**第 1 片**在片头后面带 dataId / 分隔符 / 有效性位图；后续片是纯数据续接。
 * 整个文件的 CRC32 在**最后一片**的末尾，覆盖除第 1 片片头以外的全部内容。
 */
class FitnessChunk(
    /** 该文件共几片 */
    val total: Int,
    /** 当前是第几片（1 起） */
    val index: Int,
    /** 去掉 4 字节片头后的内容 */
    val data: ByteArray,
) {
    companion object {
        /** 片头长度 */
        const val HEADER_SIZE = 4

        fun parse(payload: ByteArray): FitnessChunk? {
            if (payload.size <= HEADER_SIZE) return null
            val total = payload[0].toInt() and 0xFF
            val marker1 = payload[1].toInt() and 0xFF
            val index = payload[2].toInt() and 0xFF
            val marker2 = payload[3].toInt() and 0xFF
            // 两个保留字节必须为 0，否则不是我们认为的 FORMAT
            if (marker1 != 0 || marker2 != 0) return null
            if (total < 1 || index < 1 || index > total) return null
            return FitnessChunk(total, index, payload.copyOfRange(HEADER_SIZE, payload.size))
        }
    }
}

/**
 * 把 [FitnessChunk] 按序拼回一个完整帧。
 *
 * 输出仍是 `FitnessFileFrame.parse` 认识的形状：`[total][0][1][0] + data…`，
 * 所以重组成功后直接交给原解析器即可，不用改它。
 *
 * 乱序/丢片一律丢弃当前累积（宁可丢一帧，也不要把错位的数据拼成垃圾睡眠）。
 * 设备是在一条 SPP 链路上顺序推送的，实际很少乱序。
 */
class FitnessFileAssembler {

    private var total = 0
    private var next = 1
    private val parts = ArrayList<ByteArray>()

    /** 喂入一片载荷；返回 null 表示还没拼完（或这片被丢弃） */
    fun accept(payload: ByteArray): ByteArray? {
        val chunk = FitnessChunk.parse(payload) ?: return null

        if (chunk.index == 1) {
            // 新文件开始
            total = chunk.total
            next = 1
            parts.clear()
        }
        if (chunk.total != total || chunk.index != next) {
            // 不是同一个文件，或者中间丢片 —— 丢掉这次的累积，等下一片重新开始
            reset()
            return null
        }

        parts.add(chunk.data)
        next++

        if (next <= total) return null
        return build()
    }

    fun reset() {
        total = 0
        next = 1
        parts.clear()
    }

    /** 当前正在拼的文件共几片 / 已收到几片（调试用） */
    val progress: Pair<Int, Int> get() = total to (next - 1)

    private fun build(): ByteArray? {
        val size = parts.sumOf { it.size }
        if (size == 0) {
            reset()
            return null
        }
        val out = ByteArray(FitnessChunk.HEADER_SIZE + size)
        // 重组后的片头固定成老代码眼里的 magic `01 00 01 00`：
        // 第 1 字节在原格式里是「总片数」，但 FitnessFileFrame 只用它当魔数校验，
        // 写成 0x01 才能让多片文件复用同一套解析路径。
        out[0] = 1
        out[1] = 0
        out[2] = 1
        out[3] = 0
        var p = FitnessChunk.HEADER_SIZE
        for (part in parts) {
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        reset()
        return out
    }
}
