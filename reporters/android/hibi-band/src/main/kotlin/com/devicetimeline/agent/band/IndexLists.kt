// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessDataId
import com.example.healthapp.data.protocol.fitness.FitnessSync
import com.example.healthapp.data.protocol.proto.WearPacket

/** A read-only union of lists for this authenticated connection. The device has
 * separate TODAY and PAST replies. Neither request defines a pagination cursor. */
internal class IndexLists {
    private val lists = mutableMapOf<Int, LinkedHashSet<FitnessDataId>>()
    private val messages = mutableMapOf<Int, Int>()
    @Synchronized fun observe(packet: WearPacket): Boolean {
        if (packet.type != WearPacket.TYPE_FITNESS || packet.id !in setOf(FitnessSync.ID_QUERY_TODAY,FitnessSync.ID_QUERY_HISTORY) ||
            (packet.errorCode != null && packet.errorCode != 0)) return false
        val parsed = runCatching { FitnessSync.parseIdsReply(packet.fitnessRaw) }.getOrNull() ?: return false
        messages[packet.id] = (messages[packet.id] ?: 0) + 1
        lists.getOrPut(packet.id) { linkedSetOf() }.addAll(parsed)
        return true
    }
    @Synchronized fun list(which: Int): List<FitnessDataId>? = lists[which]?.toList()
    @Synchronized fun messageCount(which: Int) = messages[which] ?: 0
}
