package com.devicetimeline.agent

internal object ActivityNotice {
    fun format(interactive: Boolean, keyguard: Boolean, foregroundApp: String?, sync: String): String {
        val local = when {
            !interactive -> "屏幕已关闭"
            keyguard -> "屏幕已点亮，等待解锁"
            foregroundApp != null -> "正在记录 · $foregroundApp"
            else -> "设备记录已开启"
        }
        return if (sync.isBlank()) local else "$local　·　$sync"
    }
}

internal class DeliveryNotice {
    private var generation = 0L
    private var detail = ""
    @Synchronized fun current() = generation
    @Synchronized fun message() = detail
    @Synchronized fun reset() { generation++; detail = "" }
    @Synchronized fun network(available: Boolean) {
        generation++
        detail = if (available) "正在连接" else "等待联网"
    }
    @Synchronized fun result(startGeneration: Long, text: String): Boolean {
        if (startGeneration != generation) return false
        detail = text
        return true
    }
}
