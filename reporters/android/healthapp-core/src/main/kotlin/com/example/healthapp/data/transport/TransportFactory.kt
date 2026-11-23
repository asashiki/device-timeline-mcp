package com.example.healthapp.data.transport

/**
 * 传输层工厂。
 *
 * 存在的唯一理由是**可测性**：仓库层只依赖这个接口，测试里换成
 * `FakeBandTransport` 就能在 JVM 上跑完整连接流程，不需要真机。
 */
fun interface TransportFactory {
    fun create(): Transport
}
