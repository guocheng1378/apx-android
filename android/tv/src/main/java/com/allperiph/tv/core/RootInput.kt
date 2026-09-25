package com.allperiph.tv.core

import android.os.SystemClock
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **root 注入通道（TV 版）**：与手机端 `com.allperiph.controlled.RootInput` 同实现。
 * 电视 / 盒子如果有 root（不少盒子刷了第三方固件），按键/点击就走真正的系统输入通道，
 * 不再受无障碍能力限制；没有 root 时 [available] 为 false，自动退回无障碍。
 */
object RootInput {

    private const val TAG = "RootInput"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    private val starting = AtomicBoolean(false)

    val available: Boolean
        get() = process?.isAlive == true && writer != null

    fun tryStart() {
        if (available || !starting.compareAndSet(false, true)) return
        Thread({
            try {
                val p = openRootShell() ?: run {
                    Log.i("root 不可用（未授权 / 无 root）→ 输入走无障碍通道")
                    return@Thread
                }
                process = p
                writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
                Thread({ runCatching { p.errorStream.bufferedReader().useLines { it.forEach { } } } },
                    "apx-tv-root-err").start()
                if (!probe(p)) {
                    Log.w("root shell 无响应，关闭")
                    runCatching { p.destroy() }
                    process = null
                    writer = null
                    return@Thread
                }
                Log.i("root 注入通道已就绪：TV 全键鼠可用")
            } catch (t: Throwable) {
                Log.w("启动 root shell 失败：${t.message}")
            } finally {
                starting.set(false)
            }
        }, "apx-tv-root-start").start()
    }

    private fun openRootShell(): Process? {
        val candidates = listOf(
            arrayOf("su", "-c", "sh"),
            arrayOf("su", "0", "sh"),
            arrayOf("su"),
            arrayOf("/data/adb/ksu/bin/su", "-c", "sh"),
            arrayOf("/data/adb/ap/bin/su", "-c", "sh"),
        )
        for (cmd in candidates) {
            val p = try {
                ProcessBuilder(*cmd).start()
            } catch (_: Throwable) {
                continue
            }
            SystemClock.sleep(200)
            if (p.isAlive) return p
            runCatching { p.destroy() }
        }
        return null
    }

    private fun probe(p: Process): Boolean {
        return try {
            val w = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
            w.write("echo APX_ROOT_OK\n"); w.flush()
            val r = p.inputStream.bufferedReader()
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (r.ready()) {
                    val line = r.readLine()
                    if (line != null && line.contains("APX_ROOT_OK")) return true
                } else {
                    SystemClock.sleep(50)
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun run(cmd: String): Boolean {
        val w = writer ?: return false
        val p = process ?: return false
        if (!p.isAlive) return false
        return try {
            w.write(cmd)
            w.write("\n")
            w.flush()
            Log.i("root 注入：$cmd")
            true
        } catch (t: Throwable) {
            Log.w("root 命令失败：$cmd（${t.message}）")
            runCatching { p.destroy() }
            process = null
            writer = null
            false
        }
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
    }
}
