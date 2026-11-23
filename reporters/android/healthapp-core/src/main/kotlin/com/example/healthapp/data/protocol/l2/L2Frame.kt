package com.example.healthapp.data.protocol.l2

/** L2 通道号 */
enum class L2Channel(val code: Int) {
    PB(1),
    MASS(2),
    MASS_VOICE(3),
    FILE_SENSOR(4),
    FILE_FITNESS(5),
    OTA(6),
    NETWORK(7),
    LYRA(8),
    RESEARCH(9),
    MULTI_MODAL(10),
    CANOPUS(0x43);

    companion object {
        fun fromCode(code: Int): L2Channel =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("invalid L2 channel: $code")
    }
}

/** L2 操作码 */
enum class L2Opcode(val code: Int) {
    WRITE(1),
    WRITE_ENC(2),
    READ(3);

    companion object {
        fun fromCode(code: Int): L2Opcode =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("invalid L2 opcode: $code")
    }
}

/**
 * L2 帧：1 字节通道 + 1 字节操作码 + 载荷。
 *
 * 载荷是否需要加解密由 opcode 决定（WRITE_ENC 才加密），
 * 但本类只负责结构，加解密交给上层。
 */
class L2Frame(
    val channel: L2Channel,
    val opcode: L2Opcode,
    val payload: ByteArray,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is L2Frame) return false
        return channel == other.channel &&
            opcode == other.opcode &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + opcode.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "L2Frame(channel=$channel, opcode=$opcode, len=${payload.size})"
}
