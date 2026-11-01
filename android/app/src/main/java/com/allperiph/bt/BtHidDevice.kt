package com.allperiph.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDevice.Callback
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.allperiph.core.ApxNative
import com.allperiph.shared.util.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * 蓝牙 HID 设备：手机注册为 HID 设备，PC 零驱动识别为鼠标/键盘/多媒体键/游戏手柄。
 * 描述符为裁剪版（鼠标 + 键盘 + 多媒体键 + 游戏手柄），不含传感器 TLC。
 *
 * 可与 USB 复合设备并存（手机同时做蓝牙触控板 + USB 副屏）。
 *
 * **运行时权限（API 31+）**：`registerApp` / `connect` / `sendReport` 均需
 * `BLUETOOTH_CONNECT`。缺失时**降级为 DEGRADED 并留日志**，绝不抛 SecurityException ——
 * 本回调运行在蓝牙 Binder 线程，未捕获异常会杀死整个进程（真机已复现）。
 */
class BtHidDevice(private val appContext: Context) : Module {
    override val id: String = ModuleId.BTHID
    @Volatile override var state: ModuleState = ModuleState.IDLE

    @Volatile var isConnected = false
        private set

    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false

    /** 已挂上蓝牙开关监听（只在 start 里挂一次，stop 里摘掉） */
    private var listening = false

    /**
     * 蓝牙开关监听 —— 为什么必须有（issue#52「蓝牙模式直接不能用，配对后系统还是识别为 pad/手机」）：
     * Android 在蓝牙关闭时会拆掉 profile proxy，注册的 HID SDP 随之失效。旧实现只挂一次监听、
     * 从不重注册，于是**用户为了配对去关一次蓝牙再开**，之后就永远是「手机」而不是「键鼠」，
     * 只有杀掉 App 重启模块才恢复。这里开关一动就重新注册，并把状态如实降级/恢复。
     */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "蓝牙已开启：重新注册 HID（配对后才会被主机识别为键鼠）")
                    state = ModuleState.STARTING
                    openProxy()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    hidDevice = null; registered = false; isConnected = false
                    state = ModuleState.DEGRADED
                    Log.w(TAG, "蓝牙已关闭：HID 注册失效，待蓝牙开启后自动恢复")
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) hidDevice = null
        }
    }

    private val callback = object : Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BtHidDevice.registered = registered
            state = if (registered) ModuleState.RUNNING else ModuleState.IDLE
            Log.i(TAG, "蓝牙 HID 注册状态=$registered")
            // 注册成功才有 SDP 可被主机发现；此时若本机已经配对过电脑 / 电视，主动发起一次
            // HID 连接 —— 见 connectBondedHost() 的注释（旧实现从来没人调 connect()）。
            if (registered) connectBondedHost()
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            isConnected = state == BluetoothProfile.STATE_CONNECTED
            Log.i(TAG, "蓝牙 HID 连接=${isConnected}")
        }
    }

    private fun hasConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun start(ctx: ModuleContext) {
        state = ModuleState.STARTING
        if (!hasConnect()) {
            state = ModuleState.DEGRADED
            Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限：蓝牙 HID 暂不可用，请在 UI 授权后重启模块")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { state = ModuleState.ERROR; Log.e(TAG, "无蓝牙适配器"); return }
        listenAdapter()
        if (!adapter.isEnabled) {
            // 旧实现不看开关：蓝牙关着时 getProfileProxy 拿不到回调，模块永远停在 STARTING，
            // 界面看着「在启动」其实什么都没发生。现在如实降级，开关一打就自动接上。
            state = ModuleState.DEGRADED
            Log.w(TAG, "蓝牙未开启：请先打开蓝牙，HID 会在开启后自动注册")
            return
        }
        openProxy()
    }

    /** 挂蓝牙开关监听（幂等） */
    private fun listenAdapter() {
        if (listening) return
        listening = true
        val f = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(adapterReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(adapterReceiver, f)
            }
        }.onFailure {
            listening = false
            Log.w(TAG, "蓝牙开关监听挂不上：${it.message}")
        }
    }

    /** 拿 HID_DEVICE profile proxy（幂等：已经拿到就不重复要，避免代理泄漏） */
    private fun openProxy() {
        if (hidDevice != null) { if (!registered) registerApp(); return }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        runCatching { adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE) }
            .onFailure { Log.w(TAG, "取 HID_DEVICE proxy 失败：${it.message}") }
    }

    override fun stop() {
        if (listening) {
            listening = false
            runCatching { appContext.unregisterReceiver(adapterReceiver) }
        }
        runCatching { hidDevice?.unregisterApp() }
        runCatching { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice) }
        hidDevice = null; registered = false; isConnected = false
        state = ModuleState.STOPPED
    }

    private fun registerApp() {
        if (!hasConnect()) {
            state = ModuleState.DEGRADED
            Log.w(TAG, "registerApp 时 BLUETOOTH_CONNECT 已被撤销，跳过注册")
            return
        }
        runCatching {
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 11250)
            val desc = if (ApxNative.isAvailable) {
                runCatching { ApxNative.btReportDescriptor() }.getOrNull() ?: BtHidDescriptor.bytes
            } else {
                BtHidDescriptor.bytes
            }
            val smi = BluetoothHidDeviceAppSdpSettings(
                "AllPeriph", "AllPeriph HID", "AllPeriph",
                BluetoothHidDevice.SUBCLASS1_COMBO, desc)
            // 主机是靠这四样东西（SDP 名 / 子类 / 描述符里的 TLC 顺序）判断「这是键鼠还是手机」的，
            // 出问题时要能一眼看出注册了什么，所以逐项落日志（不进 UI 文案）。
            Log.i(TAG, "注册 HID：subclass=0x%02X(COMBO) 描述符=%d字节 TLC顺序=鼠标1 键盘2 多媒体3 手柄4"
                .format(BluetoothHidDevice.SUBCLASS1_COMBO, desc.size))
            hidDevice?.registerApp(smi, null, qos, { it.run() }, callback)
        }.onFailure {
            state = ModuleState.ERROR
            Log.e(TAG, "registerApp failed: ${it.message}")
        }
    }

    /**
     * 主动发起 HID 连接到指定主机。
     *
     * 注意：这个方法长期是**死代码**（全仓无调用点），正是 issue#52「配对之后系统还是识别为
     * pad/手机，做不了 hid 键鼠」的直接原因之一 —— Android 只把 SDP 注册上去，
     * HID profile 的连接必须由某一端主动 connect，而两端当时都没人做。
     */
    fun connect(host: BluetoothDevice) {
        runCatching { if (hasConnect()) hidDevice?.connect(host) }
    }

    /**
     * 向「已配对的电脑 / 电视」自动补一次 HID 连接（注册成功后、蓝牙重新打开后各调一次）。
     *
     * 只认 majorDeviceClass = COMPUTER 或 AUDIO_VIDEO：不往耳机 / 车机 / 另一部手机上乱连。
     * 失败不重试 —— 主机可能正忙、可能已删过设备；下一次注册或开关蓝牙会再试。
     * 系统若仍显示成「手机」，说明那台主机的配对记录是在 App 注册 HID **之前**建立的，
     * 主机缓存了旧的设备类型：需要在主机端删掉本机再重新配对（App 侧改不了别人的缓存）。
     */
    private fun connectBondedHost() {
        if (!hasConnect()) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val hosts = runCatching { adapter.bondedDevices }.getOrNull().orEmpty().filter { d ->
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull() ?: -1
            major == BluetoothClass.Device.Major.COMPUTER ||
                major == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
        if (hosts.isEmpty()) {
            Log.i(TAG, "没有已配对的电脑/电视主机；若主机只显示「手机」，说明旧配对早于 HID 注册，需删掉重配")
            return
        }
        for (h in hosts) {
            val name = runCatching { h.name }.getOrNull() ?: h.address
            Log.i(TAG, "向已配对主机发起 HID 连接：$name")
            runCatching { hidDevice?.connect(h) }
                .onFailure { Log.w(TAG, "HID 连接 $name 失败：${it.message}") }
        }
    }

    fun reportMouse(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
        if (!isConnected || !hasConnect()) return
        // v186 修复：sendReport(reportId, data) 的 reportId 已由第二个参数负责，
        // data 只需纯载荷，不再包含 Report ID 字节。
        val r = byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte(), pan.toByte())
        runCatching { hidDevice?.sendReport(null, 0x01, r) }
    }

    /**
     * 蓝牙键盘报告（Report ID 2）。
     * @param mod 修饰键位图（bit0=LCtrl .. bit7=RGUI）
     * @param keys 最多 6 个 HID Usage Code（USB HID Boot Protocol 格式）
     */
    fun reportKeyboard(mod: Int, keys: ByteArray) {
        if (!isConnected || !hasConnect()) return
        // v184：报告必须是 8 字节 —— modifier(1) + reserved(1) + 6 个按键码。
        // v186 修复：sendReport 的 reportId=0x02 已负责 Report ID，
        // data 不再包含 Report ID 字节（旧 r[0]=0x02 会重复）。
        val r = ByteArray(8)
        r[0] = mod.toByte()
        for (i in 0 until minOf(keys.size, 6)) {
            r[2 + i] = keys[i]
        }
        runCatching { hidDevice?.sendReport(null, 0x02, r) }
            .onFailure { Log.w(TAG, "reportKeyboard failed: ${it.message}") }
    }

    fun reportConsumer(keyBitmap: Int) {
        if (!isConnected || !hasConnect()) return
        val r = byteArrayOf((keyBitmap and 0xFF).toByte(), ((keyBitmap shr 8) and 0xFF).toByte())
        runCatching { hidDevice?.sendReport(null, 0x03, r) }
            .onFailure { Log.w(TAG, "reportConsumer failed: ${it.message}") }
    }

    /**
     * 蓝牙游戏手柄报告（Report ID 4）。
     * @param buttons 16 位按钮位图（bit0..15 → 按钮 1..16）
     * @param x 左摇杆 X（-127..127，0=回中）
     * @param y 左摇杆 Y（-127..127，0=回中）
     * @param rx 右摇杆 X（-127..127，0=回中）
     * @param ry 右摇杆 Y（-127..127，0=回中）
     */
    fun reportGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
        if (!isConnected || !hasConnect()) return
        // 格式：[buttons_lo, buttons_hi, x, y, rx, ry] = 6 bytes（Report ID 由 sendReport 负责）
        val r = byteArrayOf(
            (buttons and 0xFF).toByte(),
            ((buttons shr 8) and 0xFF).toByte(),
            x.coerceIn(-127, 127).toByte(),
            y.coerceIn(-127, 127).toByte(),
            rx.coerceIn(-127, 127).toByte(),
            ry.coerceIn(-127, 127).toByte(),
        )
        runCatching { hidDevice?.sendReport(null, 0x04, r) }
            .onFailure { Log.w(TAG, "reportGamepad failed: ${it.message}") }
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> if (isConnected) "蓝牙 HID 已连接（鼠标/键盘/多媒体/手柄）" else "蓝牙 HID 已注册（待 PC 配对）"
        ModuleState.STARTING -> "蓝牙 HID 启动中"
        ModuleState.DEGRADED -> "蓝牙 HID 权限不足"
        ModuleState.ERROR -> "蓝牙 HID 错误：无蓝牙适配器"
        ModuleState.STOPPING -> "蓝牙 HID 停止中"
        ModuleState.STOPPED -> "蓝牙 HID 已停止"
        ModuleState.IDLE -> "蓝牙 HID 未启动"
        else -> state.name
    }

    override fun maskBits(): Long = if (state.isActive) (1L shl 34) else 0

    companion object { private const val TAG = "BtHidDevice" }
}

/**
 * 蓝牙 HID 描述符（fallback 版本）。
 * 仅当 ApxNative 不可用（libapx.so 未加载）时使用；
 * native 版描述符通过 [ApxNative.btReportDescriptor] 获取，保证与 shared/ 协议库一致。
 * 含四个 TLC：Mouse(rid=1) + Keyboard(rid=2) + Consumer(rid=3) + Gamepad(rid=4)。
 *
 * v186 修复：当存在多个 Report 时，HID 规范要求每个 TLC 必须声明 Report ID。
 * 旧版 Mouse/Keyboard/Consumer 缺少 0x85,0x0N，fallback 路径下 PC 无法正确解析。
 */
object BtHidDescriptor {
    val bytes: ByteArray = byteArrayOf(
        // ---- TLC 1: Mouse (rid=1) ----
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte(),
        // ---- TLC 2: Keyboard (rid=2) ----
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03,
        0x05, 0x07, 0x19, 0x01, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x75, 0x08, 0x95.toByte(), 0x06, 0x81.toByte(), 0x00,
        0xC0.toByte(),
        // ---- TLC 3: Consumer (rid=3) ----
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03,
        0x19, 0x00, 0x2A, 0x9C.toByte(), 0x02, 0x15, 0x01, 0x26, 0x9C.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x10, 0x81.toByte(), 0x00,
        0xC0.toByte(),
        // ---- TLC 4: Gamepad (rid=4) ----
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x04,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0xC0.toByte(),
    )
}