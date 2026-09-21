package com.allperiph.core

/**
 * 传输抽象。架构 §2 路线 B 只保留该接口，不投入实现。
 * 数据面模块（sensor/gps）只依赖接口，不感知底层是 ConfigFS 还是降级通道。
 */

/**
 * HID IN 报告出口。
 * 约定：传入的字节数组**首字节必须是 Report ID**，长度必须等于描述符中该 ID 声明的长度
 * （PROTOCOL §2.1）。实现负责在链路不可用时安全丢弃而不是崩溃。
 */
interface HidTransport {
    fun sendInputReport(report: ByteArray): Boolean
    fun isReady(): Boolean
}

/** 字节流出口（CDC ACM / bulk 降级通道） */
interface SerialSink {
    fun write(bytes: ByteArray): Boolean
    fun isReady(): Boolean
}

/** 链路未就绪时的空实现：让模块可以先行启动，后续 attach 真实实现 */
object NullHidTransport : HidTransport {
    override fun sendInputReport(report: ByteArray): Boolean = false
    override fun isReady(): Boolean = false
}

object NullSerialSink : SerialSink {
    override fun write(bytes: ByteArray): Boolean = false
    override fun isReady(): Boolean = false
}

/**
 * 可热替换的传输代理：gadget 挂载完成后把真实实现塞进来，
 * 业务模块持有的引用无需重建。
 */
class DelegatingHidTransport : HidTransport {
    @Volatile
    private var delegate: HidTransport = NullHidTransport

    fun attach(t: HidTransport) {
        delegate = t
    }

    fun detach() {
        delegate = NullHidTransport
    }

    override fun sendInputReport(report: ByteArray): Boolean = delegate.sendInputReport(report)
    override fun isReady(): Boolean = delegate.isReady()
}

class DelegatingSerialSink : SerialSink {
    @Volatile
    private var delegate: SerialSink = NullSerialSink

    fun attach(t: SerialSink) {
        delegate = t
    }

    fun detach() {
        delegate = NullSerialSink
    }

    override fun write(bytes: ByteArray): Boolean = delegate.write(bytes)
    override fun isReady(): Boolean = delegate.isReady()
}
