package com.example.healthapp.data.protocol

import com.example.healthapp.data.protocol.l2.L2Channel
import com.example.healthapp.data.protocol.l2.L2Opcode

/**
 * 任意 L2 通道的原始报文（`WriteEnc` 的已解密）。
 *
 * [WearPacketChannel.incoming] 只透出 **PB 通道**的 `WearPacket`；
 * 其余通道（MASS / FILE_FITNESS / NETWORK / LYRA / CANOPUS …）走这里，
 * 由上层按各自格式解析 —— 它们**不是** protobuf，按 protobuf 解会得到
 * 形如「不支持的 wire type: N」的随机错误。
 */
data class L2Event(
    val channel: L2Channel,
    val opcode: L2Opcode,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is L2Event) return false
        return channel == other.channel && opcode == other.opcode &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + opcode.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "L2Event(channel=$channel, opcode=$opcode, len=${payload.size})"
}
