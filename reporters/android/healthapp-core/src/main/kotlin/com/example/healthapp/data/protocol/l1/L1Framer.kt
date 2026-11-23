package com.example.healthapp.data.protocol.l1

/**
 * 把 SPP 的字节流切成完整的 L1 帧。
 *
 * SPP 是流式的：一次 read 可能拿到半帧，也可能一次拿到好几帧。
 * 这个类负责缓存不完整的尾巴，等下次 feed 补齐。
 */
class L1Framer {

    private var buf = ByteArray(4096)
    private var size = 0

    /** 喂入一段字节，返回本次能解出的所有完整帧 */
    fun feed(chunk: ByteArray): List<L1Frame> {
        append(chunk)

        val frames = mutableListOf<L1Frame>()
        while (true) {
            if (size < L1Codec.HEADER_SIZE) break

            val len = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
            val total = L1Codec.HEADER_SIZE + len
            if (size < total) break

            val frame = L1Codec.decode(buf, 0, size) ?: break
            frames += frame
            consume(total)
        }
        return frames
    }

    /** 清空缓存（断线重连时调用） */
    fun reset() {
        size = 0
    }

    /** 当前缓存里未消费的字节数，便于调试 */
    val pending: Int get() = size

    private fun append(chunk: ByteArray) {
        if (size + chunk.size > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + chunk.size))
        }
        System.arraycopy(chunk, 0, buf, size, chunk.size)
        size += chunk.size
    }

    private fun consume(n: Int) {
        if (n >= size) {
            size = 0
            return
        }
        System.arraycopy(buf, n, buf, 0, size - n)
        size -= n
    }
}
