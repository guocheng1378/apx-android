package com.allperiph.gadget

import com.allperiph.core.Log
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * root 操作的**唯一出口**（硬性要求：其它模块不得直接 su）。
 *
 * 采用**常驻 su 进程 + 结束标记**的方式，而不是每条命令 `su -c`：
 * 挂载序列有几十条命令，逐条 fork su 会显著拖慢且容易漏掉返回值。
 */
class RootShell private constructor(private val process: Process) {

    data class Result(
        val exitCode: Int,
        val out: String,
        val timedOut: Boolean,
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    private val lines = LinkedBlockingQueue<String>()
    private val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

    @Volatile
    private var broken = false

    val isBroken: Boolean get() = broken || !process.isAlive

    private val readerThread = thread(start = true, name = "apx-su-reader") {
        try {
            process.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) lines.put(line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reader ended: ${t.message}")
        } finally {
            lines.put(EOF_MARKER)
        }
    }

    @Synchronized
    fun exec(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        if (isBroken) return Result(-1, "", false)
        val sb = StringBuilder()
        var code = -1
        var timedOut = false
        try {
            writer.write(cmd)
            writer.write("\n")
            // 命令后紧跟退出码标记，作为本次 exec 的结束边界
            writer.write("echo \"$MARKER\$?\"\n")
            writer.flush()

            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) {
                    timedOut = true
                    broken = true
                    Log.w(TAG, "timeout: $cmd")
                    break
                }
                val line = lines.poll(remain, TimeUnit.MILLISECONDS) ?: continue
                if (line == EOF_MARKER) {
                    broken = true
                    Log.w(TAG, "su EOF while running: $cmd")
                    break
                }
                if (line.startsWith(MARKER)) {
                    code = line.removePrefix(MARKER).trim().toIntOrNull() ?: -1
                    break
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        } catch (t: Throwable) {
            broken = true
            Log.w(TAG, "exec failed: ${t.message}")
        }
        return Result(code, sb.toString(), timedOut)
    }

    // —— 常用封装 ——

    fun ok(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = exec(cmd, timeoutMs).ok

    /** ConfigFS/sysfs 写入：用 printf 避免 toybox echo 的 -n 行为差异与多余换行 */
    fun writeAttr(path: String, value: String): Boolean =
        exec("printf '%s' '$value' > '$path'").ok

    fun readAttr(path: String): String =
        exec("cat '$path' 2>/dev/null").out.trim()

    fun readAttrOrNull(path: String): String? {
        val r = exec("cat '$path' 2>/dev/null")
        return if (r.ok || r.out.isNotEmpty()) r.out.trim().takeIf { it.isNotEmpty() } else null
    }

    fun listDir(path: String): List<String> =
        exec("ls -1 '$path' 2>/dev/null").out
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    fun makeDirs(path: String): Boolean = ok("mkdir -p '$path'")

    fun removeRecursive(path: String): Boolean = ok("rm -rf '$path'")

    fun chmod(path: String, mode: String): Boolean = ok("chmod $mode '$path'")

    fun symlink(target: String, link: String): Boolean = ok("ln -s '$target' '$link'")

    fun exists(path: String): Boolean = ok("[ -e '$path' ]")

    /**
     * 二进制投递：App 以普通权限写到私有目录，再由 root 侧 cp 到目标。
     * 直接向 su 的 stdin 灌二进制在有结束标记的 shell 里不可靠，故走文件。
     */
    fun pushFile(src: File, dstPath: String, mode: String = "0644"): Boolean {
        if (!src.exists()) return false
        return ok("cp '${src.absolutePath}' '$dstPath'") && chmod(dstPath, mode)
    }

    fun getprop(name: String): String = exec("getprop '$name'").out.trim()

    fun setprop(name: String, value: String): Boolean = ok("setprop '$name' '$value'")

    fun close() {
        try {
            runCatching { writer.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
        } finally {
            Log.i(TAG, "closed")
        }
    }

    companion object {
        private const val TAG = "RootShell"
        private const val MARKER = "__APX_EC__"
        private const val EOF_MARKER = "__APX_EOF__"
        private const val DEFAULT_TIMEOUT_MS = 5_000L

        /**
         * 打开 root shell；无 root 返回 null（调用方应给出可理解的降级提示，而不是崩溃）。
         */
        fun open(): RootShell? {
            val candidates = listOf(
                listOf("su", "-c", "sh"),
                listOf("su", "0", "sh"),
                listOf("su"),
            )
            for (cmd in candidates) {
                val shell = try {
                    val pb = ProcessBuilder(cmd)
                    pb.redirectErrorStream(true)
                    RootShell(pb.start())
                } catch (t: Throwable) {
                    Log.w(TAG, "cannot exec ${cmd.joinToString(" ")}: ${t.message}")
                    null
                } ?: continue

                val r = shell.exec("id -u", 3_000L)
                if (!r.timedOut && r.out.trim() == "0") {
                    Log.i(TAG, "root shell up via ${cmd.joinToString(" ")}")
                    return shell
                }
                Log.w(TAG, "candidate ${cmd.joinToString(" ")} not root (out=${r.out.trim()})")
                shell.close()
            }
            Log.w(TAG, "ROOT UNAVAILABLE")
            return null
        }
    }
}
