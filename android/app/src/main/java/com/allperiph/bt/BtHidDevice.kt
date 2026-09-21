package com.allperiph.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDevice.Callback
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * 蓝牙 HID 设备：手机注册为 HID 设备，PC 零驱动识别为鼠标/键盘/多媒体键。
 * 描述符为裁剪版（鼠标 + 键盘 + 多媒体键），不含传感器 TLC，远小于 USB 版（架构 §3）。
 *
 * 可与 USB 复合设备并存（手机同时做蓝牙触控板 + USB 副屏）。
 *
 * **运行时权限（API 31+）**：`registerApp` / `connect` / `sendReport` 均需
 * `BLUETOOTH_CONNECT`。缺失时**降级为 DEGRADED 并留日志**，绝不抛 SecurityException ——
 * 本回调运行在蓝牙 Binder 线程，未捕获异常会杀死整个进程（真机已复现）。
 * 权限请求由 UI 层（MainActivity）完成。
 */
class BtHidDevice(private val appContext: Context) : Module {
    override val id: String = ModuleId.BTHID
    @Volatile override var state: ModuleState = ModuleState.IDLE

    @Volatile var isConnected = false
        private set

    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false

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
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            isConnected = state == BluetoothProfile.STATE_CONNECTED
            Log.i(TAG, "蓝牙 HID 连接=${isConnected}")
        }
    }

    /** API 31+ 起蓝牙接口需要 BLUETOOTH_CONNECT 运行时权限（UI 层负责请求） */
    private fun hasConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun start(ctx: ModuleContext) {
        state = ModuleState.STARTING
        if (!hasConnect()) {
            // 如实降级：UI 尚未授予蓝牙权限时不启动，也不抛异常（Binder 回调线程会杀进程）
            state = ModuleState.DEGRADED
            Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限：蓝牙 HID 暂不可用，请在 UI 授权后重启模块")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { state = ModuleState.ERROR; Log.e(TAG, "无蓝牙适配器"); return }
        adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
    }

    override fun stop() {
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
        // AppSdpSettings / AppQosSettings 是 android.bluetooth 包的顶层类（API 28+）；
        // descriptors = 完整 HID 报告描述符字节流（buildBtReportDescriptor 同源）。
        // 本调用在蓝牙 Binder 回调线程执行：任何 SecurityException 都不能外抛。
        runCatching {
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 11250)
            val smi = BluetoothHidDeviceAppSdpSettings(
                "AllPeriph", "AllPeriph HID", "AllPeriph",
                BluetoothHidDevice.SUBCLASS1_COMBO, BtHidDescriptor.bytes)
            hidDevice?.registerApp(smi, null, qos, { it.run() }, callback)
        }.onFailure {
            state = ModuleState.ERROR
            Log.e(TAG, "registerApp failed: ${it.message}")
        }
    }

    /** PC 蓝牙地址经面板输入或扫码获得；连接后 PC 即可接收 HID 报告 */
    fun connect(host: BluetoothDevice) {
        runCatching { if (hasConnect()) hidDevice?.connect(host) }
    }

    /** 发送鼠标报告（相对位移）。与 PC 端 MouseFrame 对齐 */
    fun reportMouse(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
        if (!isConnected || !hasConnect()) return
        val r = byteArrayOf(0x01, buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte(), pan.toByte())
        runCatching { hidDevice?.sendReport(null, 0x01, r) }
    }

    /**
     * 发送 Consumer 按键位图（媒体键）。蓝牙版描述符的 Consumer TLC Report ID = 3
     * （§2.11：1=Mouse 2=Keyboard 3=Consumer），sendReport 的 data 不含 reportId，
     * 只含 u16 位图（LE）。按下与释放均发全量位图，PC 按位比对得边沿。
     */
    fun reportConsumer(keyBitmap: Int) {
        if (!isConnected || !hasConnect()) return
        val r = byteArrayOf((keyBitmap and 0xFF).toByte(), ((keyBitmap shr 8) and 0xFF).toByte())
        runCatching { hidDevice?.sendReport(null, 0x03, r) }
            .onFailure { Log.w(TAG, "reportConsumer failed: ${it.message}") }
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> if (isConnected) "蓝牙 HID 已连接（鼠标/键盘/多媒体）" else "蓝牙 HID 已注册（待 PC 配对）"
        ModuleState.STARTING -> "注册中…"
        ModuleState.ERROR -> "无蓝牙适配器"
        else -> state.name
    }

    override fun maskBits(): Long = if (state.isActive) (1L shl 34) else 0  // §2.9 蓝牙 HID 位

    companion object { private const val TAG = "BtHidDevice" }
}

/** 蓝牙 HID 裁剪版描述符（鼠标 + 键盘 + 多媒体键），与 shared/src/hid_descriptor.cpp buildBtReportDescriptor 同义 */
object BtHidDescriptor {
    val bytes: ByteArray = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte(),
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03,
        0x05, 0x07, 0x19, 0x01, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x75, 0x08, 0x95.toByte(), 0x06, 0x81.toByte(), 0x00,
        0xC0.toByte(),
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x19, 0x00, 0x2A, 0x9C.toByte(), 0x02, 0x15, 0x01, 0x26, 0x9C.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x10, 0x81.toByte(), 0x00,
        0xC0.toByte(),
    )
}
