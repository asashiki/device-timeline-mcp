package com.example.healthapp.data.protocol.proto

import java.io.ByteArrayOutputStream

/** protobuf 的 wire type */
internal object WireType {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LEN = 2
    const val FIXED32 = 5
}

/**
 * 最小 protobuf 写入器。
 *
 * 只实现小米协议用得到的类型：varint / bool / bytes / string / 嵌套消息 / float。
 * 语义上按 proto2 的 required 处理 —— 显式调用就一定会写出去，
 * 即使值等于默认值（0 / false / 空）。这一点很重要：
 * CompanionDevice.device_type 的 ANDROID 就是 0，但必须写出来。
 */
internal class ProtoWriter {

    private val out = ByteArrayOutputStream(64)

    fun varint(field: Int, value: Long): ProtoWriter = apply {
        writeTag(field, WireType.VARINT)
        writeVarint(value)
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        writeTag(field, WireType.LEN)
        writeVarint(value.size.toLong())
        out.write(value, 0, value.size)
    }

    fun string(field: Int, value: String): ProtoWriter =
        bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ByteArray): ProtoWriter = bytes(field, value)

    fun float(field: Int, value: Float): ProtoWriter = apply {
        writeTag(field, WireType.FIXED32)
        val bits = value.toRawBits()
        out.write(bits and 0xFF)
        out.write((bits ushr 8) and 0xFF)
        out.write((bits ushr 16) and 0xFF)
        out.write((bits ushr 24) and 0xFF)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeTag(field: Int, wireType: Int) {
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/**
 * 最小 protobuf 读取器。
 *
 * 用法：
 * ```
 * val r = ProtoReader(bytes)
 * while (r.hasNext()) {
 *     when (r.nextField()) {
 *         FIELD_A -> a = r.readVarint()
 *         FIELD_B -> b = r.readBytes()
 *         else -> r.skip()
 *     }
 * }
 * ```
 */
internal class ProtoReader(
    private val buf: ByteArray,
    private var pos: Int = 0,
    private val limit: Int = buf.size,
) {

    var lastField: Int = 0
        private set

    var lastWireType: Int = 0
        private set

    fun hasNext(): Boolean = pos < limit

    /** 读一个 tag，返回字段号；wire type 通过 [lastWireType] 暴露 */
    fun nextField(): Int {
        val tag = readVarint()
        lastField = (tag ushr 3).toInt()
        lastWireType = (tag and 0x7L).toInt()
        return lastField
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < limit) { "varint 被截断：pos=$pos limit=$limit" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint 过长（超过 64 位）" }
        }
    }

    fun readBool(): Boolean = readVarint() != 0L

    fun readBytes(): ByteArray {
        val len = readVarint()
        require(len >= 0 && len <= Int.MAX_VALUE) { "非法的长度前缀：$len" }
        val n = len.toInt()
        require(pos + n <= limit) { "length-delimited 越界：len=$n pos=$pos limit=$limit" }
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readFloat(): Float {
        require(pos + 4 <= limit) { "float 被截断" }
        val bits = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return Float.fromBits(bits)
    }

    /** 跳过当前字段（兼容未来新增字段） */
    fun skip() {
        when (lastWireType) {
            WireType.VARINT -> readVarint()
            WireType.LEN -> readBytes()
            WireType.FIXED32 -> {
                require(pos + 4 <= limit) { "fixed32 被截断" }
                pos += 4
            }
            WireType.FIXED64 -> {
                require(pos + 8 <= limit) { "fixed64 被截断" }
                pos += 8
            }
            else -> throw IllegalStateException("不支持的 wire type: $lastWireType (field=$lastField)")
        }
    }
}
