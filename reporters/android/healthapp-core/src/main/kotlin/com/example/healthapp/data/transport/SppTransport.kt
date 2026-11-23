package com.example.healthapp.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * 通过经典蓝牙 SPP 连接手环。
 *
 * 连接策略完全对齐 AstroBox-NG 的 BTSpp.kt：
 *
 * ```
 * 1) trySdpUuid()   —— fetchUuidsWithSdp + 轮询 20 次找 00001101 前缀
 *                      insecure 优先（部分国行 ROM 只允许 insecure），失败回退 secure
 * 2) tryChannel()   —— SDP 拿不到时，反射调用 createInsecureRfcommSocket(int)
 *                      直接连 RFCOMM 通道 5（8 秒），再试通道 1（6 秒）
 * ```
 *
 * 第 2 步是关键：小米手环常常不响应 SDP，此时只能按通道号硬连。
 *
 * 前置条件：手环已配对；且没有别的 App（小米运动健康 / AstroBox）占着它 ——
 * 小米手环同一时刻只接受一条 SPP 连接。
 */
@SuppressLint("MissingPermission")
class SppTransport(private val context: Context) : Transport {

    companion object {
        private const val TAG = "MiWear"
        private const val SPP_PREFIX = "00001101"
        private const val SDP_CONNECT_TIMEOUT_MS = 6_000L
        private const val SDP_POLL_COUNT = 20
        private const val SDP_POLL_INTERVAL_MS = 100L
        private const val BUFFER_SIZE = 4096

        /** RFCOMM 通道候选，与 AstroBox 一致 */
        private val CHANNEL_CANDIDATES = listOf(5 to 8_000L, 1 to 6_000L)

        /**
         * Xiaomi 的 SPP「神秘 Hello」：`ba dc fe 00 c0 03 00 00 01 00 ef`（11 字节）。
         *
         * 逐字取自 AstroBox-NG `src/device/xiaomi.rs`：
         * `hex_stream_to_bytes("badcfe00c00300000100ef")`。
         * 它**不属于** A5A5 L1 协议，是更低一层的建链声明，必须在任何 L1 帧之前发出。
         */
        private val MYSTERY_HELLO = byteArrayOf(
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), 0x00,
            0xC0.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00, 0xEF.toByte(),
        )
    }

    private var socket: BluetoothSocket? = null
    private var out: java.io.OutputStream? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override suspend fun connect(address: String): Unit = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter
            ?: throw IllegalStateException("本机不支持蓝牙")

        if (!adapter.isEnabled) throw IllegalStateException("蓝牙未开启")

        val device = adapter.getRemoteDevice(address)
        Log.i(TAG, "SPP connecting to $address")
        logDeviceState(device)

        // API 31+ 上 isDiscovering / cancelDiscovery 都需要 BLUETOOTH_SCAN。
        // 我们从不主动扫描，这里只是「顺手清理」—— 拿不到权限就跳过，
        // 绝不能因为清理动作失败而连不上手环（真机踩过：整个连接直接抛 SecurityException）。
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        }.onFailure {
            Log.w(TAG, "跳过 cancelDiscovery（多半缺 BLUETOOTH_SCAN）: ${it.message}")
        }

        // ---- 策略 1：SDP UUID ----
        var sock = trySdpUuid(device)
        var how = "SDP-UUID"

        // ---- 策略 2：RFCOMM 通道号（SDP 失败时的关键回退）----
        if (sock == null) {
            Log.w(TAG, "SDP 路径失败，改用 RFCOMM 通道号直连")
            for ((channel, timeout) in CHANNEL_CANDIDATES) {
                sock = tryChannel(device, channel, timeout)
                if (sock != null) {
                    how = "RFCOMM-channel-$channel"
                    break
                }
            }
        }

        val connected = sock ?: throw IllegalStateException(buildFailureMessage(device))

        socket = connected
        val stream = connected.outputStream
        out = stream

        // ---- 小米的「神秘 Hello」----
        // SPP 建链后必须**立刻**发这一帧，早于任何 A5A5 协议数据（SAR 握手也不行）。
        // 来源：AstroBox-NG `src/device/xiaomi.rs`，作者注释：
        //   「不知道为什么傻逼小米针对 SPP 连接要发这么一个神秘 Hello」
        // 我们此前不发它：认证照样成功、设备信息照样能读，但链路约 1.7s 就被断开。
        runCatching {
            stream.write(MYSTERY_HELLO)
            stream.flush()
            Log.i(TAG, "已发送 SPP Hello（${MYSTERY_HELLO.size} 字节）")
        }.onFailure {
            Log.w(TAG, "发送 SPP Hello 失败（忽略）: ${it.message}")
        }

        Log.i(TAG, "SPP connected via $how")
    }

    // ------------------------------------------------------------ 策略 1：SDP

    private suspend fun trySdpUuid(dev: BluetoothDevice): BluetoothSocket? {
        if (!runCatching { dev.fetchUuidsWithSdp() }.getOrDefault(false)) {
            Log.w(TAG, "fetchUuidsWithSdp() 返回 false")
            // 不直接放弃：缓存里可能已有 UUID
        }

        repeat(SDP_POLL_COUNT) {
            val parcel = dev.uuids
                ?.firstOrNull { it.uuid.toString().startsWith(SPP_PREFIX, ignoreCase = true) }

            if (parcel != null) {
                Log.i(TAG, "SDP 找到 SPP UUID: ${parcel.uuid}")

                // insecure 优先：部分国行 ROM 只允许 insecure
                tryConnect(dev, parcel.uuid, insecure = true)?.let { return it }
                Log.w(TAG, "insecure 失败，回退 secure")
                tryConnect(dev, parcel.uuid, insecure = false)?.let { return it }
            }
            delay(SDP_POLL_INTERVAL_MS)
        }
        return null
    }

    private suspend fun tryConnect(
        dev: BluetoothDevice,
        uuid: UUID,
        insecure: Boolean,
    ): BluetoothSocket? = runCatching {
        val sock = if (insecure) {
            dev.createInsecureRfcommSocketToServiceRecord(uuid)
        } else {
            dev.createRfcommSocketToServiceRecord(uuid)
        }
        try {
            Log.i(TAG, "connect uuid=$uuid insecure=$insecure")
            withTimeout(SDP_CONNECT_TIMEOUT_MS) { sock.connect() }
            sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            Log.w(TAG, "connect 失败 (insecure=$insecure): ${e.message}")
            throw e
        }
    }.getOrNull()

    // -------------------------------------------------------- 策略 2：通道号

    /**
     * 反射调用 `createInsecureRfcommSocket(int)` / `createRfcommSocket(int)`。
     *
     * 这是隐藏 API，但在小米设备上可用（AstroBox 也是这么做的）。
     */
    private suspend fun tryChannel(
        dev: BluetoothDevice,
        channel: Int,
        timeoutMs: Long,
    ): BluetoothSocket? = runCatching {
        Log.i(TAG, "tryChannel(ch=$channel)")

        val method = runCatching {
            dev.javaClass.getMethod("createInsecureRfcommSocket", Integer.TYPE)
        }.getOrNull()
            ?: dev.javaClass.getMethod("createRfcommSocket", Integer.TYPE)

        val sock = method.invoke(dev, channel) as BluetoothSocket
        try {
            withTimeout(timeoutMs) { sock.connect() }
            sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            Log.w(TAG, "通道 $channel 连接失败: ${e.message}")
            throw e
        }
    }.getOrNull()

    // ------------------------------------------------------------------ 其它

    private fun logDeviceState(device: BluetoothDevice) {
        val bond = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_NONE -> "NONE"
            else -> "UNKNOWN(${device.bondState})"
        }
        val uuids = device.uuids?.joinToString { it.uuid.toString() } ?: "<null>"
        Log.i(TAG, "device bond=$bond, uuids=$uuids")
    }

    private fun buildFailureMessage(device: BluetoothDevice): String = buildString {
        append("SPP 连接失败（SDP 与 RFCOMM 通道 5/1 都试过了）。可能原因：")
        append("① 小米运动健康 / AstroBox 正占着手环 —— 手环只接受一条连接，请先在它们里面断开；")
        append("② 手环处于休眠 —— 请点亮手环屏幕后重试；")
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            append("③ 手环未配对 —— 请先在系统蓝牙里配对；")
        }
        append("④ 手环不在范围内。")
    }

    override suspend fun send(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val o = out ?: throw IllegalStateException("未连接")
        try {
            o.write(bytes)
            o.flush()
        } catch (e: IOException) {
            throw IllegalStateException("发送失败：${e.message}", e)
        }
    }

    override fun receive(): Flow<ByteArray> = flow {
        val sock = socket ?: throw IllegalStateException("未连接，请先 connect()")
        val input = sock.inputStream
        val buffer = ByteArray(BUFFER_SIZE)

        while (currentCoroutineContext().isActive) {
            val n = try {
                input.read(buffer)
            } catch (e: Exception) {
                Log.w(TAG, "读循环结束: ${e.message}")
                break
            }
            if (n <= 0) break
            emit(buffer.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { out?.close() }
        runCatching { socket?.close() }
        out = null
        socket = null
        Log.i(TAG, "SPP closed")
    }
}
