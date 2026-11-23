package com.example.healthapp.data.protocol.l1

/**
 * L1 帧编解码。
 *
 * 线上格式（全部小端）：
 * ```
 * offset  size  field
 * 0       2     magic = 0xA5A5
 * 2       1     type | frx   (低 4 位 type，bit4 = frx)
 * 3       1     seq
 * 4       2     length (payload 字节数)
 * 6       2     crc16 = CRC16-ARC(payload)
 * 8       N     payload
 * ```
 */
object L1Codec {

    const val MAGIC = 0xA5A5
    const val HEADER_SIZE = 8

    private const val TYPE_MASK = 0x0F
    private const val FRX_MASK = 0x10

    fun encode(frame: L1Frame): ByteArray {
        val payload = frame.payload
        val out = ByteArray(HEADER_SIZE + payload.size)

        out[0] = (MAGIC and 0xFF).toByte()
        out[1] = ((MAGIC ushr 8) and 0xFF).toByte()

        var tf = frame.type.code and TYPE_MASK
        if (frame.frx) tf = tf or FRX_MASK
        out[2] = tf.toByte()

        out[3] = frame.seq.toByte()

        val len = payload.size
        out[4] = (len and 0xFF).toByte()
        out[5] = ((len ushr 8) and 0xFF).toByte()

        val crc = Crc16.arc(payload)
        out[6] = (crc and 0xFF).toByte()
        out[7] = ((crc ushr 8) and 0xFF).toByte()

        System.arraycopy(payload, 0, out, HEADER_SIZE, len)
        return out
    }

    /**
     * 从 [buf] 的 [offset] 处解出一帧。
     *
     * @param available 可读字节数（默认到 buf 末尾）
     * @return 解出的帧；数据不足时返回 null（表示需要继续读）
     * @throws IllegalArgumentException magic 错误、类型非法或 CRC 不匹配
     */
    fun decode(buf: ByteArray, offset: Int = 0, available: Int = buf.size - offset): L1Frame? {
        if (available < HEADER_SIZE) return null

        val magic = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        require(magic == MAGIC) { "bad magic: 0x%04X".format(magic) }

        val tf = buf[offset + 2].toInt() and 0xFF
        val type = L1Type.fromCode(tf and TYPE_MASK)
        val frx = (tf and FRX_MASK) != 0
        val seq = buf[offset + 3].toInt() and 0xFF
        val len = (buf[offset + 4].toInt() and 0xFF) or ((buf[offset + 5].toInt() and 0xFF) shl 8)
        val declaredCrc = (buf[offset + 6].toInt() and 0xFF) or
            ((buf[offset + 7].toInt() and 0xFF) shl 8)

        if (available < HEADER_SIZE + len) return null

        val payload = buf.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + len)
        val computed = Crc16.arc(payload)
        require(computed == declaredCrc) {
            "CRC mismatch: declared=0x%04X computed=0x%04X".format(declaredCrc, computed)
        }

        return L1Frame(type, frx, seq, payload)
    }
}
