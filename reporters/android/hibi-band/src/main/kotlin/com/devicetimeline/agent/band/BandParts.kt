// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessChunk
import com.example.healthapp.data.protocol.fitness.FitnessDataId
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** Each run must see its own matching first fragment. Untagged continuations do not
 * establish an identity. Reuse partial chunks only for the same peer, data ID, full
 * first-fragment SHA256 and declared part count, verifying the band's CRC at the end. */
internal class BandParts(private val cache: File, peer: String) {
    private val peerTag = digest(peer.toByteArray())
    private class FileRun(val id: FitnessDataId, val total: Int, val bucket: File) {
        val thisRun = mutableSetOf<Int>()
    }
    // Unlike a continuation, a valid first fragment contains a complete data ID.
    // Different declared lengths cannot share continuation packets. For each
    // length, the next file start supersedes the preceding file's routing context.
    private val byTotal = mutableMapOf<Int, FileRun>()
    private var finished: FileRun? = null
    val headCounts = mutableMapOf<Int, Int>()
    var events = 0; private set
    var badHeader = 0; private set
    var unmatched = 0; private set
    var orphan = 0; private set
    var assembled = 0; private set
    var badCrc = 0; private set
    var maxUnique = 0; private set
    var maxDeclared = 0; private set
    var maxIndex = 0; private set
    var firstSeen = 0; private set
    var leadingVersion = -1; private set
    var leadingType = -1; private set
    var unexpectedLayout = 0; private set

    fun accept(payload: ByteArray): ByteArray? {
        events++
        if (payload.size > 65_536) { badHeader++; return null }
        val chunk = FitnessChunk.parse(payload)
        if (chunk == null) { badHeader++; return null }
        maxDeclared = maxOf(maxDeclared, chunk.total)
        maxIndex = maxOf(maxIndex, chunk.index)
        if (chunk.index == 1) {
            firstSeen++
            byTotal.remove(chunk.total)
            val id = FitnessDataId.parse(chunk.data)
            if (id == null) { badHeader++; return null }
            leadingVersion = id.version
            leadingType = id.typeByte
            headCounts[id.typeByte] = (headCounts[id.typeByte] ?: 0) + 1
            if (id.typeByte != 0 && !SleepArchive.accepts(id)) return null
            val name = "$peerTag-${digest(id.toByteArray())}-${chunk.total}-${digest(chunk.data)}"
            val bucket = File(cache, name).apply { mkdirs() }
            byTotal[chunk.total] = FileRun(id, chunk.total, bucket)
        }
        val run = byTotal[chunk.total]
        if (run == null) { orphan++; return null }
        val bucket = run.bucket
        val count = run.total
        run.thisRun.add(chunk.index)
        val part = File(bucket, chunk.index.toString())
        if (!part.exists() || !part.readBytes().contentEquals(chunk.data)) {
            val tmp = File(bucket, "writing")
            tmp.writeBytes(chunk.data)
            check(tmp.renameTo(part))
        }
        val files = (1..count).map { File(bucket, it.toString()) }
        val available = files.count { it.isFile }
        maxUnique = maxOf(maxUnique, available)
        if (available != count) return null
        // Only join after all expected indices exist. Every byte, including the embedded
        // ID, is protected by the trailing CRC. Do not emit an unverified candidate.
        val length = files.sumOf { it.length() }
        if (length !in 11L..4_194_304L) { badHeader++; return null }
        val full = ByteArray(4 + length.toInt()); full[0] = 1; full[2] = 1
        var pos = 4
        for (f in files) { val bytes = f.readBytes(); bytes.copyInto(full, pos); pos += bytes.size }
        assembled++
        val tail = full.size - 4
        var expected = 0L
        for (i in 0..3) expected = expected or ((full[tail+i].toLong() and 255L) shl (8*i))
        if (CRC32().apply { update(full, 4, tail-4) }.value != expected) {
            badCrc++
            // Conflicting past contents are not a valid file. Keep newly received
            // fragments; replace the others on a future full, matching retransmission.
            files.filterIndexed { i, _ -> i+1 !in run.thisRun }.forEach { it.delete() }
            return null
        }
        if (FitnessDataId.parse(full, 4) != run.id) { unmatched++; return null }
        finished = run
        return full
    }
    val finishedEntirelyThisRun: Boolean get() = finished?.let { it.thisRun.size == it.total } ?: false
    fun unsupported(bytes: ByteArray) {
        unexpectedLayout++
        // Already verified whole-file CRC, identifier, and transport peer. Keep
        // the file privately when the higher-level schema parser does not recognize it.
        val out = File(cache, "verified-${peerTag}-${digest(bytes)}.frame")
        if (!out.exists()) { val tmp = File(cache,"verified.tmp"); tmp.writeBytes(bytes); check(tmp.renameTo(out)) }
        complete()
    }
    fun complete() {
        val run = finished ?: return
        run.bucket.deleteRecursively()
        if (byTotal[run.total] === run) byTotal.remove(run.total)
        finished = null
    }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
