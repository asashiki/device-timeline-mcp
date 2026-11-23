package com.example.healthapp.data.protocol.l1

/**
 * CRC16-ARC：多项式 0xA001（0x8005 的反向形式），初值 0x0000，无最终异或。
 *
 * 注意这是「反射」版本：每字节先与 crc 低 8 位异或，然后右移。
 */
object Crc16 {

    fun arc(data: ByteArray): Int = arc(data, 0, data.size)

    fun arc(data: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range out of bounds: offset=$offset length=$length size=${data.size}"
        }
        var crc = 0x0000
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
