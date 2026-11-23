package com.example.healthapp.data.protocol

/**
 * 协议层的日志门面。纯 Kotlin，不依赖 Android。
 *
 * 由 :app 在 Application.onCreate 里注入 sink 指向 android.util.Log。
 * 未注入时静默 —— 这样单元测试不需要 Robolectric 也能跑。
 */
object MiWearLog {

    /** level: "I" / "W" / "E" */
    var sink: (level: String, msg: String) -> Unit = { _, _ -> }

    fun i(msg: String) = sink("I", msg)
    fun w(msg: String) = sink("W", msg)
    fun e(msg: String) = sink("E", msg)
}
