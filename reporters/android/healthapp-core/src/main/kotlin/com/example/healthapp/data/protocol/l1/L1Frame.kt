package com.example.healthapp.data.protocol.l1

/** L1 帧类型，占 type 字节的低 4 位 */
enum class L1Type(val code: Int) {
    NAK(0), ACK(1), CMD(2), DATA(3);

    companion object {
        fun fromCode(code: Int): L1Type =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("invalid L1 type: $code")
    }
}

/**
 * L1 帧：8 字节头 + 载荷。
 *
 * 用普通 class 而非 data class，因为 ByteArray 的 equals 是引用比较，
 * data class 自动生成的 equals 会给出错误语义。
 */
class L1Frame(
    val type: L1Type,
    val frx: Boolean,
    val seq: Int,
    val payload: ByteArray,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is L1Frame) return false
        return type == other.type &&
            frx == other.frx &&
            seq == other.seq &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + frx.hashCode()
        result = 31 * result + seq
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "L1Frame(type=$type, frx=$frx, seq=$seq, len=${payload.size})"
}
