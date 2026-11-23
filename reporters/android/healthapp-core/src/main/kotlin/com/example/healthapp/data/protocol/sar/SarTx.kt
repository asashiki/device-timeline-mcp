package com.example.healthapp.data.protocol.sar

import com.example.healthapp.data.protocol.MiWearLog

/**
 * SAR 发送侧状态机 —— **纯逻辑，不碰 IO**，可在 JVM 单元测试里完整覆盖。
 *
 * 实现 Go-Back-N：
 *
 * - **窗口**：同时在途（已发未确认）的帧数不超过 [windowSize]；
 *   超出的帧留在队列里，等 ACK 推进后自动补发。
 * - **累积 ACK**：收到 `ACK(n)` 表示 **n 及之前全部确认**，队列头部按序出队。
 * - **NAK 重传**：`NAK(n)` 表示 n 之前都已收到、但 n 丢了 —— 从 n 开始整窗重发。
 * - **超时重传**：队头（最老的未确认帧）超时，整窗重发。只有队头超时才计数，
 *   与参考实现一致。
 * - **重传上限**：[maxRetries] 次后判定链路失步，返回 `fatal` 让上层断开重连。
 *
 * 参考：`research/astrobox-core/src/device/xiaomi/sar/mod.rs`。
 *
 * 调用方（[com.example.healthapp.data.protocol.WearPacketChannel]）负责：
 * 1. 把 `toSend` 里的帧真正写出去；
 * 2. 把 ACK / NAK 喂给 [onAck] / [onNak]；
 * 3. 每 500ms 调一次 [tick] 驱动超时检测；
 * 4. 见到 `fatal` 时断开链路。
 *
 * ⚠️ **已知边界**：累积确认依赖 8 位回绕比较（`(b-a) & 0xFF < 128`），
 * 因此 `pending` 里的未确认帧数**不应超过 128**。实际窗口 ≤ 32，且调用方
 * 一次只发一个请求，远达不到该上限。若将来要做大批量单向推送，需要改成
 * 显式序号区间或扩大序号空间。
 */
class SarTx(
    /** 同时在途的最大帧数。握手前先用 1，拿到对端 `txWin` 后调整 */
    var windowSize: Int = 1,
    /** 队头超时，超时整窗重发 */
    val sendTimeoutMs: Long = 10_000L,
    /** 同一队头最多重传几次 */
    val maxRetries: Int = 3,
) {

    /** 一个待发出的 L1 DATA 帧 */
    data class Frame(val seq: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return seq == other.seq && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * seq + payload.contentHashCode()
    }

    /** 一次操作的结果 */
    data class TxResult(
        /** 需要按序写出的帧（新帧 + 重传帧） */
        val toSend: List<Frame> = emptyList(),
        /**
         * 重传超过上限，序号已与远端对不上 —— 需要**重开 L1 会话**。
         *
         * 与「直接放弃」的区别（对齐 AstroBox）：只清队列而不重新握手的话，本端序号
         * 已经前进、设备还停在丢的那一帧上，之后每一帧都会被当成乱序帧拒收，直到重连
         * 为止。所以这里只请求重开会话，重试几次才真放弃由上层的
         * [com.example.healthapp.data.protocol.WearPacketChannel] 决定。
         */
        val restartSession: Boolean = false,
    )

    private class Entry(
        val seq: Int,
        val payload: ByteArray,
        var sentAt: Long = 0L,
        var retries: Int = 0,
        var inFlight: Boolean = false,
        var needsRetransmit: Boolean = false,
    )

    private val pending = ArrayDeque<Entry>()
    private var nextSeq = 0

    /** 当前在途（已发未确认）帧数，便于调试与断言 */
    val inFlightCount: Int get() = pending.count { it.inFlight }

    /** 队列里未确认的帧数 */
    val pendingCount: Int get() = pending.size

    // ------------------------------------------------------------- 入队

    /**
     * 把一条 L2 载荷放进发送队列，分配序号并尽量填满窗口。
     *
     * 注意：即使超出窗口也会入队（只是暂时不发），所以调用方不需要自己限流。
     */
    fun enqueue(payload: ByteArray, now: Long): TxResult {
        val seq = nextSeq
        nextSeq = (nextSeq + 1) and 0xFF
        pending.addLast(Entry(seq, payload))
        return TxResult(toSend = flush(now))
    }

    // ------------------------------------------------------------- 确认

    /**
     * 累积确认：`ACK(seq)` 表示 seq 及之前的帧全部收到。
     */
    fun onAck(seq: Int, now: Long): TxResult {
        var removed = 0
        while (pending.isNotEmpty() && seqLe(pending.first().seq, seq)) {
            pending.removeFirst()
            removed++
        }
        if (removed > 0) {
            MiWearLog.i("SAR TX 累计确认到 seq=$seq，出队 $removed 帧，剩余 ${pending.size}")
        }
        return TxResult(toSend = flush(now))
    }

    /**
     * 对端要求从 [seq] 重传。
     *
     * `NAK(seq)` 同时意味着 seq 之前都已收到，所以先按累积确认处理到 seq-1，
     * 再从 seq 开始整窗重发。
     */
    fun onNak(seq: Int, now: Long): TxResult {
        // 先确认 seq 之前的部分
        while (pending.isNotEmpty() && seqLe(pending.first().seq, (seq - 1) and 0xFF)) {
            pending.removeFirst()
        }

        val head = pending.firstOrNull() ?: return TxResult()
        head.retries++
        if (head.retries > maxRetries) {
            MiWearLog.w("SAR TX 对端反复 NAK(seq=$seq)，重传已超 $maxRetries 次，请求重开会话")
            return TxResult(restartSession = true)
        }

        MiWearLog.w("SAR TX 收到 NAK($seq)，整窗重传（第 ${head.retries} 次）")
        for (e in pending) {
            if (seqLe(seq, e.seq)) {
                e.inFlight = false
                e.needsRetransmit = true
            }
        }
        return TxResult(toSend = flush(now))
    }

    // ------------------------------------------------------------- 超时

    /**
     * 超时检测。**只有队头超时才计数** —— 窗口里后面的帧本来就可能还没被确认。
     */
    fun tick(now: Long): TxResult {
        val head = pending.firstOrNull() ?: return TxResult()
        if (!head.inFlight) return TxResult()
        if (now - head.sentAt < sendTimeoutMs) return TxResult()

        head.retries++
        if (head.retries > maxRetries) {
            MiWearLog.w("SAR TX 队头 seq=${head.seq} 超过 $maxRetries 次仍未确认，请求重开会话")
            return TxResult(restartSession = true)
        }

        MiWearLog.w("SAR TX 队头 seq=${head.seq} 超时，整窗重传（第 ${head.retries} 次）")
        for (e in pending) {
            if (e.inFlight) {
                e.inFlight = false
                e.needsRetransmit = true
            }
        }
        return TxResult(toSend = flush(now))
    }

    // ------------------------------------------------------------- 会话

    /** 会话重启（对端重发 START_REQ，或本端重连）：清空队列，序号归零 */
    fun reset() {
        pending.clear()
        nextSeq = 0
    }

    // ------------------------------------------------------------- 内部

    /**
     * 先把标记了重传的帧按序补发（重传**不受窗口限制**），
     * 再在窗口余量内放新帧。
     */
    private fun flush(now: Long): List<Frame> {
        val out = mutableListOf<Frame>()

        for (e in pending) {
            if (e.needsRetransmit) {
                e.needsRetransmit = false
                e.inFlight = true
                e.sentAt = now
                out += Frame(e.seq, e.payload)
            }
        }

        var inFlight = pending.count { it.inFlight }
        val win = windowSize.coerceAtLeast(1)
        for (e in pending) {
            if (inFlight >= win) break
            if (!e.inFlight) {
                e.inFlight = true
                e.sentAt = now
                out += Frame(e.seq, e.payload)
                inFlight++
            }
        }
        return out
    }

    /** 8 位回绕比较：a 是否在 b 之前（含相等） */
    private fun seqLe(a: Int, b: Int): Boolean = ((b - a) and 0xFF) < 128
}
