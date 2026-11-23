// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessDataId
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small journal for one authenticated model and peer, in app-private storage. IDs originate
 * only from valid index responses or identified first fragments of the active transport. */
internal class SleepCatchup(directory: File, peerTag: String, private val clock: () -> Long = { System.currentTimeMillis()/1000L }) {
    enum class State { MISSING, UNFINISHED, COMPLETED, ARCHIVED }
    private data class Entry(val id: FitnessDataId, var state: State, var tries: Int, var next: Long)
    private val file = File(File(directory,"sleep-catchup-n67cn").apply { mkdirs() }, "$peerTag.bin")
    private val entries = linkedMapOf<FitnessDataId, Entry>()
    init {
        require(peerTag.matches(Regex("[a-f0-9]{64}")))
        if (file.isFile) DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            check(input.readInt()==1)
            val count=input.readInt(); check(count in 0..LIMIT)
            repeat(count) {
                val wire=ByteArray(FitnessDataId.SIZE); input.readFully(wire)
                val id=checkNotNull(FitnessDataId.parse(wire)); val state=State.entries[input.readUnsignedByte()]
                entries[id]=Entry(id,state,input.readInt(),input.readLong())
            }
            check(input.read()==-1)
        }
        if (prune()) save()
    }
    private fun acceptable(id: FitnessDataId, now: Long) = SleepArchive.accepts(id) && id.version in 1..255 &&
        id.timestamp in (now - RETENTION)..now && (id.tzIn15Min in 0..56 || id.tzIn15Min in 208..255)
    private fun prune(): Boolean {
        val now=clock()
        return entries.entries.removeAll { !acceptable(it.key,now) }
    }
    @Synchronized fun discover(ids: Iterable<FitnessDataId>) {
        var dirty=prune(); val now=clock()
        for (id in ids) {
            if (!acceptable(id,now) || entries.containsKey(id)) continue
            if (entries.size>=LIMIT) {
                // Age out the oldest finished entry first. In the unlikely event that every
                // slot is pending, retain the most recent fortnight's newest candidates.
                val oldest=entries.values.filter { it.state==State.COMPLETED || it.state==State.ARCHIVED }.minByOrNull { it.id.timestamp }
                    ?: entries.values.minByOrNull { it.id.timestamp }
                if (oldest==null || oldest.id.timestamp>=id.timestamp) continue
                entries.remove(oldest.id)
            }
            entries[id]=Entry(id,State.MISSING,0,now); dirty=true
        }
        if (dirty) save()
    }
    @Synchronized fun due(excluding: Set<FitnessDataId>, limit: Int): List<FitnessDataId> {
        val now=clock()
        return entries.values.asSequence().filter {
            acceptable(it.id,now) && (it.state==State.MISSING || it.state==State.UNFINISHED) && it.id !in excluding && it.next<=now
        }.sortedWith(compareBy<Entry> { it.tries }.thenBy { it.next }.thenByDescending { it.id.timestamp })
            .take(limit.coerceIn(0,MAX_PER_CYCLE)).map { it.id }.toList()
    }
    @Synchronized fun attempted(ids: Iterable<FitnessDataId>) {
        var dirty=false; val now=clock()
        for (id in ids) {
            val e=entries[id] ?: continue
            if (e.state!=State.MISSING && e.state!=State.UNFINISHED) continue
            e.tries=(e.tries+1).coerceAtMost(16)
            e.next=now + minOf(6*3600L, 4*60L*(1L shl (e.tries-1).coerceAtMost(7)))
            dirty=true
        }
        if (dirty) save()
    }
    /** Called after full CRC verification and successful artifact / DB persistence. */
    @Synchronized fun persisted(id: FitnessDataId, state: State) {
        val now=clock()
        if (!acceptable(id,now)) return
        discover(listOf(id))
        val e=entries[id] ?: return
        if (e.state==State.COMPLETED && state!=State.COMPLETED) return // delayed incomplete duplicate
        // A recent, valid open report changes throughout the night. Compare it again
        // next polling cycle, even if its state and archive ID have not changed.
        val live = state == State.UNFINISHED && id.timestamp >= now - 86_400
        if (e.state == state && !live) return
        e.state = state
        if (live) { e.tries = 0; e.next = now + 4*60 }
        save()
    }
    @Synchronized fun outstanding(): Int = entries.values.count { it.state==State.MISSING || it.state==State.UNFINISHED }
    private fun save() {
        val temp=File(file.parentFile, file.name+".tmp")
        FileOutputStream(temp).use { stream ->
            val out=DataOutputStream(BufferedOutputStream(stream)); out.writeInt(1); out.writeInt(entries.size)
            entries.values.forEach { e ->
                out.write(e.id.toByteArray()); out.writeByte(e.state.ordinal); out.writeInt(e.tries); out.writeLong(e.next)
            }
            out.flush(); stream.fd.sync()
        }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        const val MAX_PER_CYCLE=3
        private const val RETENTION=14L*86_400
        private const val LIMIT=512
    }
}
