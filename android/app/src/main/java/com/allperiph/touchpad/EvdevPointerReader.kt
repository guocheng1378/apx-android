package com.allperiph.touchpad

import android.os.Handler
import android.os.HandlerThread
import com.allperiph.shared.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * 外接触控板/鼠标的**原始事件直读**（issue #52 建议 6 的第二条腿 · 需 root）。
 *
 * 为什么必须走 root：
 *   Android 把外接指针设备的原始位移**当成系统光标**消费掉 —— 上层（窗口回调、
 *   无障碍、输入法）拿到的要么是「已换算的 hover 绝对坐标」，要么什么都拿不到
 *   （App 不在前台时窗口根本收不到 MotionEvent）。要拿一手相对位移只能读
 *   `/dev/input/eventN`（evdev），而那是 root 才能读的字符设备。
 *
 * ⚠️ 读法必须是 `su -c cat <节点>`：App 进程本身即使拿到了 root 授权，也只是
 *   「可以调用 su」，并不等于自己拥有打开该字符设备的权限 —— 直接
 *   `File("/dev/input/eventN").inputStream()` 会 EACCES。所以从 su 子进程的
 *   stdout 读裸二进制流（struct input_event 固定 24 字节）。
 *
 * 与 [com.allperiph.ui.PhysKeyBridge] 的窗口 hover 通路互补、**互斥**（见
 * [RootPointerBridge]）：那条只在 App 前台有效，本类任何时候都有效。
 *
 * 只读不写、不落盘、只解析指针事件（键盘走 GlobalKeyForwarder）。
 */
class EvdevPointerReader(
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onButton: (buttons: Int) -> Unit,
    private val onWheel: (vertical: Int, horizontal: Int) -> Unit,
) {

    companion object {
        private const val TAG = "EvdevPointer"

        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_REL = 0x02

        private const val REL_X = 0x00
        private const val REL_Y = 0x01
        private const val REL_HWHEEL = 0x06
        private const val REL_WHEEL = 0x08

        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112

        /** struct input_event 在 64 位 Android 上：8B timeval + 2B type + 2B code + 4B value */
        private const val EVENT_SIZE = 24

        /**
         * 从 /proc/bus/input/devices 挑出指针设备的 event 节点。
         *
         * 两条判据取「或」，因为单用一条会在真机上当：
         *   - `mouseN`（Handlers 里）：内核挂了 mousedev 才有。**很多 Android 内核
         *     根本没编 mousedev** —— 真机（小米 15，Android 15）实测本机全部设备里
         *     一个 mouseN 都没有，只认这条会静默「找不到设备」，功能直接失效。
         *   - 能力位图：`EV` 位图含 EV_REL **且** `KEY` 位图含 BTN_LEFT。
         *     相对指针的本质特征，不依赖 mousedev。触摸屏是 EV_ABS、键盘无 EV_REL、
         *     手柄无 REL，都能据此排掉。
         *
         * 另外排除名字含 apx / allperiph 的自家虚拟设备，避免自激（读到自己发出的）。
         */
        fun findPointerNodes(): List<File> {
            val out = ArrayList<File>()
            try {
                val txt = File("/proc/bus/input/devices").readText()
                for (blk in txt.split("\n\n")) {
                    if (!blk.contains("Handlers=")) continue
                    val name = Regex("N: Name=\"([^\"]*)\"").find(blk)?.groupValues?.get(1) ?: ""
                    if (name.contains("apx", true) || name.contains("allperiph", true)) continue
                    val handlers = Regex("H: Handlers=([^\n]*)").find(blk)?.groupValues?.get(1) ?: ""
                    // eventN 是读裸事件的节点；没有它（纯 mouseN 的旧设备走 mousedev）就跳过
                    val idx = Regex("event(\\d+)").find(handlers)?.groupValues?.get(1) ?: continue
                    val byMouse = Regex("mouse\\d+").containsMatchIn(handlers)
                    val byCaps = hasRelAxis(blk) && hasBtnLeft(blk)
                    if (!byMouse && !byCaps) continue
                    Log.i(
                        TAG,
                        "指针设备：$name -> event$idx" +
                            if (byCaps && !byMouse) "（靠能力位图认出，该内核无 mousedev）" else "",
                    )
                    out.add(File("/dev/input/event$idx"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "读 /proc/bus/input/devices 失败：${t.message}")
            }
            return out
        }

        /**
         * 取 `B: <field>=` 的十六进制位图分组。
         * 内核按 64 位一组输出、**高位组在前**（如 `KEY=70000 0 0 0 0 0`）。
         */
        private fun bitmapGroups(blk: String, field: String): List<Long> {
            val m = Regex("B: " + field + "=([0-9A-Fa-f ]+)").find(blk) ?: return emptyList()
            return m.groupValues[1].trim().split(Regex("\\s+"))
                .mapNotNull { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() }
        }

        /** 位 b 是否置位（b 为 Linux 输入码，如 BTN_LEFT = 0x110） */
        private fun bitSet(groups: List<Long>, b: Int): Boolean {
            if (groups.isEmpty()) return false
            val gi = groups.size - 1 - (b / 64)
            if (gi < 0 || gi >= groups.size) return false
            return (groups[gi] ushr (b % 64)) and 1L == 1L
        }

        /** 相对轴能力（EV_REL = 0x02） */
        private fun hasRelAxis(blk: String) = bitSet(bitmapGroups(blk, "EV"), 0x02)

        /** 左键能力（BTN_LEFT = 0x110）—— 把「有相对轴但没键」的怪设备也排掉 */
        private fun hasBtnLeft(blk: String) = bitSet(bitmapGroups(blk, "KEY"), 0x110)
    }

    @Volatile private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val procs = ArrayList<Process>()

    fun start(): Boolean {
        if (running) return true
        val found = findPointerNodes()
        if (found.isEmpty()) {
            Log.i(TAG, "没找到指针类输入设备，跳过 evdev 直读")
            return false
        }
        // root 探测：拿不到 su 就别起子进程（否则每个节点都失败一次，日志刷屏，
        // 用户还看到「已开启」的假状态 —— issue #52 问题 5 的教训）。
        if (!hasRoot()) {
            Log.w(TAG, "没有 root，evdev 直读不可用")
            return false
        }
        running = true
        val ht = HandlerThread("apx-evdev").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)
        for (f in found) handler?.post { readLoop(f) }
        Log.i(TAG, "evdev 直读启动：${found.map { it.name }}")
        return true
    }

    fun stop() {
        running = false
        synchronized(procs) {
            for (p in procs) runCatching { p.destroy() }
            procs.clear()
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** root 可用性：起一次 `su -c true` 判成败（与 RootShell.open 同口径的轻量版） */
    private fun hasRoot(): Boolean = try {
        val pr = ProcessBuilder("su", "-c", "true").redirectErrorStream(true).start()
        val done = pr.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) runCatching { pr.destroy() }
        done && pr.exitValue() == 0
    } catch (t: Throwable) {
        Log.w(TAG, "su 探测异常：${t.message}")
        false
    }

    /** 起 `su -c cat <节点>`，从其 stdout 阻塞读事件 */
    private fun readLoop(f: File) {
        var proc: Process? = null
        var stream: InputStream? = null
        try {
            proc = ProcessBuilder("su", "-c", "cat ${f.absolutePath}")
                .redirectErrorStream(false)
                .start()
            synchronized(procs) { procs.add(proc) }
            stream = proc.inputStream
            DataInputStream(stream.buffered()).use { ins ->
                val buf = ByteArray(EVENT_SIZE)
                var pdx = 0; var pdy = 0; var pwheel = 0; var ppan = 0
                var buttons = 0
                while (running) {
                    var read = 0
                    while (read < EVENT_SIZE) {
                        val n = ins.read(buf, read, EVENT_SIZE - read)
                        if (n < 0) return
                        read += n
                    }
                    val type = le16(buf, 16)
                    val code = le16(buf, 18)
                    val value = le32(buf, 20)
                    when (type) {
                        EV_REL -> when (code) {
                            REL_X -> pdx += value
                            REL_Y -> pdy += value
                            REL_WHEEL -> pwheel += value
                            REL_HWHEEL -> ppan += value
                        }
                        EV_KEY -> if (code in BTN_LEFT..BTN_MIDDLE) {
                            buttons = when (code) {
                                BTN_LEFT -> if (value != 0) buttons or 0x01 else buttons and 0x01.inv()
                                BTN_RIGHT -> if (value != 0) buttons or 0x02 else buttons and 0x02.inv()
                                else -> if (value != 0) buttons or 0x04 else buttons and 0x04.inv()
                            }
                            onButton(buttons)
                        }
                        EV_SYN -> {
                            if (pdx != 0 || pdy != 0) { onMove(pdx, pdy); pdx = 0; pdy = 0 }
                            if (pwheel != 0 || ppan != 0) { onWheel(pwheel, ppan); pwheel = 0; ppan = 0 }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "evdev 读取结束 ${f.name}：${t.message}")
        } finally {
            runCatching { proc?.destroy() }
            synchronized(procs) { procs.remove(proc) }
            runCatching { stream?.close() }
        }
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}