// SPDX-License-Identifier: AGPL-3.0-only
package com.example.healthapp.data.protocol.proto

/** Xiaomi wear_system.proto: SYSTEM 78 / Present (48), SYSTEM 79 / Report (49).
 * Neither message has a device timestamp. A fresh, authenticated phone observation
 * may be timestamped on receipt; wear state is scoped to one live connection.
 */
object SleepStatus {
    const val QUERY = 78
    const val REPORT = 79

    enum class Observed { AWAKE, ASLEEP, NOT_WORN }
    data class Signal(val wearing: Boolean?, val asleep: Boolean?)

    private fun decode(packet: WearPacket): Signal? {
        if (packet.type != WearPacket.TYPE_SYSTEM || packet.id !in setOf(QUERY, REPORT) ||
            (packet.errorCode != null && packet.errorCode != 0)) return null
        val raw = packet.systemRaw ?: return null
        val present = packet.id == QUERY
        val wanted = if (present) 48 else 49
        return runCatching {
            val outer = ProtoReader(raw)
            var signal: Signal? = null
            while (outer.hasNext()) {
                if (outer.nextField() == wanted) {
                    require(signal == null && outer.lastWireType == WireType.LEN)
                    signal = fields(outer.readBytes(), present)
                } else outer.skip()
            }
            signal
        }.getOrNull()
    }

    private fun fields(raw: ByteArray, present: Boolean): Signal {
        val reader = ProtoReader(raw)
        var wearing: Boolean? = null
        var asleep: Boolean? = null
        fun flag(): Boolean {
            require(reader.lastWireType == WireType.VARINT)
            val value = reader.readVarint()
            // Present uses protobuf bools (0/1). Report uses ON/IN=1, OFF/OUT=2.
            require(if (present) value in 0L..1L else value in 1L..2L)
            return value == 1L
        }
        while (reader.hasNext()) {
            when (reader.nextField()) {
                if (present) 3 else 2 -> { require(wearing == null); wearing = flag() }
                if (present) 4 else 3 -> { require(asleep == null); asleep = flag() }
                else -> reader.skip()
            }
        }
        return Signal(wearing, asleep)
    }

    /** Start a new instance only after verifying the device model on an authenticated socket. */
    class Session {
        var replies = 0; private set
        var reports = 0; private set
        private var wornInSession: Boolean? = null
        @Volatile var expectingReply = false; private set

        fun requestStarted() { expectingReply = true }

        fun observe(packet: WearPacket): Observed? {
            if (packet.type != WearPacket.TYPE_SYSTEM) return null
            when (packet.id) {
                QUERY -> if (expectingReply) replies++ else return null
                REPORT -> reports++
                else -> return null
            }
            if (packet.errorCode != null && packet.errorCode != 0) return null
            val signal = decode(packet)
            if (signal == null) { wornInSession = null; return null }
            if (packet.id == QUERY || signal.wearing != null) wornInSession = signal.wearing
            if (wornInSession == false) return Observed.NOT_WORN
            if (wornInSession != true) return null
            return when (signal.asleep) { true -> Observed.ASLEEP; false -> Observed.AWAKE; null -> null }
        }
    }
}
