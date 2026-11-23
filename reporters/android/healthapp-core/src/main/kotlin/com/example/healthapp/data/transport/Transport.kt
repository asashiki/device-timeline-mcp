package com.example.healthapp.data.transport

import kotlinx.coroutines.flow.Flow

/**
 * 传输层抽象。
 *
 * 第一期只有 SPP 实现；将来加 BLE 时上层零改动。
 */
interface Transport {

    suspend fun connect(address: String)

    suspend fun send(bytes: ByteArray)

    /** 持续产出收到的字节块（可能是半帧，也可能是多帧） */
    fun receive(): Flow<ByteArray>

    suspend fun close()

    val isConnected: Boolean
}
