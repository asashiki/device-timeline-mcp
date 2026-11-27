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
import com.example.healthapp.data.protocol.proto.WearPacket
import com.example.healthapp.data.protocol.system.DeviceInfoRequest
import com.example.healthapp.data.transport.SppTransportFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock

/** Owns the authenticated session, five-minute collection cycles and wrist controls. */
class BandReadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stage = "打开蓝牙连接"
    private val added = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var sleepDiagnostic = ""

    private val watchdog = Runnable { sendState("连接超时，准备重连"); endProcess() }
    private var runner: Job? = null
    private var owner: Messenger? = null
    private val session = MutableStateFlow<WearPacketChannel?>(null)
    private val shortConnections = MutableStateFlow(false)
    private val commands = MutableStateFlow(0)
    private var nextReadAt = 0L // elapsedRealtime; only maintain's coroutine updates this
    private fun countCommand(delta: Int) = synchronized(commands) { commands.value += delta }

    private val controlLock = kotlinx.coroutines.sync.Mutex()
    private var connectionAt = 0L
    private fun deliverPower(battery:com.example.healthapp.data.protocol.proto.Battery) {
        val whenRead=System.currentTimeMillis()
        val mode=when(battery.chargeStatus){1->"charging";2->"discharging";3->"full";else->"unknown"}
        val message=Message.obtain(null,5).apply{data=Bundle().apply{
            putInt("percent",battery.capacity);putLong("at",whenRead);putString("mode",mode)
        }}
        runCatching{owner?.send(message)}
    }

    private fun sendState(text: String, samples: Int = 0, connected: Boolean = false, diagnostic: String = "", standby: Boolean = false) {
        try { owner?.send(Message.obtain(null,2).apply {
            data = Bundle().apply {putString("status",text);putInt("samples",samples);putBoolean("ok",connected)
                putLong("connectedAt",if(connected)connectionAt else 0);putString("diagnostic",diagnostic);putBoolean("standby",standby)}
        }) } catch (_: RemoteException) { }
    }
    private fun command(msg: Message) {
        val answer=msg.replyTo ?: return
        val requestId=msg.data.getString("id") ?: return
        val action=msg.data.getString("action") ?: return
        val raw=msg.data.getString("args") ?: "{}"
        countCommand(1)
        scope.launch {
            val result=try {
                val args = org.json.JSONObject(raw)
                val expires = args.optLong("__expiresAt", Long.MAX_VALUE)
                args.remove("__expiresAt")
                // Only maintain opens sockets. Await authentication first, then hold the
                // same lock as a planned release for the entire action (including find-stop).
                val budget = if (action == "wake-ring") 35_000L else 12_000L
                withTimeout(22_000L + budget) {
                    suspend fun dispatch(): ControlResult {
                        while (true) {
                            if (System.currentTimeMillis() >= expires) return ControlResult("expired", "指令已过期")
                            val ch = session.first { it != null } ?: continue
                            val reply = controlLock.withLock {
                                when {
                                    session.value !== ch -> null
                                    System.currentTimeMillis() >= expires -> ControlResult("expired", "指令已过期")
                                    else -> withTimeout(budget) { BandActions.execute(applicationContext, ch, action, args) }
                                }
                            }
                            if (reply != null) return reply
                        }
                    }
                    dispatch()
                }
            } catch(e:TimeoutCancellationException){ControlResult("unconfirmed","连接或回执超时")}
              catch(e:CancellationException){throw e}
              catch(e:Exception){ControlResult("failed","指令未完成")}
              finally { countCommand(-1) }
            runCatching {answer.send(Message.obtain(null,4).apply { data=Bundle().apply {
                putString("id",requestId);putString("status",result.status);putString("detail",result.detail)
            } })}
        }
    }
    private val inbox=Messenger(object:Handler(Looper.getMainLooper()){
        override fun handleMessage(msg:Message){
            if(msg.what==3){ command(msg);return }
            if(msg.what!=1 || msg.replyTo==null)return
            owner=msg.replyTo
            shortConnections.value = msg.data.getBoolean("suspendBetweenSyncs", false)
            if(runner?.isActive==true)return
            runner=scope.launch { maintain() }
        }
    })
    override fun onBind(intent:Intent?) = inbox.binder
    private fun endProcess(){ scope.cancel();android.os.Process.killProcess(android.os.Process.myPid()) }
    override fun onDestroy(){ main.removeCallbacksAndMessages(null);super.onDestroy();endProcess() }
    private suspend fun maintain(){
        var backoff=5
        while(currentCoroutineContext().isActive){
            var ready=false
            try {
                val remaining = nextReadAt - SystemClock.elapsedRealtime()
                if (shortConnections.value && remaining > 0 && commands.value == 0) {
                    sendState("按需连接 · 等待下次同步", standby = true)
                    withTimeoutOrNull(remaining) {
                        combine(shortConnections, commands) { short, n -> !short || n > 0 }.first { it }
                    }
                }
                connectAndRun { ready=true;backoff=5 }
                // Intentional release has already closed the socket. Preserve the next scheduled read.
                continue
            } catch(e:TimeoutCancellationException) {sendState("连接超时 · "+stage)}
              catch(e:CancellationException) {throw e}
              catch(e:Exception) {
                val detail=when {
                    e.message?.contains("HMAC") == true -> "手环拒绝现有配对资料"
                    e is AuthHandshake.AuthException -> "手环配对验证失败"
                    else -> stage+"中断"
                }
                sendState(detail+"，"+backoff+" 秒后重连")
              } finally {session.value=null;main.removeCallbacks(watchdog)}
            delay(backoff*1000L)
            if(!ready)backoff=(backoff*2).coerceAtMost(60)
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun connectAndRun(ready:()->Unit) = coroutineScope {
        val pair=Pairing.load(this@BandReadService)
        val transport=SppTransportFactory(applicationContext).create()
        val ch=WearPacketChannel(transport,this)
        main.postDelayed(watchdog,48000)
        try {
            val adapter=getSystemService(BluetoothManager::class.java)?.adapter
            check(adapter?.isEnabled==true){"蓝牙未开启"}
            check(adapter!!.bondedDevices.any{it.address.equals(pair.address,true)}){"未配对"}
            stage="蓝牙";sendState("正在连接手环")
            transport.connect(pair.address)
            val lost=launch(start=CoroutineStart.UNDISPATCHED){ch.closed.first();throw java.io.IOException("socket closed")}
            val fatal=launch(start=CoroutineStart.UNDISPATCHED){ch.fatal.first();throw java.io.IOException("channel stalled")}
            try {
                stage="建立数据通道";ch.start();ch.handshake()
                stage="验证配对";AuthHandshake(ch,pair.key).run()
                runSession(ch, pair, ready)
            } finally {session.value=null;lost.cancel();fatal.cancel()}
        } finally {
            main.removeCallbacks(watchdog);pair.key.fill(0)
            withContext(NonCancellable){ch.stop()}
        }
    }
    private class Round {
        val live = CurrentSleepPoll()
        val id = java.util.UUID.randomUUID().toString()
        val indexes = IndexLists()
        val collected = java.util.Collections.synchronizedSet(mutableSetOf<FitnessDataId>())
        val deliveries = java.util.concurrent.ConcurrentHashMap<FitnessDataId, Int>()
        val fileUpdates = kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    private suspend fun runSession(ch: WearPacketChannel, pair: Pairing, ready: () -> Unit): Unit = coroutineScope {
            BandStore(this@BandReadService).use { store ->
                val cache = java.io.File(noBackupFilesDir, "band-parts").apply { mkdirs() }
                val parts = BandParts(cache, pair.address)
                // Subscribe before checking the model, including files the
                // peer sends spontaneously. Only authenticated data from the expected model is stored.
                val verified = CompletableDeferred<Boolean>()
                val activeRound = java.util.concurrent.atomic.AtomicReference(Round())
                val peerId = java.security.MessageDigest.getInstance("SHA-256").digest(pair.address.toByteArray()).joinToString("") { "%02x".format(it) }
                fun recordLive(round: Round, observations: List<CurrentSleepPoll.Observation>) {
                    for (observation in observations) {
                        added.addAndGet(store.observeProgress(peerId, round.id, observation.point, observation.at))
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
                val indexUpdates = kotlinx.coroutines.flow.MutableStateFlow(0)
                val indexStatus = java.util.concurrent.atomic.AtomicReference("睡眠索引待查")
                val sleepStatus = java.util.concurrent.atomic.AtomicReference("")
                val liveSleep = java.util.concurrent.atomic.AtomicReference("手环状态未读取")
                val receiveStatus = java.util.concurrent.atomic.AtomicReference("等待文件")
                val lastSleepRequest = mutableMapOf<FitnessDataId, Long>()
                fun report() {
                    sleepDiagnostic = listOf(liveSleep.get(), indexStatus.get(), receiveStatus.get(), minuteStatus.get(), sleepStatus.get(), "待补睡眠 ${catchup.outstanding()} 份", known.status()).filter { it.isNotEmpty() }.joinToString("，")
                }
                val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
                    ch.incomingL2.collect { event ->
                        if (event.channel != L2Channel.FILE_FITNESS || !verified.await()) return@collect
                        val round = activeRound.get()
                        val collected = round.collected
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
                                recordLive(round, round.live.received(frame, parts.finishedEntirelyThisRun, now))
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
                            }
                            if (actual != null) {
                                round.deliveries.merge(actual,1,Int::plus)
                                round.fileUpdates.value++
                            }
                        }
                        val heads = parts.headCounts.toSortedMap().entries.joinToString("/") { "${it.key}×${it.value}" }
                        val counts = synchronized(collected) { collected.count(FitnessSync::isDailyRecord) to collected.count(SleepArchive::accepts) }
                        receiveStatus.set("收到${parts.events}片，首片类型×次数 $heads，组装${parts.assembled}，CRC错${parts.badCrc}，包头异常${parts.badHeader}，无归属${parts.orphan}，未识别布局${parts.unexpectedLayout}，已存分钟${counts.first}组/睡眠${counts.second}组")
                        report()
                    }
                }
                var pbListener: Job? = null
                try {
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
                        val round = activeRound.get()
                        val indexes = round.indexes
                        if (indexes.observe(packet)) {
                            val ids = indexes.list(packet.id).orEmpty()
                            catchup.discover(ids)
                            rememberMinutes(ids)
                            if (packet.id == FitnessSync.ID_QUERY_TODAY) recordLive(round, round.live.indexed(ids))
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
                    stage = "确认型号"
                    check(DeviceInfoRequest(ch).fetchInfo().model == "M2401B1") { "型号不符" }
                    connectionAt = System.currentTimeMillis()
                    verified.complete(true)
                    session.value = ch
                    ready()
                    sendState("已连接", connected = true)

                    suspend fun collectOnce(round: Round): BandResult = coroutineScope {
                        val indexes = round.indexes
                        val collected = round.collected
                        val live = round.live
                        status.requestStarted()
                        liveSleep.compareAndSet("手环状态未读取", "状态回复78=0、通知79=0")
                        report()
                        val stateQuery = launch(start = CoroutineStart.UNDISPATCHED) {
                            ch.send(WearPacket(type = WearPacket.TYPE_SYSTEM, id = SleepStatus.QUERY))
                        }
                        try {
                            // The band can return more than one index packet. Keep both receivers
                            // installed across the cycle, and base every read on an observed ID.
                            stage = "查询当日健康记录"
                            ch.send(FitnessSync.buildTodayIdsQuery())
                            withTimeoutOrNull(3500) { indexUpdates.first { indexes.list(FitnessSync.ID_QUERY_TODAY) != null } }
                            stage = "查询历史健康记录"
                            ch.send(FitnessSync.buildHistoryIdsQuery())
                            withTimeoutOrNull(2500) { indexUpdates.first { indexes.list(FitnessSync.ID_QUERY_HISTORY) != null } }

                            val issued = linkedSetOf<FitnessDataId>()
                            val exact = known.next()
                            val deadline = SystemClock.elapsedRealtime() + 32_000L
                            while (issued.size < SerialFileQueue.MAX_FILES) {
                                val left = deadline - SystemClock.elapsedRealtime()
                                if (left < 600) break
                                val generation = indexUpdates.value
                                val today = indexes.list(FitnessSync.ID_QUERY_TODAY)
                                val past = indexes.list(FitnessSync.ID_QUERY_HISTORY)
                                val now = System.currentTimeMillis() / 1000
                                lastSleepRequest.entries.removeAll { now - it.value > 24 * 3600 }
                                val minutes = (today.orEmpty() + past.orEmpty()).distinct()
                                    .filter(FitnessSync::isDailyRecord).sortedByDescending { it.timestamp }
                                val rolling = minuteIDs.forRefresh(now, minutes)
                                val next = SerialFileQueue.next(
                                    now, today.orEmpty(), exact, catchup.due(issued, SleepCatchup.MAX_PER_CYCLE),
                                    (rolling + minutes.take(1) + minutes.filterNot { store.hasCompleteFrame(it) }.take(4)).distinct(),
                                    issued, lastSleepRequest,
                                )
                                indexStatus.set("睡眠索引（当日 ${SleepArchive.label(today)}，历史 ${SleepArchive.label(past)}）")
                                if (next == null) {
                                    // Recheck for a late or additional index after the queue drains.
                                    if (withTimeoutOrNull(minOf(left, 1200L)) { indexUpdates.first { it != generation } } == null) break
                                    continue
                                }
                                val before = round.deliveries[next] ?: 0
                                issued.add(next)
                                if (SleepArchive.accepts(next)) {
                                    live.requesting(listOf(next))
                                    catchup.attempted(listOf(next))
                                    lastSleepRequest[next] = now
                                    if (exact == next) known.begin(next)
                                }
                                stage = "读取手环健康文件"
                                // Gadgetbridge uses 8/3 with a single seven-byte ID in field 2.
                                ch.send(WearPacket(type=WearPacket.TYPE_FITNESS, id=FitnessSync.ID_REQUEST_FILES,
                                    fitnessRaw=FitnessMessage.encodeIds(next.toByteArray())))
                                if (exact == next) known.sent(next)
                                withTimeoutOrNull(minOf(4800L, left)) {
                                    round.fileUpdates.first { (round.deliveries[next] ?: 0) > before }
                                }
                            }
                            val today = indexes.list(FitnessSync.ID_QUERY_TODAY)
                            val past = indexes.list(FitnessSync.ID_QUERY_HISTORY)
                            persistSleepIndex(today, past, indexes, issued)
                            minuteStatus.set("本轮请求 ${issued.count(FitnessSync::isDailyRecord)} 份分钟文件")
                            report()
                            val count = collected.size
                            when {
                                count > 0 -> BandResult(true, "已保存 $count 组手环文件，新增或更新 ${added.get()} 项", added.get())
                                issued.isEmpty() -> BandResult(today != null && past != null, "本轮没有取得健康文件", added.get())
                                else -> BandResult(false, "未取得完整健康记录，可归属的分片已保存", added.get())
                            }
                        } finally {
                            withContext(NonCancellable) { stateQuery.cancelAndJoin() }
                        }
                    }
                    var round = activeRound.get()
                    main.removeCallbacks(watchdog)
                    while (isActive) {
                        val remaining = nextReadAt - SystemClock.elapsedRealtime()
                        if (remaining > 0 && shortConnections.value) {
                            // A command-only wake keeps its session until the pending actions finish.
                            withTimeoutOrNull(remaining) {
                                combine(shortConnections, commands) { short, n -> !short || n == 0 }.first { it }
                            }
                            if (shortConnections.value && SystemClock.elapsedRealtime() < nextReadAt) {
                                val close = controlLock.withLock {
                                    if (!shortConnections.value || commands.value > 0) false
                                    else { session.value = null; true }
                                }
                                if (close) break
                            }
                            continue
                        }
                        if (remaining > 0) {
                            withTimeoutOrNull(remaining) { shortConnections.first { it } }
                            continue
                        }
                        val start = SystemClock.elapsedRealtime()
                        main.postDelayed(watchdog, 14_000)
                        val battery = DeviceInfoRequest(ch).fetchStatus(7_500).battery
                        deliverPower(battery)
                        main.removeCallbacks(watchdog)
                        main.postDelayed(watchdog, 48_000)
                        added.set(0)
                        val read = withTimeout(44_000) { collectOnce(round) }
                        sendState("已连接", read.samples, true, read.status + "。" + sleepDiagnostic)
                        main.removeCallbacks(watchdog)
                        nextReadAt = start + 300_000L
                        round = Round()
                        activeRound.set(round)
                    }
                } finally {
                    verified.complete(false)
                    withContext(NonCancellable) {
                        pbListener?.cancelAndJoin()
                        receiver.cancelAndJoin()
                    }
                }
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
