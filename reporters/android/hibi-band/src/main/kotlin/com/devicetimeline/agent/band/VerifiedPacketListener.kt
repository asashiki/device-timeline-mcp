// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.proto.WearPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow

/** Subscribe synchronously on an already authenticated channel. Retain the arrival time of
 * early reports while the model check is outstanding; a rejected model never commits any. */
internal fun CoroutineScope.listenForVerifiedPackets(
    incoming: Flow<WearPacket>, verified: Deferred<Boolean>, accept: (WearPacket) -> Boolean,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
    consume: (WearPacket, Long) -> Unit
): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    val packets = Channel<Pair<WearPacket,Long>>(32)
    val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
        if (verified.await()) for ((packet, observedAt) in packets) consume(packet, observedAt)
    }
    try {
        incoming.collect { packet -> if (accept(packet)) packets.send(packet to clock()) }
    } finally {
        packets.close()
        consumer.cancel()
    }
}
