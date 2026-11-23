package com.example.healthapp.data.protocol.l2

/**
 * L2 帧编解码。
 *
 * 线上格式：
 * ```
 * offset  size  field
 * 0       1     channel
 * 1       1     opcode
 * 2       N     payload
 * ```
 *
 * 本类只做结构解析，不做加解密 —— 由上层根据 opcode 决定。
 */
object L2Codec {

    const val HEADER_SIZE = 2

    fun encode(frame: L2Frame): ByteArray {
        val out = ByteArray(HEADER_SIZE + frame.payload.size)
        out[0] = frame.channel.code.toByte()
        out[1] = frame.opcode.code.toByte()
        System.arraycopy(frame.payload, 0, out, HEADER_SIZE, frame.payload.size)
        return out
    }

    fun decode(bytes: ByteArray): L2Frame {
        require(bytes.size >= HEADER_SIZE) { "L2 too short: ${bytes.size}" }
        val channel = L2Channel.fromCode(bytes[0].toInt() and 0xFF)
        val opcode = L2Opcode.fromCode(bytes[1].toInt() and 0xFF)
        val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
        return L2Frame(channel, opcode, payload)
    }
}
