package com.example.healthapp.data.protocol

import com.example.healthapp.data.protocol.crypto.MiWearCrypto
import com.example.healthapp.data.protocol.l1.L1Cmd
import com.example.healthapp.data.protocol.l1.L1Codec
import com.example.healthapp.data.protocol.l1.L1Frame
import com.example.healthapp.data.protocol.l1.L1Framer
import com.example.healthapp.data.protocol.l1.L1Type
import com.example.healthapp.data.protocol.l2.L2Channel
import com.example.healthapp.data.protocol.l2.L2Codec
import com.example.healthapp.data.protocol.l2.L2Frame
import com.example.healthapp.data.protocol.l2.L2Opcode
import com.example.healthapp.data.protocol.proto.WearPacket
import com.example.healthapp.data.protocol.sar.SarRx
import com.example.healthapp.data.protocol.sar.SarTx
import com.example.healthapp.data.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 把 Transport / L1(SAR) / L2 / protobuf 串起来的报文通道。
 *
 * 与设备的完整交互分两阶段：
 *
 * **阶段一：L1 握手（必须）**
 * 发 `CMD_L1_START_REQ`，等设备回 `CMD_L1_START_RSP`。
 * 不做这一步直接发业务数据，设备只会回一个 L1 ACK 然后沉默 —— 这是实测踩过的坑。
 *
 * **阶段二：业务报文**
 * WearPacket → protobuf → L2（按需加密）→ L1 DATA（递增 seq）→ Transport。
 *
 * ## 接收侧两条铁律
 *
 * 1. **按通道分流**：只有 `channel == PB` 的载荷才是 `WearPacket`。
 *    MASS / FILE_FITNESS / NETWORK / LYRA / CANOPUS 是各自格式的私有报文，
 *    一律按 protobuf 解会得到「不支持的 wire type: N」这类随机错误。
 * 2. **ACK 由 [SarRx] 决定**，不是「每帧必回」：PB / LYRA 立即 ACK，
 *    其余通道累积 ACK，NETWORK / MULTI_MODAL 不回，frx 帧也不回。
 */
class WearPacketChannel(
    private val transport: Transport,
    private val scope: CoroutineScope,
    /** 发送侧队头超时。默认 10s，与参考实现一致；测试里可调小 */
    sendTimeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS,
    /** 超时检查间隔；测试里可调小 */
    private val txTickMs: Long = TX_TICK_MS,
) {

    private val _incoming = MutableSharedFlow<WearPacket>(extraBufferCapacity = 64)
    val incoming: SharedFlow<WearPacket> = _incoming.asSharedFlow()

    /** 所有通道的原始报文（PB 通道也会发一份），供 M3 之后的私有协议使用 */
    private val _incomingL2 = MutableSharedFlow<L2Event>(extraBufferCapacity = 64)
    val incomingL2: SharedFlow<L2Event> = _incomingL2.asSharedFlow()

    /**
     * 链路**意外**断开时发出一次。
     *
     * 主动 [stop] 不会触发 —— 否则「用户点断开」也会被当成故障。
     * 有了它，上层才能把「手环被别的 App 抢走」这类情况如实反映到界面，
     * 而不是一直显示过期的「已连接」。
     */
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    /**
     * 发送侧判定链路失步时发出（重传超过上限）。
     *
     * 与 [closed] 区分开：socket 可能还活着，但序号已经对不上，继续用只会一直错。
     * 上层应当断开并重连。
     */
    private val _fatal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val fatal: SharedFlow<String> = _fatal.asSharedFlow()

    private val framer = L1Framer()
    private val sarRx = SarRx()

    /** 发送侧窗口 / 重传状态机。窗口先按 1，拿到对端 `txWin` 后调整 */
    private val sarTx = SarTx(windowSize = 1, sendTimeoutMs = sendTimeoutMs)

    /** 串行化「状态机变更 + 实际写出」，保证帧的写出顺序与序号一致 */
    private val txMutex = Mutex()

    private var encKey: ByteArray? = null
    private var decKey: ByteArray? = null
    private var readJob: Job? = null
    private var cumAckJob: Job? = null
    private var timeoutJob: Job? = null
    private var fatalReported = false

    /** 连续重开会话的次数；收到 START_RSP 即清零 */
    private var sessionRestarts = 0

    /** 主动关闭中 —— 用来区分「用户断开」和「链路掉了」 */
    @Volatile
    private var stopping = false

    private var handshakeDone = false
    private var handshakeSignal = CompletableDeferred<Unit>()

    /** 设备在 START_RSP 里声明的发送窗口 */
    var peerTxWin: Int? = null
        private set

    /** 设备在 START_RSP 里声明的超时 */
    var peerSendTimeoutMs: Int? = null
        private set

    val encryptionEnabled: Boolean get() = encKey != null
    val decryptionEnabled: Boolean get() = decKey != null
    val isHandshakeDone: Boolean get() = handshakeDone

    /** 接收侧期望的下一个序号，调试用 */
    val rxExpectSeq: Int get() = sarRx.expectSeq

    // ------------------------------------------------------------- 加密控制

    fun enableDecryption(decKey: ByteArray) {
        require(decKey.size == 16) { "decKey must be 16 bytes" }
        this.decKey = decKey
        MiWearLog.i("解密通道已启用 (decKey len=16)")
    }

    fun enableEncryption(encKey: ByteArray) {
        require(encKey.size == 16) { "encKey must be 16 bytes" }
        this.encKey = encKey
        MiWearLog.i("加密通道已启用 (encKey len=16)")
    }

    fun enableBoth(encKey: ByteArray, decKey: ByteArray) {
        enableDecryption(decKey)
        enableEncryption(encKey)
    }

    // --------------------------------------------------------------- 生命周期

    fun start() {
        if (readJob != null) return
        readJob = scope.launch(Dispatchers.IO) {
            try {
                transport.receive().collect { chunk ->
                    val frames = try {
                        framer.feed(chunk)
                    } catch (e: Exception) {
                        MiWearLog.e("拆帧失败: ${e.message}")
                        emptyList()
                    }
                    for (frame in frames) {
                        try {
                            handleL1(frame)
                        } catch (e: Exception) {
                            MiWearLog.e("处理帧失败: ${e.message}")
                        }
                    }
                }
            } finally {
                // 读取流结束 = socket 没了。主动 stop() 的情况不算故障。
                if (!stopping) {
                    MiWearLog.w("链路意外断开（接收流已结束）")
                    _closed.tryEmit(Unit)
                }
            }
        }

        // 发送侧超时看门狗：队头超时 -> 整窗重传 -> 超过上限判失步
        timeoutJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(txTickMs)
                txMutex.withLock { applyTx(sarTx.tick(now())) }
            }
        }
    }

    suspend fun stop() {
        stopping = true
        readJob?.cancel()
        readJob = null
        cumAckJob?.cancel()
        cumAckJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        transport.close()
    }

    // ------------------------------------------------------------- L1 握手

    /**
     * 执行 L1 SAR 握手。必须在 [start] 之后、发任何业务报文之前调用。
     *
     * @throws IllegalStateException 超时未收到 START_RSP
     */
    suspend fun handshake(timeoutMs: Long = HANDSHAKE_TIMEOUT_MS) {
        if (handshakeDone) return
        handshakeSignal = CompletableDeferred()
        sarRx.reset()
        sarTx.reset()
        fatalReported = false

        MiWearLog.i("SAR 握手: 发送 CMD_L1_START_REQ")
        sendCmd(L1Cmd.CMD_START_REQ)

        try {
            withTimeout(timeoutMs) { handshakeSignal.await() }
            MiWearLog.i(
                "SAR 握手完成 (peerTxWin=$peerTxWin, peerTimeout=$peerSendTimeoutMs)",
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "L1 握手超时：设备未回 CMD_L1_START_RSP。可能手环正被别的 App 占用。",
                e,
            )
        }
    }

    // --------------------------------------------------------------- 发送

    /** 发送一个 WearPacket（需要先完成握手） */
    suspend fun send(packet: WearPacket) {
        check(handshakeDone) { "L1 握手尚未完成，不能发送业务报文" }

        val plain = packet.encode()
        val key = encKey

        val l2Frame = if (key != null) {
            L2Frame(L2Channel.PB, L2Opcode.WRITE_ENC, MiWearCrypto.aesCtr(key, plain))
        } else {
            L2Frame(L2Channel.PB, L2Opcode.WRITE, plain)
        }

        val label = "${WearPacket.typeName(packet.type)} id=${packet.id} enc=${key != null}"
        txMutex.withLock {
            applyTx(sarTx.enqueue(L2Codec.encode(l2Frame), now()), label)
        }
    }

    /**
     * 发一条请求并等待匹配的应答。
     *
     * **先订阅再发送** —— 否则设备回包快于订阅时会丢包。
     */
    suspend fun request(
        packet: WearPacket,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        predicate: (WearPacket) -> Boolean,
    ): WearPacket = withTimeout(timeoutMs) {
        coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                incoming.first(predicate)
            }
            try {
                send(packet)
                waiter.await()
            } catch (e: Exception) {
                waiter.cancel()
                throw e
            }
        }
    }

    // --------------------------------------------------------------- 接收

    private suspend fun handleL1(frame: L1Frame) {
        when (frame.type) {
            L1Type.CMD -> handleCmd(frame)

            L1Type.ACK -> {
                // 累积确认：seq 及之前的帧全部收到，窗口可以前移
                MiWearLog.i("RX ACK seq=${frame.seq}")
                txMutex.withLock { applyTx(sarTx.onAck(frame.seq, now())) }
            }

            L1Type.NAK -> {
                // 对端要我们从 seq 重传（seq 之前视为已收到）
                MiWearLog.w("RX NAK seq=${frame.seq}（对端要求从该序号重传）")
                txMutex.withLock { applyTx(sarTx.onNak(frame.seq, now())) }
            }

            L1Type.DATA -> handleData(frame)
        }
    }

    private suspend fun handleCmd(frame: L1Frame) {
        val cmd = L1Cmd.parse(frame.payload) ?: run {
            MiWearLog.w("收到无法解析的 CMD 帧")
            return
        }
        MiWearLog.i("RX CMD $cmd")

        when (cmd.cmd) {
            L1Cmd.CMD_START_RSP -> {
                peerTxWin = cmd.txWin()
                peerSendTimeoutMs = cmd.sendTimeout()
                // 用对端通告的窗口放开在途帧数；对端没给就保守用 1
                sarTx.windowSize = (cmd.txWin() ?: 1).coerceIn(1, MAX_TX_WINDOW)
                handshakeDone = true
                sarRx.markSessionActive()
                // 会话已重新对齐，重开计数清零
                sessionRestarts = 0
                MiWearLog.i("SAR 发送窗口设为 ${sarTx.windowSize}（在途上限）")
                if (!handshakeSignal.isCompleted) handshakeSignal.complete(Unit)
            }

            L1Cmd.CMD_START_REQ -> {
                // 设备主动发起握手（例如它重启了会话）：对端序号已归零，本端收发都要重置
                peerTxWin = cmd.txWin()
                peerSendTimeoutMs = cmd.sendTimeout()
                MiWearLog.i("设备发起 L1 握手，回 START_RSP 并重置收发状态")
                sarRx.reset()
                sarTx.reset()
                fatalReported = false
                sarTx.windowSize = (cmd.txWin() ?: 1).coerceIn(1, MAX_TX_WINDOW)
                sendCmd(L1Cmd.CMD_START_RSP)
                handshakeDone = true
                sarRx.markSessionActive()
                if (!handshakeSignal.isCompleted) handshakeSignal.complete(Unit)
            }

            L1Cmd.CMD_STOP_REQ -> {
                MiWearLog.w("设备请求停止 L1 会话")
                handshakeDone = false
                sarRx.reset()
                sarTx.reset()
            }

            else -> MiWearLog.i("忽略 CMD code=${cmd.cmd}")
        }
    }

    private suspend fun handleData(frame: L1Frame) {
        val l2 = try {
            L2Codec.decode(frame.payload)
        } catch (e: Exception) {
            MiWearLog.e("L2 解析失败 len=${frame.payload.size}: ${e.message}")
            return
        }

        // ---- SAR：序号连续性 + 何时 ACK 全在这里决定 ----
        val outcome = sarRx.onData(frame.seq, frame.frx, l2.channel)
        when (val reply = outcome.reply) {
            is SarRx.Reply.Ack -> sendAck(reply.seq)
            is SarRx.Reply.Nak -> sendNak(reply.seq)
            null -> Unit
        }
        if (outcome.armCumulativeAck) armCumulativeAck()

        if (!outcome.deliver) {
            MiWearLog.i("RX ${l2.channel} seq=${frame.seq} 丢弃（序号未对齐）")
            return
        }

        val body = if (l2.opcode == L2Opcode.WRITE_ENC) {
            val key = decKey ?: run {
                MiWearLog.e("收到 WriteEnc 但还没有 decKey，丢弃")
                return
            }
            try {
                MiWearCrypto.aesCtr(key, l2.payload)
            } catch (e: Exception) {
                MiWearLog.e("解密失败: ${e.message}")
                return
            }
        } else {
            l2.payload
        }

        _incomingL2.emit(L2Event(l2.channel, l2.opcode, body))

        // 只有 PB 通道的载荷才是 WearPacket。其余通道是各自格式的私有报文，
        // 按 protobuf 解必然炸 —— 这正是之前「不支持的 wire type: N」的根因。
        if (l2.channel != L2Channel.PB) {
            // M3 探路：非 PB 通道的载荷是各家的私有格式，唯一能做的就是留原始 hex。
            // NETWORK 通道每几秒就一帧 316 字节，只留前缀避免刷屏。
            val cap = if (l2.channel == L2Channel.NETWORK) 32 else 256
            val hex = MiWearCrypto.bytesToHex(body.copyOf(minOf(body.size, cap)))
            MiWearLog.i(
                "RX ${l2.channel.name} op=${l2.opcode} seq=${frame.seq} len=${body.size} " +
                    "hex=$hex${if (body.size > cap) "…" else ""}",
            )
            return
        }

        val packet = try {
            WearPacket.decode(body)
        } catch (e: Exception) {
            MiWearLog.e("PB 报文解码失败 len=${body.size}: ${e.message}")
            return
        }

        MiWearLog.i(
            "RX ${WearPacket.typeName(packet.type)} id=${packet.id} " +
                "len=${body.size} enc=${l2.opcode == L2Opcode.WRITE_ENC} " +
                "err=${packet.errorCode?.let { WearPacket.errorCodeName(it) } ?: "-"} " +
                "fitnessLen=${packet.fitnessRaw?.size ?: -1}",
        )
        // FITNESS 是 M3 探路对象，原始 hex 一律打出来，方便对照 proto 反推
        _incoming.emit(packet)
    }

    // --------------------------------------------------------------- 底层

    private suspend fun sendCmd(cmd: Int) {
        val payload = L1Cmd.buildStartCmd(cmd)
        // CMD 帧的 seq 固定为 0（与 AstroBox 一致）
        val l1 = L1Frame(L1Type.CMD, frx = false, seq = 0, payload = payload)
        MiWearLog.i("TX CMD code=$cmd len=${payload.size}")
        transport.send(L1Codec.encode(l1))
    }

    private suspend fun sendAck(seq: Int) {
        val l1 = L1Frame(L1Type.ACK, frx = false, seq = seq, payload = ByteArray(0))
        transport.send(L1Codec.encode(l1))
        MiWearLog.i("TX ACK seq=$seq")
    }

    private suspend fun sendNak(seq: Int) {
        val l1 = L1Frame(L1Type.NAK, frx = false, seq = seq, payload = ByteArray(0))
        transport.send(L1Codec.encode(l1))
        MiWearLog.w("TX NAK seq=$seq（请求设备从该序号重传）")
    }

    /** 非 PB 通道用累积 ACK：500ms 内没有新帧就补一个，避免每帧一次往返 */
    private fun armCumulativeAck() {
        if (cumAckJob?.isActive == true) return
        cumAckJob = scope.launch(Dispatchers.IO) {
            delay(CUM_ACK_DELAY_MS)
            cumAckJob = null
            sarRx.flushCumulativeAck()?.let { sendAck(it) }
        }
    }

    /** 把发送状态机的结论落地：写出帧 + 重开会话 / 判定失步 */
    private suspend fun applyTx(result: SarTx.TxResult, label: String = "") {
        for (f in result.toSend) {
            MiWearLog.i("TX ${label.ifEmpty { "SAR" }} seq=${f.seq} len=${f.payload.size}")
            transport.send(
                L1Codec.encode(
                    L1Frame(L1Type.DATA, frx = false, seq = f.seq, payload = f.payload),
                ),
            )
        }

        if (result.restartSession) restartSession()
    }

    /**
     * 重开 L1 会话（对齐 AstroBox 的 `restart_session`）。
     *
     * 重传超限时**先尝试自愈**而不是直接放弃：清空收发序号、重发 START_REQ，让两端
     * 序号重新对齐。只有连续重开 [MAX_SESSION_RESTARTS] 次都没救回来，才判定失步。
     */
    private suspend fun restartSession() {
        sessionRestarts++

        if (sessionRestarts > MAX_SESSION_RESTARTS) {
            val reason = "SAR 连续 $MAX_SESSION_RESTARTS 次重开会话仍未恢复同步"
            if (!fatalReported) {
                fatalReported = true
                MiWearLog.e(reason)
                _fatal.tryEmit(reason)
            }
            return
        }

        MiWearLog.w("SAR 失步，重开 L1 会话（第 $sessionRestarts/$MAX_SESSION_RESTARTS 次）")
        sarRx.reset()
        sarTx.reset()
        // 回到保守窗口，等新的 START_RSP 再放开
        sarTx.windowSize = 1
        sendCmd(L1Cmd.CMD_START_REQ)
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val CUM_ACK_DELAY_MS = 500L

        /**
         * 发送侧队头超时，对齐参考实现（SPP 链路 10s）。
         *
         * 注意：设备在 START_RSP 里通告的 `sendTimeout` 只作记录 ——
         * 两端各自用自己的发送超时，这是 AstroBox 注释里明确的结论。
         */
        const val DEFAULT_SEND_TIMEOUT_MS = 10_000L

        /** 发送侧超时检查间隔 */
        const val TX_TICK_MS = 500L

        /** 对端通告的窗口上限，防止异常值把在途帧数拉爆 */
        const val MAX_TX_WINDOW = 32

        /**
         * 连续重开 L1 会话的上限。
         *
         * 重传超限时先自愈（清序号 + 重发 START_REQ）；连续这么多次都救不回来，
         * 才判定链路真的坏了。参考实现也是「重开会话」而非直接放弃。
         */
        const val MAX_SESSION_RESTARTS = 3
    }
}
