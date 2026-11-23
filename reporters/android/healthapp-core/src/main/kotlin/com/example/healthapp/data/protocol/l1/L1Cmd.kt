package com.example.healthapp.data.protocol.l1

import java.io.ByteArrayOutputStream

/**
 * L1 层的控制报文（CMD）。
 *
 * 这是 SAR（分段与重传）层的握手 —— **必须先完成它，业务数据才会被设备受理**。
 * 不做握手直接发 DATA，设备只会回一个 L1 ACK 然后沉默。
 *
 * 载荷格式（来自 AstroBox 的 layer1cmd.rs）：
 * ```
 * [0]      cmd code
 * 之后是重复的 TLV： [key:u8][len:u16 小端][value: len 字节]
 * ```
 */
object L1Cmd {

    // ---- cmd code ----
    const val CMD_START_REQ = 1
    const val CMD_START_RSP = 2
    const val CMD_STOP_REQ = 3
    const val CMD_STOP_RSP = 4

    // ---- config key ----
    const val CFG_VERSION = 0x01
    const val CFG_MPS = 0x02
    const val CFG_TX_WIN = 0x03
    const val CFG_SEND_TIMEOUT = 0x04
    const val CFG_DEVICE_TYPE = 0x05
    const val CFG_DEVICE_NAME = 0x06
    const val CFG_OS_VERSION = 0x07

    // ---- 官方客户端的默认值（AstroBox 同款）----
    const val DEFAULT_MPS = 64512
    const val DEFAULT_TX_WIN = 32
    const val DEFAULT_SEND_TIMEOUT_MS = 10_000

    class Packet(
        val cmd: Int,
        val config: Map<Int, ByteArray>,
    ) {
        fun version(): Triple<Int, Int, Int>? =
            config[CFG_VERSION]?.takeIf { it.size == 3 }
                ?.let { Triple(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

        fun mps(): Int? = config[CFG_MPS]?.takeIf { it.size == 2 }?.let { u16(it) }

        fun txWin(): Int? = config[CFG_TX_WIN]?.takeIf { it.size == 2 }?.let { u16(it) }

        fun sendTimeout(): Int? = config[CFG_SEND_TIMEOUT]?.takeIf { it.size == 2 }?.let { u16(it) }

        fun deviceType(): Int? = config[CFG_DEVICE_TYPE]?.firstOrNull()?.toInt()

        fun deviceName(): String? =
            config[CFG_DEVICE_NAME]?.toString(Charsets.UTF_8)

        override fun toString(): String =
            "L1Cmd(cmd=$cmd, ver=${version()}, mps=${mps()}, txWin=${txWin()}, timeout=${sendTimeout()})"

        private fun u16(v: ByteArray): Int =
            (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
    }

    /** 构造 START_REQ / START_RSP 这类握手报文 */
    fun buildStartCmd(
        cmd: Int,
        txWin: Int = DEFAULT_TX_WIN,
        sendTimeoutMs: Int = DEFAULT_SEND_TIMEOUT_MS,
        mps: Int = DEFAULT_MPS,
    ): ByteArray {
        val out = ByteArrayOutputStream(32)
        out.write(cmd)

        fun tlv(key: Int, value: ByteArray) {
            out.write(key)
            out.write(value.size and 0xFF)
            out.write((value.size ushr 8) and 0xFF)
            out.write(value, 0, value.size)
        }

        tlv(CFG_VERSION, byteArrayOf(1, 0, 0))
        tlv(CFG_MPS, byteArrayOf((mps and 0xFF).toByte(), ((mps ushr 8) and 0xFF).toByte()))
        tlv(
            CFG_TX_WIN,
            byteArrayOf((txWin and 0xFF).toByte(), ((txWin ushr 8) and 0xFF).toByte()),
        )
        tlv(
            CFG_SEND_TIMEOUT,
            byteArrayOf(
                (sendTimeoutMs and 0xFF).toByte(),
                ((sendTimeoutMs ushr 8) and 0xFF).toByte(),
            ),
        )

        return out.toByteArray()
    }

    /** 解析收到的 CMD 载荷；格式非法返回 null */
    fun parse(payload: ByteArray): Packet? {
        if (payload.isEmpty()) return null
        val cmd = payload[0].toInt() and 0xFF
        val config = mutableMapOf<Int, ByteArray>()

        var i = 1
        while (i + 3 <= payload.size) {
            val key = payload[i].toInt() and 0xFF
            val size = (payload[i + 1].toInt() and 0xFF) or
                ((payload[i + 2].toInt() and 0xFF) shl 8)
            i += 3
            if (i + size > payload.size) break
            config[key] = payload.copyOfRange(i, i + size)
            i += size
        }
        return Packet(cmd, config)
    }
}
