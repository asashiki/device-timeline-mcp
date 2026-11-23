package com.example.healthapp.data.transport

import android.content.Context

/** 生产环境用的工厂：每次 [create] 都新建一个 SPP socket */
class SppTransportFactory(private val context: Context) : TransportFactory {
    override fun create(): Transport = SppTransport(context)
}
