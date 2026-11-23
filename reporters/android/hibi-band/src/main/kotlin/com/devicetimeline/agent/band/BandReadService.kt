// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.*
import com.example.healthapp.data.protocol.WearPacketChannel
import com.example.healthapp.data.protocol.auth.AuthHandshake
import com.example.healthapp.data.protocol.fitness.FitnessFileFrame
import com.example.healthapp.data.protocol.fitness.FitnessSync
import com.example.healthapp.data.protocol.fitness.FitnessDataId
import com.example.healthapp.data.protocol.l2.L2Channel
import com.example.healthapp.data.protocol.proto.SleepStatus
import com.example.healthapp.data.protocol.proto.FitnessMessage
import com.example.healthapp.data.protocol.proto.SystemMessage
import com.example.healthapp.data.protocol.proto.WearPacket
import com.example.healthapp.data.protocol.system.DeviceInfoRequest
import com.example.healthapp.data.transport.SppTransportFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect

/** Owns one socket in a dedicated process. No scheduled retries here: Hibi controls cadence. */
class BandReadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stage = "打开蓝牙连接"
    private val added = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var sleepDiagnostic = ""
    private var active = false
    private var recipient: Messenger? = null
    private val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != 1 || message.replyTo == null || active) return
            recipient = message.replyTo
            active = true
            // BluetoothSocket.connect() may ignore coroutine cancellation. A dedicated
            // process gives even a vendor/native socket stall a definite lifetime.
            main.postDelayed({ finish(BandResult(false, "读取超时：$stage", added.get())); endProcess() }, 48_000)
            scope.launch {
                val result = try {
                    withTimeout(45_000) { read() }
                } catch (e: TimeoutCancellationException) {
                    BandResult(false, "读取超时：$stage", added.get())
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: Exception) {
                    BandResult(false, "读取中断：$stage（${e.javaClass.simpleName}）", added.get())
                }
                main.post { finish(result) }
            }
        }
    })
    override fun onBind(intent: Intent?): IBinder = inbox.binder
    private fun finish(result: BandResult) {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        val message = Message.obtain(null, 2).apply {
            data = Bundle().apply { putBoolean("ok", result.ok); putString("status", listOf(result.status, sleepDiagnostic).filter { it.isNotEmpty() }.joinToString("。")); putInt("samples", result.samples) }
        }
        try { recipient?.send(message) } catch (_: RemoteException) { /* Data already committed. */ }
    }
    private fun endProcess() {
        scope.cancel()
        // This service is the sole component in :bandreader. Android owns the main process separately.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        endProcess()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun read(): BandResult = coroutineScope {
        val pair = Pairing.load(this@BandReadService)
        val transport = SppTransportFactory(applicationContext).create()
        val ch = WearPacketChannel(transport, this)
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) return@coroutineScope BandResult(false, "手机蓝牙未开启")
            if (adapter.bondedDevices.none { it.address.equals(pair.address, ignoreCase = true) })
                return@coroutineScope BandResult(false, "系统已配对设备中未找到已保存的手环")
            stage = "打开手环蓝牙连接"
            transport.connect(pair.address)
            BandStore(this@BandReadService).use { store ->
                val cache = java.io.File(noBackupFilesDir, "band-parts").apply { mkdirs() }
                val parts = BandParts(cache, pair.address)
                // Start receiving *before* opening the protocol channel, including files the
                // peer sends spontaneously. Only authenticated data from the expected model is stored.
                val verified = CompletableDeferred<Boolean>()
                val live = CurrentSleepPoll()
                val pollId = java.util.UUID.randomUUID().toString()
                val peerId = java.security.MessageDigest.getInstance("SHA-256").digest(pair.address.toByteArray()).joinToString("") { "%02x".format(it) }
                fun recordLive(observations: List<CurrentSleepPoll.Observation>) {
                    for (observation in observations) {
                        added.addAndGet(store.observeProgress(peerId, pollId, observation.point, observation.at))
                    }
                }
                val known = KnownSleepRequests(noBackupFilesDir, peerId)
                // Remember only IDs supplied by this authenticated band. A valid file's
                // contents can grow after the one-minute snapshot has reached the phone.
                val minutePrefs = getSharedPreferences("hibi-band-minute-ids", MODE_PRIVATE)
                val minuteIDs = RecentMinuteFiles(minutePrefs.getString(peerId, "") ?: "")
                val minuteStatus = java.util.concurrent.atomic.AtomicReference("")
                fun rememberMinutes(ids: Iterable<FitnessDataId>) {
                    synchronized(minuteIDs) {
                        val updated = minuteIDs.add(ids, System.currentTimeMillis() / 1000)
                        if (updated != null) minutePrefs.edit().putString(peerId, updated).apply()
                    }
                }
                val catchup = SleepCatchup(noBackupFilesDir, peerId)
                val collected = java.util.Collections.synchronizedSet(mutableSetOf<FitnessDataId>())
                val indexes = IndexLists()
                val indexUpdates = kotlinx.coroutines.flow.MutableStateFlow(0)
                val indexStatus = java.util.concurrent.atomic.AtomicReference("睡眠索引待查")
                val sleepStatus = java.util.concurrent.atomic.AtomicReference("")
                val liveSleep = java.util.concurrent.atomic.AtomicReference("手环状态未读取")
                val receiveStatus = java.util.concurrent.atomic.AtomicReference("等待文件")
                val got = kotlinx.coroutines.flow.MutableStateFlow(0)
                fun report() {
                    sleepDiagnostic = listOf(liveSleep.get(), indexStatus.get(), receiveStatus.get(), minuteStatus.get(), sleepStatus.get(), "待补睡眠 ${catchup.outstanding()} 份", known.status()).filter { it.isNotEmpty() }.joinToString("，")
                }
                val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
                    ch.incomingL2.collect { event ->
                        if (event.channel != L2Channel.FILE_FITNESS || !verified.await()) return@collect
                        val first = com.example.healthapp.data.protocol.fitness.FitnessChunk.parse(event.payload)
                        if (first?.index == 1 && first.data.size >= FitnessDataId.SIZE+1 && first.data[FitnessDataId.SIZE] == 0.toByte()) {
                            // Already passed SPP authentication, device-model check and L1 frame CRC.
                            // Never assign an ID to anonymous continuation bytes.
                            FitnessDataId.parse(first.data)?.let { id -> known.first(id); catchup.discover(listOf(id)) }
                        }
                        val whole = parts.accept(event.payload)
                        if (whole != null) {
                            val actual = FitnessDataId.parse(whole, 4)
                            val frame = if (actual != null && SleepArchive.accepts(actual)) SleepArchive.envelope(whole) else FitnessFileFrame.parse(whole)
                            if (frame == null) {
                                known.full(actual, null, parts.finishedEntirelyThisRun)
                                parts.unsupported(whole)
                                actual?.let { catchup.persisted(it, SleepCatchup.State.ARCHIVED) }
                            } else {
                                val now = System.currentTimeMillis() / 1000L
                                added.addAndGet(store.store(frame, whole, now))
                                if (FitnessSync.isDailyRecord(frame.dataId)) rememberMinutes(listOf(frame.dataId))
                                recordLive(live.received(frame, parts.finishedEntirelyThisRun, now))
                                if (SleepArchive.accepts(frame.dataId)) {
                                    val interval = BandSleep.decode(frame, now)
                                    known.full(actual, interval, parts.finishedEntirelyThisRun)
                                    val incomplete = FitnessSync.isFullDaySleep(frame.dataId) && frame.body.firstOrNull() == 0.toByte()
                                    catchup.persisted(frame.dataId, when {
                                        interval != null -> SleepCatchup.State.COMPLETED
                                        incomplete -> SleepCatchup.State.UNFINISHED
                                        else -> SleepCatchup.State.ARCHIVED
                                    })
                                    sleepStatus.set(if (interval == null) "型${frame.dataId.typeByte}睡眠原帧已保存，缺少已验证时段" else "睡眠报告含有效起止")
                                }
                                collected.add(frame.dataId)
                                parts.complete()
                                got.value = got.value + 1
                            }
                        }
                        val heads = parts.headCounts.toSortedMap().entries.joinToString("/") { "${it.key}×${it.value}" }
                        val counts = synchronized(collected) { collected.count(FitnessSync::isDailyRecord) to collected.count(SleepArchive::accepts) }
                        receiveStatus.set("收到${parts.events}片，首片类型×次数 $heads，组装${parts.assembled}，CRC错${parts.badCrc}，包头异常${parts.badHeader}，无归属${parts.orphan}，未识别布局${parts.unexpectedLayout}，已存分钟${counts.first}组/睡眠${counts.second}组")
                        report()
                    }
                }
                val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                    ch.closed.first()
                    // The transport already published its last frames into the shared-flow
                    // buffer. Give the subscribed decoder time to commit those bytes locally.
                    delay(350)
                    throw java.io.IOException("band disconnected")
                }
                var pbListener: Job? = null
                try {
                    stage = "建立手环数据通道"
                    ch.start()
                    ch.handshake()
                    stage = "验证已有配对"
                    AuthHandshake(ch, pair.key).run()
                    // Subscribe as soon as the peer is authenticated. Reports can precede
                    // the model reply or a history request. Only commit them after model verification.
                    val status = SleepStatus.Session()
                    pbListener = listenForVerifiedPackets(ch.incoming, verified,
                        accept = { packet ->
                            (packet.type == WearPacket.TYPE_SYSTEM && (packet.id == SleepStatus.REPORT ||
                                (packet.id == SleepStatus.QUERY && status.expectingReply))) ||
                                (packet.type == WearPacket.TYPE_FITNESS &&
                                    (packet.id == FitnessSync.ID_QUERY_TODAY || packet.id == FitnessSync.ID_QUERY_HISTORY))
                        }) { packet, observedAt ->
                        if (indexes.observe(packet)) {
                            val ids = indexes.list(packet.id).orEmpty()
                            catchup.discover(ids)
                            rememberMinutes(ids)
                            if (packet.id == FitnessSync.ID_QUERY_TODAY) recordLive(live.indexed(ids))
                            indexUpdates.value++
                        }
                        if (packet.type != WearPacket.TYPE_SYSTEM ||
                            (packet.id != SleepStatus.QUERY && packet.id != SleepStatus.REPORT)) return@listenForVerifiedPackets
                        val observed = status.observe(packet)
                        val message = when (observed) {
                            SleepStatus.Observed.AWAKE, SleepStatus.Observed.ASLEEP -> {
                                val origin = if (packet.id == SleepStatus.QUERY) "band_basic_status" else "band_status_event"
                                store.storeSleepState(observed == SleepStatus.Observed.ASLEEP, observedAt, origin)
                                "设备报告睡醒状态"
                            }
                            SleepStatus.Observed.NOT_WORN -> "设备报告未佩戴"
                            null -> "没有可用的明确睡醒值"
                        }
                        liveSleep.set("状态回复78=${status.replies}、通知79=${status.reports}，$message")
                        report()
                    }
                    stage = "确认手环型号"
                    val profile = DeviceInfoRequest(ch).fetchInfo()
                    if (profile.model != "M2401B1") return@use BandResult(false, "连接的型号与已导入的手环不符")
                    verified.complete(true)
                    status.requestStarted()
                    liveSleep.compareAndSet("手环状态未读取", "状态回复78=0、通知79=0")
                    report()
                    val stateQuery = launch(start = CoroutineStart.UNDISPATCHED) {
                        ch.send(WearPacket(type = WearPacket.TYPE_SYSTEM, id = SleepStatus.QUERY))
                    }
                    // Send this opt-in exact read early on short connections; no synthetic
                    // IDs and no sleep data from the selector can enter the sample store.
                    val exact = known.next()
                    if (exact != null) {
                        stage = "按已知编号补读睡眠文件"
                        live.requesting(listOf(exact))
                        known.begin(exact)
                        ch.send(WearPacket(type=WearPacket.TYPE_FITNESS, id=FitnessSync.ID_REQUEST_FILES,
                            fitnessRaw=FitnessMessage.encodeIds(exact.toByteArray())))
                        known.sent(exact)
                        report()
                    }
                    // Recover one pending file before spending this short authenticated
                    // connection waiting for two fresh indexes. Continue the normal
                    // minute-and-sleep request flow immediately after queueing this read.
                    val recovery = catchup.due(setOfNotNull(exact), 1).firstOrNull()
                    if (recovery != null) {
                        stage = "补读之前发现的睡眠文件"
                        live.requesting(listOf(recovery))
                        catchup.attempted(listOf(recovery))
                        ch.send(WearPacket(type=WearPacket.TYPE_FITNESS, id=FitnessSync.ID_REQUEST_FILES,
                            fitnessRaw=FitnessMessage.encodeIds(recovery.toByteArray())))
                    }
                    val earlyMinutes = minuteIDs.forRefresh(System.currentTimeMillis() / 1000, emptyList())
                    if (earlyMinutes.isNotEmpty()) {
                        val wire = earlyMinutes.flatMap { it.toByteArray().asIterable() }.toByteArray()
                        ch.send(WearPacket(type=WearPacket.TYPE_FITNESS, id=FitnessSync.ID_REQUEST_FILES,
                            fitnessRaw=FitnessMessage.encodeIds(wire)))
                        minuteStatus.set("已补读最近 ${earlyMinutes.size} 份分钟文件")
                        report()
                    }
                    val keepAlive = launch {
                        while (isActive) {
                            delay(800)
                            ch.send(WearPacket(type = WearPacket.TYPE_SYSTEM, id = SystemMessage.ID_GET_DEVICE_STATUS))
                        }
                    }
                    try {
                        stage = "查询当日健康记录"
                        val issued = mutableSetOf<FitnessDataId>().also { it.addAll(listOfNotNull(exact, recovery)); it.addAll(earlyMinutes) }
                        val sleepIssued = mutableSetOf<FitnessDataId>().also { set -> recovery?.let { set.add(it) } }
                        var today: List<FitnessDataId>? = null
                        var history: List<FitnessDataId>? = null
                        fun refreshIndex() {
                            today = indexes.list(FitnessSync.ID_QUERY_TODAY)
                            history = indexes.list(FitnessSync.ID_QUERY_HISTORY)
                            indexStatus.set("睡眠索引（当日 ${SleepArchive.label(today)}，历史 ${SleepArchive.label(history)}）")
                            report()
                        }
                        // Subscribe above before sending either query. Keep accepting subsequent responses
                        // for the whole connection, including multiple lists under the same message ID.
                        ch.send(FitnessSync.buildTodayIdsQuery())
                        withTimeoutOrNull(3_500) { indexUpdates.first { indexes.list(FitnessSync.ID_QUERY_TODAY) != null } }
                        refreshIndex()
                        stage = "查询历史睡眠索引"
                        ch.send(FitnessSync.buildHistoryIdsQuery())
                        withTimeoutOrNull(2_500) { indexUpdates.first { indexes.list(FitnessSync.ID_QUERY_HISTORY) != null } }
                        refreshIndex()
                        fun selectTargets(): List<FitnessDataId> {
                            val all = (today.orEmpty() + history.orEmpty()).distinct().sortedByDescending { it.timestamp }
                            val minutes = all.filter(FitnessSync::isDailyRecord)
                            val sleepers = catchup.due(issued, SleepCatchup.MAX_PER_CYCLE-sleepIssued.size)
                            // Minute traffic is always eligible, even when an older file never returns.
                            val rolling = minuteIDs.forRefresh(System.currentTimeMillis() / 1000, minutes)
                            return ((minutes.take(1) + sleepers + rolling + minutes.filterNot { store.hasCompleteFrame(it) }.take(4))
                                .distinct().filterNot { it in issued })
                        }
                        suspend fun sendNewTargets(targets: List<FitnessDataId>) {
                            if (targets.isEmpty()) return
                            val ids = ByteArray(targets.size * FitnessDataId.SIZE)
                            targets.forEachIndexed { i,id -> id.toByteArray().copyInto(ids, i * FitnessDataId.SIZE) }
                            live.requesting(targets)
                            sleepIssued.addAll(targets.filter(SleepArchive::accepts))
                            issued.addAll(targets)
                            // Reserve attempts before submit for bounded recovery after an abrupt disconnect.
                            catchup.attempted(targets.filter(SleepArchive::accepts))
                            // The published read command accepts even a single ID in field 2.
                            ch.send(WearPacket(type=WearPacket.TYPE_FITNESS, id=FitnessSync.ID_REQUEST_FILES,
                                fitnessRaw=FitnessMessage.encodeIds(ids)))
                        }
                        // Re-read both snapshots on every list notification. StateFlow replays
                        // its current value, covering a second reply arriving during the send.
                        var processed = -1
                        stage = "读取手环健康文件"
                        withTimeoutOrNull(12_000) {
                            kotlinx.coroutines.flow.combine(got, indexUpdates) { _, n -> n }.first { n ->
                                if (n != processed) {
                                    processed = n
                                    refreshIndex()
                                    val send = selectTargets()
                                    if (send.isNotEmpty()) sendNewTargets(send)
                                    persistSleepIndex(today,history,indexes,issued)
                                }
                                issued.isNotEmpty() && collected.containsAll(issued)
                            }
                        }
                        val count = collected.size
                        when {
                            count > 0 -> BandResult(true, "已保存 $count 组手环文件，新增或更新 ${added.get()} 项", added.get())
                            issued.isEmpty() -> BandResult(today != null && history != null, "本轮没有取得健康文件", added.get())
                            else -> BandResult(false, "未取得完整健康记录，可归属的分片已保存", added.get())
                        }
                    } finally {
                        keepAlive.cancel()
                        withContext(NonCancellable) {
                            stateQuery.cancelAndJoin()
                        }
                    }
                } finally {
                    verified.complete(false)
                    disconnect.cancel()
                    withContext(NonCancellable) {
                        pbListener?.cancelAndJoin()
                        receiver.cancelAndJoin()
                    }
                }
            }
        } finally {
            pair.key.fill(0)
            withContext(NonCancellable) { ch.stop() }
        }
    }

    private fun persistSleepIndex(today: List<FitnessDataId>?, history: List<FitnessDataId>?, indexes: IndexLists, issued: Set<FitnessDataId>) {
        val j = org.json.JSONObject().put("checkedAt", java.time.Instant.now().toString())
        fun entries(items: List<FitnessDataId>?) = items?.let {
            org.json.JSONArray().also { a -> SleepArchive.groups(it)?.forEach { row -> a.put(org.json.JSONObject(row)) } }
        } ?: org.json.JSONObject.NULL
        j.put("today", entries(today)).put("history", entries(history)).put("requestedSleep", entries(issued.toList()))
            .put("todayResponseCount", indexes.messageCount(FitnessSync.ID_QUERY_TODAY)).put("historyResponseCount", indexes.messageCount(FitnessSync.ID_QUERY_HISTORY))
        val file = android.util.AtomicFile(java.io.File(noBackupFilesDir, "sleep-index.json"))
        val stream = file.startWrite()
        try { stream.write(j.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
