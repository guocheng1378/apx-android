package com.allperiph.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简同步事件总线（无第三方依赖）。
 * - 默认在**发布者线程**回调，适合数据面（传感器/USB 读写）
 * - 传 Handler 可切线程，UI 用 [onMain]
 */
object EventBus {

    fun interface Disposable {
        fun dispose()
    }

    private class Sub<T : Any>(
        val type: Class<T>,
        val handler: Handler?,
        val block: (T) -> Unit,
    ) {
        fun deliver(event: Any) {
            if (!type.isInstance(event)) return
            @Suppress("UNCHECKED_CAST")
            val typed = event as T
            val h = handler
            when {
                h == null || h.looper == Looper.myLooper() -> safe(typed)
                else -> h.post { safe(typed) }
            }
        }

        private fun safe(e: T) = runCatching { block(e) }
            .onFailure { Log.w("EventBus", "listener failed on ${type.simpleName}: ${it.message}") }
    }

    private val subs = CopyOnWriteArrayList<Sub<*>>()

    fun post(event: Any) {
        for (s in subs) s.deliver(event)
    }

    // 只在 inline 中做 reified → Class 的翻译，实体逻辑委托给下面的非 inline 重载：
    // public inline 函数不能访问 private 的 Sub / subs，直接写体会编译失败。
    inline fun <reified T : Any> on(handler: Handler? = null, noinline block: (T) -> Unit): Disposable =
        on(T::class.java, handler, block)

    inline fun <reified T : Any> onMain(noinline block: (T) -> Unit): Disposable =
        on(T::class.java, null, block)

    fun <T : Any> on(type: Class<T>, handler: Handler? = null, block: (T) -> Unit): Disposable {
        val sub = Sub(type, handler, block)
        subs.add(sub)
        return Disposable { subs.remove(sub) }
    }

    fun <T : Any> onMain(type: Class<T>, block: (T) -> Unit): Disposable =
        on(type, MAIN_HANDLER, block)

    private val MAIN_HANDLER by lazy { Handler(Looper.getMainLooper()) }
}
