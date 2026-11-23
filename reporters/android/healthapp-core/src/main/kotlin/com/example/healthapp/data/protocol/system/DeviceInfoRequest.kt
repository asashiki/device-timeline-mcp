package com.example.healthapp.data.protocol.system

import com.example.healthapp.data.protocol.MiWearLog
import com.example.healthapp.data.protocol.WearPacketChannel
import com.example.healthapp.data.protocol.proto.Battery
import com.example.healthapp.data.protocol.proto.DeviceInfo
import com.example.healthapp.data.protocol.proto.DeviceStatus
import com.example.healthapp.data.protocol.proto.SystemMessage
import com.example.healthapp.data.protocol.proto.SystemPayload
import com.example.healthapp.data.protocol.proto.WearPacket

/**
 * 读取设备信息与状态。
 *
 * 请求报文只有 `type=SYSTEM(2)` 和 `id=<SystemId>`，**不带 payload**；
 * 响应报文在 `System.payload` 的 oneof 里带 DeviceInfo / DeviceStatus。
 *
 * SystemId（来自 wear_system.proto）：
 * - `GET_DEVICE_STATUS = 1` → 电量
 * - `GET_DEVICE_INFO   = 2` → 型号 / 固件 / 序列号
 */
class DeviceInfoRequest(private val channel: WearPacketChannel) {

    /** 一次拿到的完整快照 */
    data class Snapshot(
        val info: DeviceInfo?,
        val battery: Battery?,
    )

    suspend fun fetchInfo(timeoutMs: Long = TIMEOUT_MS): DeviceInfo {
        MiWearLog.i("请求设备信息 (SystemId=${SystemMessage.ID_GET_DEVICE_INFO})")

        val reply = channel.request(
            WearPacket(
                type = WearPacket.TYPE_SYSTEM,
                id = SystemMessage.ID_GET_DEVICE_INFO,
                // 请求不带 payload
            ),
            timeoutMs = timeoutMs,
            predicate = { isSystemReply(it, SystemMessage.ID_GET_DEVICE_INFO) },
        )

        val payload = decodeReply(reply, SystemMessage.ID_GET_DEVICE_INFO)
        return payload as? DeviceInfo
            ?: throw IllegalStateException("设备信息响应类型不符：$payload")
    }

    suspend fun fetchStatus(timeoutMs: Long = TIMEOUT_MS): DeviceStatus {
        MiWearLog.i("请求设备状态 (SystemId=${SystemMessage.ID_GET_DEVICE_STATUS})")

        val reply = channel.request(
            WearPacket(
                type = WearPacket.TYPE_SYSTEM,
                id = SystemMessage.ID_GET_DEVICE_STATUS,
            ),
            timeoutMs = timeoutMs,
            predicate = { isSystemReply(it, SystemMessage.ID_GET_DEVICE_STATUS) },
        )

        val payload = decodeReply(reply, SystemMessage.ID_GET_DEVICE_STATUS)
        return payload as? DeviceStatus
            ?: throw IllegalStateException("设备状态响应类型不符：$payload")
    }

    /**
     * 依次取信息和电量。
     *
     * 两个请求串行发 —— 设备对并发请求的处理行为未验证，串行更安全。
     * 任一项失败不影响另一项。
     */
    suspend fun fetchAll(): Snapshot {
        val info = runCatching { fetchInfo() }
            .onFailure { MiWearLog.e("读取设备信息失败: ${it.message}") }
            .getOrNull()

        val status = runCatching { fetchStatus() }
            .onFailure { MiWearLog.e("读取设备状态失败: ${it.message}") }
            .getOrNull()

        return Snapshot(info, status?.battery)
    }

    private fun isSystemReply(packet: WearPacket, expectedId: Int): Boolean {
        if (packet.type != WearPacket.TYPE_SYSTEM) return false
        if (packet.id != expectedId) return false
        return packet.systemRaw != null && packet.systemRaw.isNotEmpty()
    }

    private fun decodeReply(packet: WearPacket, systemId: Int): SystemPayload {
        val raw = packet.systemRaw
            ?: throw IllegalStateException("SystemId=$systemId 的响应没有 payload")

        val payload = SystemMessage.decode(raw)
        MiWearLog.i("设备响应 SystemId=$systemId → $payload")
        return payload
    }

    companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
