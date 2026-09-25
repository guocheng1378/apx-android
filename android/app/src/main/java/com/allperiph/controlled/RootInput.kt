package com.allperiph.controlled

import android.os.SystemClock
import com.allperiph.core.Log
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **root 注入通道**：把控制帧变成真正的系统输入（`input keyevent / tap / swipe / text`）。
 *
 * 为什么要它：无障碍服务**只能**做点击/滑动/文本/三个全局动作，任意按键（字母、Tab、
 * F 区、组合键）一律注入不了 —— 这是安卓的安全模型，不是 bug。而以 root 身份执行
 * `input`，走的是和 OTG 鼠标、蓝牙手柄**同一条系统输入通道**，于是：
 *   按键（全键）· 点击 · 滑动 · 文本 · 手柄按钮 —— 全都能用。
 *
 * 实现要点：
 *  - **常驻 su 进程**：每条命令都 `su -c` 会重复 fork（慢且容易被 root 管理器反复弹授权），
 *    这里只开一次 root shell，之后往它的 stdin 里写命令；
 *  - **异步 fire-and-forget**：输入命令不等返回（等返回会拖到 ~100ms/条），
 *    只靠 [probe] 在启动时确认一次通道可用；
 *  - 无 root / 被拒绝时 [available] 为 false，调用方自动退回无障碍，绝不崩。
 */
object RootInput {

    private const val TAG = "RootInput"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    private val starting = AtomicBoolean(false)

    /** root 通道是否可用（未授权 root / 无 root / su 异常 → false） */
    val available: Boolean
        get() = process?.isAlive == true && writer != null

    /** 尽力启动一次（可重复调用；已在跑或正在启动则直接返回） */
    fun tryStart() {
        if (available || !starting.compareAndSet(false, true)) return
        Thread({
            try {
                val p = openRootShell() ?: run {
                    Log.i(TAG, "root 不可用（未授权 / 无 root）→ 输入退回头无障碍通道")
                    return@Thread
                }
                process = p
                writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
                // 把 stderr 并到 stdout 排掉，避免管道堵死
                Thread({ runCatching { p.errorStream.bufferedReader().useLines { it.forEach { } } } },
                    "apx-root-err").start()
                if (!probe(p)) {
                    Log.w(TAG, "root shell 无响应，关闭")
                    runCatching { p.destroy() }
                    process = null
                    writer = null
                    return@Thread
                }
                Log.i(TAG, "root 注入通道已就绪：全键鼠可用")
            } catch (t: Throwable) {
                Log.w(TAG, "启动 root shell 失败：${t.message}")
            } finally {
                starting.set(false)
            }
        }, "apx-root-start").start()
    }

    private fun openRootShell(): Process? {
        // 常见写法都试一遍：Magisk / KernelSU / APatch 的 su 参数略有差异
        val candidates = listOf(
            arrayOf("su", "-c", "sh"),
            arrayOf("su", "0", "sh"),
            arrayOf("su"),
            arrayOf("/data/adb/ksu/bin/su", "-c", "sh"),
            arrayOf("/data/adb/ap/bin/su", "-c", "sh"),
        )
        for (cmd in candidates) {
            val p = try {
                ProcessBuilder(*cmd)
                    .redirectErrorStream(false)
                    .start()
            } catch (t: Throwable) {
                continue
            }
            // 给一点时间让 root 管理器弹授权/拒绝
            SystemClock.sleep(200)
            if (p.isAlive) return p
            runCatching { p.destroy() }
        }
        return null
    }

    /** 确认真的是 root shell（读回标记） */
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
        } catch (t: Throwable) {
            false
        }
    }

    /** 异步执行一条 root 命令（不等待返回） */
    @Synchronized
    fun run(cmd: String): Boolean {
        val w = writer ?: return false
        val p = process ?: return false
        if (!p.isAlive) return false
        return try {
            w.write(cmd)
            w.write("\n")
            w.flush()
            Log.d(TAG, "root 注入：$cmd")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "root 命令失败：$cmd（${t.message}）")
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
