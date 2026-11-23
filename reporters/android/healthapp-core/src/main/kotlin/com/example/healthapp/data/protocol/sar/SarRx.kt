package com.example.healthapp.data.protocol.sar

import com.example.healthapp.data.protocol.MiWearLog
import com.example.healthapp.data.protocol.l2.L2Channel

/**
 * SAR 接收侧状态机 —— **纯逻辑，不碰 IO**，可在 JVM 单元测试里完整覆盖。
 *
 * 它负责三件此前缺失的事：
 *
 * 1. **序号连续性**：设备按递增序号发送。超前说明中间缺帧 → 回 `NAK(期望序号)`
 *    请设备重传；落后说明是重复帧（多半是我们的 ACK 丢了）→ 只补一个累积 ACK，
 *    **不**回 NAK，否则设备会对后面的每个乱序帧重复触发整窗重传。
 * 2. **frx 快速接收帧**：交付给上层，但**既不 ACK 也不推进序号**。
 *    若把 frx 当普通帧推进序号，期望序号会与设备永久错位，之后全是 NAK。
 * 3. **累积 ACK**：PB / LYRA 通道立即 ACK；其余通道攒够窗口的 2/3 或等 500ms
 *    再 ACK；NETWORK / MULTI_MODAL 通道**根本不 ACK**。
 *
 * 参考实现：`research/astrobox-core/src/device/xiaomi/sar/mod.rs` 的 `on_l1_packet`。
 */
class SarRx(private val rawTxWin: Int = 32) {

    /** 需要立刻回给对端的确认 */
    sealed interface Reply {
        data class Ack(val seq: Int) : Reply
        data class Nak(val seq: Int) : Reply
    }

    /** 一帧的处理结论 */
    data class Outcome(
        /** true = 交给上层；false = 丢弃 */
        val deliver: Boolean,
        /** 需要立刻发出的确认；null = 不发 */
        val reply: Reply? = null,
        /** true = 需要启动 500ms 累积 ACK 定时器 */
        val armCumulativeAck: Boolean = false,
    )

    /** 期望收到的下一个 DATA 序号 */
    var expectSeq: Int = 0
        private set

    /** 本会话是否已按序收到过数据帧 */
    var inOrderSeen: Boolean = false
        private set

    /** L1 握手是否已完成。未完成时不参与 ACK / 序号逻辑，只交付 */
    var sessionActive: Boolean = false
        private set

    private var firstFrameSeen = false
    private var cumAckCount = 0
    private var cumAckSeq = 0

    fun markSessionActive() {
        sessionActive = true
    }

    /** 会话重启（设备重发 START_REQ，或本端重连）时归零 */
    fun reset() {
        expectSeq = 0
        inOrderSeen = false
        sessionActive = false
        firstFrameSeen = false
        cumAckCount = 0
        cumAckSeq = 0
    }

    fun onData(seq: Int, frx: Boolean, channel: L2Channel?): Outcome {
        // 握手未完成：设备可能在我们发 START_REQ 之前就推了数据，先交付、不计账
        if (!sessionActive) return Outcome(deliver = true)

        // 这两个通道由设备侧自行管理流控，协议规定不回 ACK
        if (channel == L2Channel.NETWORK || channel == L2Channel.MULTI_MODAL) {
            return Outcome(deliver = true)
        }

        // frx 快速接收：交付，但既不 ACK 也不推进序号
        if (frx) {
            MiWearLog.i("SAR 收到 frx 帧 seq=$seq，不 ACK 不推进序号")
            return Outcome(deliver = true)
        }

        // 本会话第一帧：以实际序号对齐。设备理应发 0，若不同则说明假设有误，
        // 记录日志并采信实测值，避免整条链路被误判为乱序而全部丢弃。
        if (!firstFrameSeen) {
            firstFrameSeen = true
            if (seq != expectSeq) {
                MiWearLog.w("SAR 首帧序号=$seq，与假设的 $expectSeq 不同，以实测值为准")
                expectSeq = seq
            }
        }

        if (seq != expectSeq) {
            val ahead = (seq - expectSeq) and 0xFF
            return if (ahead < 128) {
                // 超前：中间有帧丢了，请设备从期望序号开始重传
                MiWearLog.w("SAR 乱序：收到 seq=$seq，期望 $expectSeq，回 NAK")
                Outcome(deliver = false, reply = Reply.Nak(expectSeq))
            } else {
                // 落后：重复帧，补一个累积 ACK 让设备停下
                if (inOrderSeen) {
                    val ackSeq = (expectSeq - 1) and 0xFF
                    MiWearLog.i("SAR 收到重复帧 seq=$seq，补 ACK $ackSeq")
                    Outcome(deliver = false, reply = Reply.Ack(ackSeq))
                } else {
                    Outcome(deliver = false)
                }
            }
        }

        expectSeq = (seq + 1) and 0xFF
        inOrderSeen = true

        val immediate = cumAckCount >= rawTxWin * 2 / 3 ||
            channel == L2Channel.PB || channel == L2Channel.LYRA

        return if (immediate) {
            cumAckCount = 0
            Outcome(deliver = true, reply = Reply.Ack(seq))
        } else {
            cumAckCount++
            cumAckSeq = seq
            Outcome(deliver = true, armCumulativeAck = true)
        }
    }

    /** 累积 ACK 定时器到期时调用；返回要 ACK 的序号，null 表示无需发送 */
    fun flushCumulativeAck(): Int? {
        if (cumAckCount == 0) return null
        cumAckCount = 0
        return cumAckSeq
    }
}
