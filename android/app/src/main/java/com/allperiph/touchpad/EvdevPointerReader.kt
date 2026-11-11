package com.allperiph.touchpad

import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import com.allperiph.shared.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 外接触控板/鼠标的**原始事件直读**（issue #52 建议 6 的第二条腿 · 需 root）。
 *
 * 为什么必须走 root：
 *   Android 把外接指针设备的原始位移**当成系统光标**消费掉 —— 上层（窗口回调、
 *   无障碍、输入法）拿到的要么是「已换算的 hover 绝对坐标」，要么什么都拿不到
 *   （App 不在前台时窗口根本收不到 MotionEvent）。要拿一手相对位移只能读
 *   `/dev/input/eventN`（evdev），而那是 root 才能读的字符设备。
 *
 * ⚠️ 打开节点需要权限：App 进程本身即使拿到了 root 授权，也只是「可以调用 su」，
 *   并不等于自己拥有打开该字符设备的权限 —— 直接 `File("/dev/input/eventN").inputStream()`
 *   会 EACCES。所以先 `su -c chmod 666` 放权限，再本进程打开。
 *
 * v223.29.66 补「输入没拦截」的大头（issue#168 三追第 4 条）：
 *   **EVIOCGRAB 独占**。旧通路 `su -c cat <节点>` 只是抄一份事件 —— 内核照样把
 *   事件派给安卓输入子系统，触控板一边转发一边操作手机屏幕，用户根本没法验证。
 *   现在启动时 root 放权限 → 本进程打开 → `ioctl(EVIOCGRAB)` 独占：内核从此不再
 *   把该设备的事件派给其它读者（安卓光标在内），输入只走本进程 → 电脑。
 *   独占失败（如 SELinux 拦打开）的节点落回 root-cat 通路（只转发、不拦截），
 *   界面按 [interceptedAll] 如实区分两种状态，不装成功。
 *
 * v223.29.63 修「实测未生效」（issue #52 二轮反馈）的两个硬缺口：
 *   1. **设备表读取也走 su**：`/proc/bus/input/devices` 在不少系统上对普通进程
 *      EACCES，旧实现扫描时**没挂 su**，直接读失败被 catch 吞掉 → 永远「没找到
 *      指针设备」→ 功能即使开了也静默退回窗口通路。现在扫描失败时改用
 *      `su -c cat /proc/bus/input/devices` 重读（扫描本身也可能需要 root）。
 *   2. **认得出真触控板**：触摸板在 evdev 里是 EV_ABS（绝对坐标）设备，没有
 *      REL 轴 —— 旧实现只解析 REL，鼠标能用、触控板永远一动不动。现在对
 *      **外接总线**（USB / 蓝牙，Bus=0003/0005）的 ABS 设备解析绝对坐标并在
 *      SYN 里合成相对位移（内置触屏 Bus=0018 等被总线过滤挡掉，不会读自己）。
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
        private const val EV_ABS = 0x03

        private const val REL_X = 0x00
        private const val REL_Y = 0x01
        private const val REL_HWHEEL = 0x06
        private const val REL_WHEEL = 0x08

        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112

        /** 触摸接触键：触控板手指按下 / 抬起（ABS 设备的位移门禁） */
        private const val BTN_TOUCH = 0x14A

        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_MT_SLOT = 0x2F
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39

        /** Linux input.h 的总线号：USB 与蓝牙（外接设备的判定依据） */
        private const val BUS_USB = 0x0003
        private const val BUS_BLUETOOTH = 0x0005

        /** ABS 触控板坐标 → 指针位移的缩放（触控板坐标粒度比鼠标 mickey 粗，约 1/3 折算） */
        private const val ABS_SENSITIVITY = 0.33f

        /** struct input_event 在 64 位 Android 上：8B timeval + 2B type + 2B code + 4B value */
        private const val EVENT_SIZE = 24

        /**
         * EVIOCGRAB 的 ioctl 号（issue#168 三追第 4 条「拦截转发」的核心）：
         * `_IOC(_IOC_WRITE, 'E', 0x90, sizeof(int))` = 0x40044590。对一个已打开的
         * evdev 节点发它，内核从此**不再把该设备的事件派给其它读者** —— 安卓输入
         * 子系统（光标、点击）在内。这就是「读 ≠ 消费」问题的解：旧通路 `su -c cat`
         * 只是抄一份事件，安卓照收，触控板照样操作手机屏幕。
         */
        private const val EVIOCGRAB = 0x40044590
        private const val GRAB_ON = 1
        private const val GRAB_OFF = 0

        /** 设备节点的标准权限（root:input 660）：拦截开始前放成 666、停止时还原 */
        private const val NODE_MODE_OPEN = "666"
        private const val NODE_MODE_RESTORE = "660"

        /** 启动失败原因（[RootPointerBridge] 翻成用户可读的提示，界面不装成功） */
        const val REASON_NO_ROOT = "no_root"
        const val REASON_NO_DEVICE = "no_device"

        /**
         * 指针设备节点：路径 + 总线号（ABS 设备只认外接总线）。
         */
        class PointerNode(val path: File, val bus: Int, val name: String)

        /**
         * 读设备表：先直读 `/proc/bus/input/devices`，EACCES 等失败时改走
         * `su -c cat`（扫描也可能需要 root —— 旧实现在这一步静默失败，
         * 「实测未生效」的大头）。都拿不到返回 null。
         */
        private fun readDevicesText(): String? {
            try {
                return File("/proc/bus/input/devices").readText()
            } catch (t: Throwable) {
                Log.i(TAG, "设备表直读失败（改走 root）：${t.message}")
            }
            return try {
                val pr = ProcessBuilder("su", "-c", "cat /proc/bus/input/devices")
                    .redirectErrorStream(false)
                    .start()
                val done = pr.waitFor(5, TimeUnit.SECONDS)
                if (!done) {
                    runCatching { pr.destroy() }
                    null
                } else {
                    pr.inputStream.readBytes().toString(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "设备表 root 读取也失败：${t.message}")
                null
            }
        }

        /**
         * 从设备表挑出指针设备的 event 节点。
         *
         * 判据（对 REL 鼠标 / ABS 触控板分别适用）：
         *   - REL 设备（鼠标）：EV 位图含 EV_REL **且** KEY 位图含 BTN_LEFT；
         *     总线不限（内置 REL 指针设备在手机上实际不存在）。
         *   - ABS 触控板：EV 位图含 EV_ABS 且有 BTN_TOUCH / BTN_LEFT 触点，
         *     **且总线是 USB / 蓝牙** —— 内置触屏同样是 EV_ABS（Bus=0018 等），
         *     不按总线过滤会把「读自己屏幕」当成外接触控板（自激）。
         * 另外排除名字含 apx / allperiph 的自家虚拟设备，避免自激（读到自己发出的）。
         */
        fun findPointerNodes(): List<PointerNode> {
            val out = ArrayList<PointerNode>()
            val txt = readDevicesText() ?: return out
            for (blk in txt.split("\n\n")) {
                if (!blk.contains("Handlers=")) continue
                val name = Regex("N: Name=\"([^\"]*)\"").find(blk)?.groupValues?.get(1) ?: ""
                if (name.contains("apx", true) || name.contains("allperiph", true)) continue
                val handlers = Regex("H: Handlers=([^\n]*)").find(blk)?.groupValues?.get(1) ?: ""
                // eventN 是读裸事件的节点；没有它（纯 mouseN 的旧设备走 mousedev）就跳过
                val idx = Regex("event(\\d+)").find(handlers)?.groupValues?.get(1) ?: continue
                val bus = Regex("I: Bus=([0-9a-fA-F]{4})").find(blk)?.groupValues?.get(1)
                    ?.toInt(16) ?: 0
                val byMouse = Regex("mouse\\d+").containsMatchIn(handlers)
                val relCap = hasRelAxis(blk) && hasBtnLeft(blk)
                // ABS 触控板：绝对坐标 + 触点，且必须插在外接总线上
                val absTouchpad = hasAbsAxis(blk) && (hasBtnTouch(blk) || hasBtnLeft(blk)) &&
                    (bus == BUS_USB || bus == BUS_BLUETOOTH)
                if (!byMouse && !relCap && !absTouchpad) continue
                Log.i(
                    TAG,
                    "指针设备：$name -> event$idx（bus=" + String.format("%04x", bus) +
                        if (absTouchpad) "，ABS 触控板）" else "）",
                )
                out.add(PointerNode(File("/dev/input/event$idx"), bus, name))
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

        /** 绝对轴能力（EV_ABS = 0x03） */
        private fun hasAbsAxis(blk: String) = bitSet(bitmapGroups(blk, "EV"), 0x03)

        /** 左键能力（BTN_LEFT = 0x110）—— 把「有相对轴但没键」的怪设备也排掉 */
        private fun hasBtnLeft(blk: String) = bitSet(bitmapGroups(blk, "KEY"), 0x110)

        /** 触点能力（BTN_TOUCH = 0x14A）—— 触控板的按下/抬起信号 */
        private fun hasBtnTouch(blk: String) = bitSet(bitmapGroups(blk, "KEY"), 0x14A)
    }

    @Volatile private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val procs = ArrayList<Process>()

    /**
     * 已独占拦截的节点与打开流（EVIOCGRAB 持有中；stop 时逐个解除并还原权限）。
     * 独占后内核不再把该设备派给安卓输入子系统 —— 触控板不再操作手机屏幕。
     * 注意代价：拦截是「全程」的，电脑侧出口没就绪时 [RootPointerBridge.feed] 会
     * 丢弃事件，触控板在「开启直读但没连电脑」期间不会有任何反应 —— 重连即恢复。
     */
    private val grabbed = ArrayList<Pair<File, FileInputStream>>()

    /** 全部节点都拿到独占拦截（true）= 真正的「拦截转发」；false = 有节点落到只转发 */
    var interceptedAll: Boolean = false
        private set

    /** 启动失败原因（null = 启动成功或还没试过）；设置页据此如实提示 */
    var lastReason: String? = null
        private set

    /** 成功接管的节点名（eventN），用于状态提示 */
    var startedNodes: List<String> = emptyList()
        private set

    fun start(): Boolean {
        if (running) return true
        // root 探测放最前：拿不到 su 就别起子进程（否则每个节点都失败一次，日志刷屏，
        // 用户还看到「已开启」的假状态 —— issue #52 问题 5 的教训）。
        if (!hasRoot()) {
            lastReason = REASON_NO_ROOT
            Log.w(TAG, "没有 root，evdev 直读不可用")
            return false
        }
        val found = findPointerNodes()
        if (found.isEmpty()) {
            lastReason = REASON_NO_DEVICE
            Log.i(TAG, "没找到指针类输入设备，跳过 evdev 直读")
            return false
        }
        lastReason = null
        running = true
        val ht = HandlerThread("apx-evdev").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)
        // 拦截走同步（启动一次每节点约 1s 的 su chmod + open + ioctl，设置页本来就在
        // 等扫描的 su 返回，可接受）：拿到的流交后台线程读；拿不到的节点落回旧
        // root-cat 通路（只转发、不拦截，安卓侧仍会收到 —— 界面会如实说明）。
        val grabbedNow = ArrayList<File>()
        for (f in found) {
            val stream = grabDevice(f.path)
            if (stream != null) {
                synchronized(grabbed) { grabbed.add(f.path to stream) }
                grabbedNow.add(f.path)
                handler?.post { parseStream(stream, f.path.name) }
            } else {
                handler?.post { readRootCat(f.path) }
            }
        }
        startedNodes = found.map { it.path.name }
        interceptedAll = grabbedNow.size == found.size
        Log.i(TAG, "evdev 直读启动：${startedNodes.joinToString()}（${if (interceptedAll) "全部独占拦截" else "部分/全部仅转发，未独占"}）")
        return true
    }

    fun stop() {
        running = false
        synchronized(procs) {
            for (p in procs) runCatching { p.destroy() }
            procs.clear()
        }
        // 逐个解除独占、关流、还原设备权限（重启也会还原，但主动还原不留尾巴）
        synchronized(grabbed) {
            for ((f, stream) in grabbed) {
                try {
                    Os.ioctl(stream.fd, EVIOCGRAB, GRAB_OFF)
                } catch (t: Throwable) {
                    Log.w(TAG, "解除独占失败（关流后内核会自行释放）：${t.message}")
                }
                runCatching { stream.close() }
                runSu("chmod $NODE_MODE_RESTORE ${f.absolutePath}")
            }
            grabbed.clear()
        }
        interceptedAll = false
        thread?.quitSafely()
        thread = null
        handler = null
        startedNodes = emptyList()
    }

    /** root 可用性：起一次 `su -c true` 判成败（与 RootShell.open 同口径的轻量版） */
    private fun hasRoot(): Boolean = try {
        val pr = ProcessBuilder("su", "-c", "true").redirectErrorStream(true).start()
        val done = pr.waitFor(3, TimeUnit.SECONDS)
        if (!done) runCatching { pr.destroy() }
        done && pr.exitValue() == 0
    } catch (t: Throwable) {
        Log.w(TAG, "su 探测异常：${t.message}")
        false
    }

    /** 起一个 root 子进程跑单条命令（放/收设备节点权限用）：只等结束，不取输出 */
    private fun runSu(cmd: String) {
        try {
            val pr = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
            if (!pr.waitFor(3, TimeUnit.SECONDS)) {
                runCatching { pr.destroy() }
            } else {
                runCatching { pr.inputStream.readBytes() } // 排空管道，防个别 su 实现卡写端
            }
        } catch (t: Throwable) {
            Log.w(TAG, "su 执行失败：$cmd ${t.message}")
        }
    }

    /**
     * 独占拦截通路：root 放节点权限 → 本进程打开 → EVIOCGRAB 抢占。
     * 抢占成功返回保持打开的流（内核从此不把该设备派给其它读者，含安卓光标）；
     * 失败（SELinux 拦打开、设备被别人占着等）返回 null，由调用方落到
     * [readRootCat] 兜底 —— 那条能转发、不能拦截，界面会如实区分。
     */
    private fun grabDevice(f: File): FileInputStream? {
        try {
            runSu("chmod $NODE_MODE_OPEN ${f.absolutePath}")
            val stream = FileInputStream(f)
            try {
                Os.ioctl(stream.fd, EVIOCGRAB, GRAB_ON)
            } catch (t: Throwable) {
                runCatching { stream.close() }
                throw t
            }
            Log.i(TAG, "已独占拦截 ${f.name}（安卓侧不再收到该设备事件）")
            return stream
        } catch (t: Throwable) {
            Log.i(TAG, "独占拦截不可用，退回只转发：${f.name} ${t.message}")
            runSu("chmod $NODE_MODE_RESTORE ${f.absolutePath}")
            return null
        }
    }

    /** 旧通路：`su -c cat <节点>` 只抄一份事件（能转发、不能拦截）—— 拦截失败时的兜底 */
    private fun readRootCat(f: File) {
        var proc: Process? = null
        try {
            proc = ProcessBuilder("su", "-c", "cat ${f.absolutePath}")
                .redirectErrorStream(false)
                .start()
            synchronized(procs) { procs.add(proc) }
            parseStream(proc.inputStream, f.name)
        } finally {
            runCatching { proc?.destroy() }
            synchronized(procs) { procs.remove(proc) }
        }
    }

    /**
     * 事件解析主循环（两条通路共用）：从 [ins] 阻塞读 24 字节 struct input_event，
     * 拆出位移 / 按键 / 滚轮。拦截通路读的是本进程 fd，root-cat 通路读的是子进程 stdout，
     * 字节排布相同（见 [EVENT_SIZE]）。
     */
    private fun parseStream(ins: InputStream, nodeName: String) {
        try {
            DataInputStream(ins.buffered()).use { stream ->
                val buf = ByteArray(EVENT_SIZE)
                // REL 通路（鼠标）：mickey 直接累加
                var pdx = 0; var pdy = 0; var pwheel = 0; var ppan = 0
                // ABS 通路（触控板）：绝对坐标在本帧出现 REL 时不参与（混合设备只用 REL）
                var ax = 0; var ay = 0
                var baseX = 0; var baseY = 0
                var absBase = false            // 本轮接触已有基准点（抬手重置，避免跳变）
                var mtContact = false          // MT_TRACKING_ID >= 0 = slot0 手指在板
                var touchContact = false       // BTN_TOUCH（非 MT / MT 设备都可能有）
                var relSeen = false            // 本帧出现过 REL 事件
                var buttons = 0
                while (running) {
                    var read = 0
                    while (read < EVENT_SIZE) {
                        val n = stream.read(buf, read, EVENT_SIZE - read)
                        if (n < 0) return
                        read += n
                    }
                    val type = le16(buf, 16)
                    val code = le16(buf, 18)
                    val value = le32(buf, 20)
                    when (type) {
                        EV_REL -> when (code) {
                            REL_X -> { pdx += value; relSeen = true }
                            REL_Y -> { pdy += value; relSeen = true }
                            REL_WHEEL -> pwheel += value
                            REL_HWHEEL -> ppan += value
                        }
                        EV_ABS -> when (code) {
                            ABS_X -> ax = value
                            ABS_Y -> ay = value
                            ABS_MT_POSITION_X -> ax = value
                            ABS_MT_POSITION_Y -> ay = value
                            // MT_TRACKING_ID：只跟 0 号触点（多指只动第一指，单光标语义）
                            ABS_MT_TRACKING_ID -> {
                                mtContact = value >= 0
                                if (!mtContact) absBase = false
                            }
                        }
                        EV_KEY -> when (code) {
                            BTN_TOUCH -> {
                                touchContact = value != 0
                                if (!touchContact) absBase = false
                            }
                            in BTN_LEFT..BTN_MIDDLE -> {
                                buttons = when (code) {
                                    BTN_LEFT -> if (value != 0) buttons or 0x01 else buttons and 0x01.inv()
                                    BTN_RIGHT -> if (value != 0) buttons or 0x02 else buttons and 0x02.inv()
                                    else -> if (value != 0) buttons or 0x04 else buttons and 0x04.inv()
                                }
                                onButton(buttons)
                            }
                        }
                        EV_SYN -> {
                            if (pdx != 0 || pdy != 0) { onMove(pdx, pdy); pdx = 0; pdy = 0 }
                            if (pwheel != 0 || ppan != 0) { onWheel(pwheel, ppan); pwheel = 0; ppan = 0 }
                            // ABS 触控板合成位移：接触中且本帧无 REL 才参与；
                            // 接触开始的第一帧只记基准（绝对坐标差一整段距离，不合成）
                            val contact = mtContact || touchContact
                            if (!relSeen && contact) {
                                if (absBase) {
                                    val dx = ((ax - baseX) * ABS_SENSITIVITY).toInt()
                                    val dy = ((ay - baseY) * ABS_SENSITIVITY).toInt()
                                    if (dx != 0 || dy != 0) onMove(dx, dy)
                                    baseX = ax; baseY = ay
                                } else {
                                    baseX = ax; baseY = ay; absBase = true
                                }
                            } else if (!contact) {
                                absBase = false
                            }
                            relSeen = false
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "evdev 读取结束 $nodeName：${t.message}")
        }
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
