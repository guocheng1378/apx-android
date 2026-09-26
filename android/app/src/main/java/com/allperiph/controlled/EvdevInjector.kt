package com.allperiph.controlled

import android.os.Build
import android.view.KeyEvent
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.allperiph.core.Log

/**
 * **evdev 内核级按键注入（手机被控端）**：免 root、免无障碍、免 `INJECT_EVENTS`。
 * 与 TV 端 `com.allperiph.tv.core.EvdevInjector` 同一套做法（那边注释更详细）。
 *
 * 原理：往 `/dev/input/eventN` 写 `struct input_event`，内核就当成"该设备产生了事件"
 * 直接注入 —— 与 `sendevent` 等价，注入出来的按键和真遥控器 / 蓝牙键鼠同级。
 * 「八爪鱼遥控TV」用的也是这条路（`libEventInjector.so`）。
 *
 * 两个必须注意的点：
 *  1. **Android `KEYCODE_*` 与 Linux `KEY_*` 不是同一套编号**，且**每个输入设备用哪份
 *     `.kl` 是自己的事** —— 所以按目标设备的 Vendor/Product 读它的 `.kl`，再反射
 *     `KeyEvent.KEYCODE_<名字>` 反查，绝不硬编码（换机型会错）。
 *  2. `struct input_event` 大小随进程位数变：32 位 16 字节、64 位 24 字节。
 *
 * 节点不可写（多数手机 `/dev/input/event*` 是 `0660 root:input`，普通 App 不在 input 组）
 * 时 [available] 为 false，调用方自动退回 root / 无障碍 —— 所以这是"多一层兜底"，
 * 而不是替代 root 通道。
 */
object EvdevInjector {

    private const val TAG = "EvdevInjector"

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val SYN_REPORT = 0

    @Volatile
    private var out: FileOutputStream? = null

    @Volatile
    var devicePath: String = ""
        private set

    @Volatile
    var klPath: String = ""
        private set

    @Volatile
    private var linuxOf: Map<Int, Int> = emptyMap()

    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    val available: Boolean
        get() = out != null

    private val structSize: Int by lazy {
        val is64 = if (Build.VERSION.SDK_INT >= 23) {
            android.os.Process.is64Bit()
        } else {
            System.getProperty("os.arch")?.contains("64") == true
        }
        if (is64) 24 else 16
    }

    /** 选一个"支持按键"的输入设备并打开。**要在后台线程调用**（有文件 IO）。幂等。 */
    fun tryStart(): Boolean {
        if (available) return true
        if (!starting.compareAndSet(false, true)) return available
        try {
            val dev = pickKeyboardDevice() ?: run {
                Log.i(TAG, "evdev：没找到支持按键的输入设备 → 按键走 root / 无障碍")
                return false
            }
            val f = File("/dev/input/${dev.event}")
            val s = try {
                FileOutputStream(f)
            } catch (t: Throwable) {
                // 多数手机是 0660 root:input，普通 App 打不开 —— 这是预期内的降级，不是故障
                Log.i(TAG, "evdev：${f.path} 不可写（${t.message}）→ 按键走 root / 无障碍")
                return false
            }
            out = s
            linuxOf = buildKeyMap(dev.vendor, dev.product)
            devicePath = f.path
            Log.i(TAG, "evdev 内核注入已就绪：$devicePath，键位表 $klPath，可映射 ${linuxOf.size} 个安卓键码")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "evdev 初始化异常：${t.message}")
            return false
        } finally {
            starting.set(false)
        }
    }

    /** 注入按键的按下 / 松开。返回 true 表示已交给 evdev（调用方不要再走其它通道）。 */
    fun sendKey(androidKeyCode: Int, down: Boolean): Boolean {
        val code = linuxOf[androidKeyCode] ?: return false
        return write(EV_KEY, code, if (down) 1 else 0)
    }

    /** 一次完整点按（down + up） */
    fun tapKey(androidKeyCode: Int): Boolean {
        val code = linuxOf[androidKeyCode] ?: return false
        return write(EV_KEY, code, 1) && write(EV_KEY, code, 0)
    }

    /** 普通字符 → 真按键（a-z / A-Z / 0-9 / 空格）；其它字符返回 false 交给输入法 */
    fun sendChar(ch: Char): Boolean {
        val kc = when {
            ch in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
            ch in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
            ch in '0'..'9' -> if (ch == '0') KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + (ch - '1')
            ch == ' ' -> KeyEvent.KEYCODE_SPACE
            else -> return false
        }
        val up = ch in 'A'..'Z'
        if (up && !write(EV_KEY, 42 /* KEY_LEFTSHIFT */, 1)) return false
        val ok = tapKey(kc)
        if (up) write(EV_KEY, 42, 0)
        return ok
    }

    fun stop() {
        runCatching { out?.close() }
        out = null
        devicePath = ""
    }

    // ————————————————————————————— 底层 —————————————————————————————

    @Synchronized
    private fun write(type: Int, code: Int, value: Int): Boolean {
        val s = out ?: return false
        return try {
            s.write(frame(type, code, value))
            s.write(frame(EV_SYN, SYN_REPORT, 0))   // 每批事件必须跟 SYN_REPORT 才交给上层
            s.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "evdev 写入失败（$devicePath）：${t.message}")
            runCatching { s.close() }
            out = null
            false
        }
    }

    private fun frame(type: Int, code: Int, value: Int): ByteArray {
        val b = ByteArray(structSize)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val now = System.currentTimeMillis()
        if (structSize == 24) {
            bb.putLong(now / 1000)
            bb.putLong((now % 1000) * 1000)
        } else {
            bb.putInt((now / 1000).toInt())
            bb.putInt(((now % 1000) * 1000).toInt())
        }
        bb.putShort(type.toShort())
        bb.putShort(code.toShort())
        bb.putInt(value)
        return b
    }

    // ————————————————————————————— 设备与键位表 —————————————————————————————

    private class Dev(val event: String, val vendor: Int, val product: Int, val keyBits: Int)

    private fun pickKeyboardDevice(): Dev? {
        val text = try {
            File("/proc/bus/input/devices").readText()
        } catch (t: Throwable) {
            Log.i(TAG, "evdev：读不到 /proc/bus/input/devices：${t.message}")
            return null
        }
        var best: Dev? = null
        for (block in text.split("\n\n")) {
            val vendor = Regex("Vendor=([0-9a-fA-F]{4})").find(block)
                ?.groupValues?.get(1)?.toIntOrNull(16) ?: continue
            val product = Regex("Product=([0-9a-fA-F]{4})").find(block)
                ?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
            val handlers = Regex("Handlers=(.*)").find(block)?.groupValues?.get(1) ?: continue
            if (!handlers.contains("kbd")) continue
            val event = Regex("(event\\d+)").find(handlers)?.groupValues?.get(1) ?: continue
            val bits = Regex("B: KEY=(.*)").findAll(block)
                .sumOf { m -> m.groupValues[1].count { it != '0' && it != ' ' } }
            val dev = Dev(event, vendor, product, bits)
            if (best == null || dev.keyBits > best!!.keyBits) best = dev
        }
        return best
    }

    private fun buildKeyMap(vendor: Int, product: Int): Map<Int, Int> {
        val vendorKl = File("/system/usr/keylayout/Vendor_%04x_Product_%04x.kl".format(vendor, product))
        val generic = File("/system/usr/keylayout/Generic.kl")
        val file = when {
            vendorKl.exists() -> vendorKl
            generic.exists() -> generic
            else -> null
        }
        val map = HashMap<Int, Int>()
        if (file != null) {
            runCatching {
                file.forEachLine { raw ->
                    val line = raw.substringBefore('#').trim()
                    if (!line.startsWith("key ")) return@forEachLine
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 3) return@forEachLine
                    val linuxCode = parts[1].toIntOrNull() ?: return@forEachLine
                    val android = keyCodeOf(parts[2]) ?: return@forEachLine
                    if (!map.containsKey(android)) map[android] = linuxCode
                }
            }.onFailure { Log.w(TAG, "evdev：解析键位表失败（${file.path}）：${it.message}") }
            klPath = file.path
        }
        if (map.isEmpty()) map.putAll(FALLBACK)
        return map
    }

    private fun keyCodeOf(name: String): Int? = try {
        KeyEvent::class.java.getField("KEYCODE_$name").getInt(null)
    } catch (_: Throwable) {
        ALIAS[name]
    }

    private val ALIAS = mapOf(
        "MOVE_HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "MOVE_END" to KeyEvent.KEYCODE_MOVE_END,
        "SOFT_LEFT" to KeyEvent.KEYCODE_SOFT_LEFT,
        "SOFT_RIGHT" to KeyEvent.KEYCODE_SOFT_RIGHT,
    )

    /** 兜底键位表（`.kl` 读不到时才用；AOSP Generic.kl 的桌面键盘扫描码） */
    private val FALLBACK = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to 103,
        KeyEvent.KEYCODE_DPAD_DOWN to 108,
        KeyEvent.KEYCODE_DPAD_LEFT to 105,
        KeyEvent.KEYCODE_DPAD_RIGHT to 106,
        KeyEvent.KEYCODE_DPAD_CENTER to 353,
        KeyEvent.KEYCODE_ENTER to 28,
        KeyEvent.KEYCODE_BACK to 158,
        KeyEvent.KEYCODE_HOME to 172,
        KeyEvent.KEYCODE_MENU to 139,
        KeyEvent.KEYCODE_DEL to 111,
        KeyEvent.KEYCODE_SPACE to 57,
        KeyEvent.KEYCODE_TAB to 15,
        KeyEvent.KEYCODE_ESCAPE to 1,
        KeyEvent.KEYCODE_POWER to 116,
        KeyEvent.KEYCODE_VOLUME_UP to 115,
        KeyEvent.KEYCODE_VOLUME_DOWN to 114,
        KeyEvent.KEYCODE_VOLUME_MUTE to 113,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 164,
        KeyEvent.KEYCODE_MEDIA_NEXT to 163,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to 165,
        KeyEvent.KEYCODE_MEDIA_STOP to 166,
        KeyEvent.KEYCODE_SHIFT_LEFT to 42,
        KeyEvent.KEYCODE_CTRL_LEFT to 29,
        KeyEvent.KEYCODE_ALT_LEFT to 56,
    )
}
