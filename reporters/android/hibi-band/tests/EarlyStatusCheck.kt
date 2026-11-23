// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.proto.WearPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

fun main() = runBlocking {
    for (acceptModel in listOf(true, false)) {
        val wire = MutableSharedFlow<WearPacket>(extraBufferCapacity=64)
        val model = CompletableDeferred<Boolean>()
        val observations = mutableListOf<Long>()
        var now = 11L
        val listener = listenForVerifiedPackets(wire, model, { it.id == 79 }, { now }) { _, at -> observations += at }
        check(wire.subscriptionCount.value == 1)
        val report = WearPacket(type=2,id=79)
        wire.emit(report); yield()
        now = 12L
        wire.emit(report); yield()
        check(observations.isEmpty())
        now = 20L
        model.complete(acceptModel); yield()
        check(observations == if (acceptModel) listOf(11L,12L) else emptyList())
        listener.cancelAndJoin()
    }
    println("PASS: two early reports keep their separate observation times and require verified model.")
}
