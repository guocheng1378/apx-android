package com.allperiph.gadget

import android.content.Context
import com.allperiph.core.AgentRuntime
import com.allperiph.core.AgentStatus
import com.allperiph.core.ApxNative
import com.allperiph.core.EventBus
import com.allperiph.core.FfsChannel
import com.allperiph.core.GadgetConst
import com.allperiph.core.GadgetStateEvent
import com.allperiph.core.HidFeature
import com.allperiph.core.HEARTBEAT_INTERVAL_MS
import com.allperiph.core.HEARTBEAT_TIMEOUT_MS
import com.allperiph.core.HidTransport
import com.allperiph.core.LinkSpeed
import com.allperiph.core.LinkSpeedDegradedEvent
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.SysPath
import com.allperiph.core.UsbId
import com.allperiph.core.VendorCmd
import com.allperiph.core.VendorCommandEvent
import com.allperiph.core.decodeVendorCommand
import java.io.File

/**
 * M2 复合设备管理器：ConfigFS 生命周期（探测 → 抢占 → 挂载 → 自愈 → 卸载）。
 *
 * 同时充当**传输实现**（[HidTransport] + [SerialSink]），挂载成功后注入 [AgentRuntime]，
 * 业务模块拿到的代理无需重建。
 */
class GadgetManager(
    private val app: Context,
    private val runtime: AgentRuntime,
) : Module, HidTransport {

    override val id: String = ModuleId.GADGET

    @Volatile
    override var state: ModuleState = ModuleState.IDLE
        private set

    private val lock = Any()

    private var shell: RootShell? = null
    private var arbiter: UsbHalArbiter? = null
    private var hidDev: HidDevice? = null
    private var serialDev: SerialDevice? = null
    private var watchdog: Thread? = null

    @Volatile
    private var udc: UdcInfo? = null

    private var currentOptions: GadgetOptions? = null

    @Volatile
    private var linkSpeed: LinkSpeed = LinkSpeed.UNKNOWN

    @Volatile
    private var lastHeartbeatMs = 0L

    @Volatile
    private var everConnected = false

    @Volatile
    private var lastError: String? = null

    override fun start(ctx: ModuleContext) {
        synchronized(lock) {
            if (state.isActive) return
            state = ModuleState.STARTING
            try {
                val sh = RootShell.open()
                if (sh == null) {
                    fail("未获得 root 权限：无法操作 ConfigFS")
                    return
                }
                shell = sh

                val all = UdcProbe.list(sh)
                val best = UdcProbe.pickBest(all)
                if (best == null) {
                    fail("未找到可用 UDC（内核未启用 ConfigFS gadget 或不支持 device 模式）")
                    return
                }
                udc = best
                linkSpeed = best.speed
                if (!best.speed.isSuperSpeed) {
                    val msg = "当前链路为 ${best.rawSpeed}，非 USB 3.0 SuperSpeed：副屏/多路高清受限，已降级运行"
                    Log.w(TAG, msg)
                    EventBus.post(LinkSpeedDegradedEvent(best.speed, msg))
                }

                val arb = UsbHalArbiter(sh)
                arbiter = arb
                arb.acquire()

                val descBytes = ApxNative.hidReportDescriptorOrNull()
                if (descBytes == null || descBytes.isEmpty()) {
                    fail("shared/ 未提供 HID 报告描述符（libapx.so 未加载或接口缺失）")
                    arb.release()
                    return
                }
                val descFile = stageDescriptor(descBytes)
                if (descFile == null) {
                    fail("HID 报告描述符暂存失败")
                    arb.release()
                    return
                }

                val systemGadget = "g1"
                val systemConfig = "b.1"
                Log.i(TAG, "build=reuse-v2，优先复用 $systemGadget/$systemConfig")

                val ownRoot = resolveConfigfsRoot(sh)
                FfsChannel.close()
                runSteps(ConfigFsLayout.forceClean(GadgetOptions(configfsRoot = ownRoot)), sh)
                val base = {
                    GadgetOptions(
                        bcdUsb = if (best.speed.isSuperSpeed) UsbId.BCD_USB_30 else UsbId.BCD_USB_20,
                        reportLength = ApxNative.hidMaxReportLengthOrDefault(),
                        reportDescSource = descFile,
                    )
                }
                Log.i(TAG, "build=own-v3，直接自建 gadget（复用 g1 已证实不可行）")
                val options = base().copy(configfsRoot = resolveConfigfsRoot(sh))
                val mounted = runSteps(ConfigFsLayout.mount(options, best.name), sh)
                if (!mounted) {
                    val diag = sh.exec("dmesg 2>/dev/null | tail -40 | tr '\\n' ' '")
                    Log.e(TAG, "内核日志：${diag.out.take(400)}")
                    val udcState = sh.exec(
                        "for u in /sys/class/udc/*/state; do echo \"\$u=[\$(cat \"\$u\" 2>&1)]\"; done",
                    )
                    Log.e(TAG, "UDC 状态：${udcState.out.take(300)}")
                    fail("ConfigFS 挂载失败")
                    return
                }
                currentOptions = options

                val hid = HidDevice()
                if (!hid.open()) {
                    fail("${SysPath.HIDG_DEVICE} 打不开（权限或 SELinux 限制）")
                    return
                }
                hid.setReportListener { onHidReport(it) }
                hidDev = hid

                registerSensorFeatureReports()
                registerPtpFeatureReports()

                val ser = SerialDevice()
                if (!ser.open()) {
                    Log.w(TAG, "${SysPath.ACM_DEVICE} 打开失败：GPS over ACM 不可用，其余功能继续")
                } else {
                    serialDev = ser
                    runtime.attachSerial(ser)
                }

                runtime.attachHid(this)

                startFfsDescriptorWriter()

                lastError = null
                state = if (best.speed.isSuperSpeed) ModuleState.RUNNING else ModuleState.DEGRADED
                publishState("mounted on ${best.name} (${best.rawSpeed})")
                startWatchdog()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                fail(t.message ?: "unknown")
            }
        }
    }

    private fun startFfsDescriptorWriter() {
        val sh = shell ?: return
        val probe = sh.exec("test -e '${SysPath.FFS_EP0}' && echo yes")
        if (!probe.out.contains("yes")) {
            Log.w(TAG, "FunctionFS ep0 不存在（内核无 f_fs 或挂载失败）：副屏 bulk 通道不可用")
            return
        }
        kotlin.concurrent.thread(start = true, name = "apx-ffs-desc") {
            val rc = FfsChannel.writeDescriptors()
            sh.exec(
                "f=/data/local/tmp/apx_ffs.txt; " +
                    "echo 'rc=$rc' > \$f; " +
                    "echo '== functions ==' >> \$f; " +
                    "ls -1 /config/usb_gadget/${GadgetConst.GADGET_NAME}/functions >> \$f 2>&1; " +
                    "echo '== ffs mount dir ==' >> \$f; " +
                    "ls -l ${SysPath.FFS_MOUNT_DIR} >> \$f 2>&1; " +
                    "chmod 644 \$f",
            )
            if (rc == 0) {
                Log.i(TAG, "FunctionFS 描述符已写入：副屏 bulk 端点（ep1 下行 / ep2 上行）就绪")
            } else {
                Log.w(TAG, "FunctionFS 描述符写入失败 rc=$rc")
            }
        }
    }

    private fun registerSensorFeatureReports() {
        Log.i(TAG, "传感器 TLC 已移出描述符（蓝屏规避），跳过 Feature 登记")
        return
    }

    private fun registerPtpFeatureReports() {
        Log.i(TAG, "PTP 描述符已移除，跳过 Feature 登记")
        return
    }

    private fun stageDescriptor(bytes: ByteArray): String? {
        return try {
            val dir = File(app.filesDir, SysPath.APP_STAGING_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val f = File(dir, DESC_FILE_NAME)
            f.writeBytes(bytes)
            val sh = shell ?: return null
            sh.makeDirs(SysPath.SHARED_TMP_DIR)
            if (!sh.pushFile(f, SysPath.SHARED_TMP_DESC, "0644")) {
                Log.w(TAG, "push descriptor failed")
                return null
            }
            SysPath.SHARED_TMP_DESC
        } catch (t: Throwable) {
            Log.e(TAG, "stage descriptor failed", t)
            null
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun resolveConfigfsRoot(sh: RootShell): String = SysPath.CONFIGFS_USB_GADGET

    private fun runSteps(steps: List<Step>, sh: RootShell): Boolean {
        for (s in steps) {
            val r = sh.exec(s.cmd)
            if (!r.ok && !s.optional) {
                Log.e(TAG, "step failed: ${s.cmd} (code=${r.exitCode} out=${r.out})")
                return false
            }
            if (!r.ok) Log.w(TAG, "optional step failed: ${s.cmd}")
        }
        return true
    }

    private fun onHidReport(report: ByteArray) {
        val cmd = decodeVendorCommand(report) ?: return
        if (cmd.cmd == VendorCmd.HEARTBEAT) {
            lastHeartbeatMs = android.os.SystemClock.elapsedRealtime()
            everConnected = true
        }
        runtime.beginCommand(cmd.seq)
        EventBus.post(VendorCommandEvent(cmd))
        runtime.endCommand()
    }

    private fun heal(reason: String) {
        synchronized(lock) {
            val sh = shell
            val best = udc
            if (sh == null || best == null || sh.isBroken) {
                Log.w(TAG, "heal skipped: shell=${sh != null} udc=${best?.name}")
                return
            }
            Log.w(TAG, "heal: $reason")
            closeDevices()
            val options = GadgetOptions(
                bcdUsb = if (best.speed.isSuperSpeed) UsbId.BCD_USB_30 else UsbId.BCD_USB_20,
                reportLength = ApxNative.hidMaxReportLengthOrDefault(),
                reportDescSource = SysPath.SHARED_TMP_DESC,
            )
            runSteps(ConfigFsLayout.forceClean(options), sh)
            if (!runSteps(ConfigFsLayout.mount(options, best.name), sh)) {
                Log.e(TAG, "heal: remount failed")
                return
            }
            val hid = HidDevice()
            if (hid.open()) {
                hid.setReportListener { onHidReport(it) }
                hidDev = hid
                registerSensorFeatureReports()
                startFfsDescriptorWriter()
                runtime.attachHid(this)
            }
            val ser = SerialDevice()
            if (ser.open()) {
                serialDev = ser
                runtime.attachSerial(ser)
            }
            lastHeartbeatMs = android.os.SystemClock.elapsedRealtime()
            publishState("healed: $reason")
        }
    }

    private fun closeDevices() {
        runtime.detachHid()
        runtime.detachSerial()
        hidDev?.close()
        hidDev = null
        serialDev?.close()
        serialDev = null
    }

    private fun startWatchdog() {
        val t = kotlin.concurrent.thread(start = true, name = "apx-gadget-wd") {
            while (state.isActive) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                sendStatusReport()
                val elapsed = android.os.SystemClock.elapsedRealtime() - lastHeartbeatMs
                if (everConnected && lastHeartbeatMs > 0 && elapsed > HEARTBEAT_TIMEOUT_MS) {
                    heal("heartbeat lost ${elapsed}ms")
                }
            }
            Log.i(TAG, "watchdog exit")
        }
        watchdog = t
    }

    private fun sendStatusReport() {
        val st = runtime.snapshotStatus(linkSpeed)
        val status = if (st.state == ModuleState.ERROR) AgentStatus.ERROR
        else if (st.state == ModuleState.DEGRADED || !linkSpeed.isSuperSpeed) AgentStatus.DEGRADED_USB2
        else AgentStatus.RUNNING
        val bytes = ApxNative.packVendorStatusOrNull(
            status, linkSpeed.code, st.moduleMask, st.errorCode, st.uptimeMs, st.lastSeq,
        ) ?: return
        hidDev?.sendInputReport(bytes)
    }

    override fun stop() {
        synchronized(lock) {
            if (state == ModuleState.STOPPED || state == ModuleState.IDLE) {
                state = ModuleState.STOPPED
                return
            }
            state = ModuleState.STOPPING
            watchdog?.interrupt()
            watchdog = null
            closeDevices()
            FfsChannel.close()
            val sh = shell
            val options = currentOptions ?: GadgetOptions()
            if (sh != null && !sh.isBroken) {
                runSteps(ConfigFsLayout.unmount(options), sh)
                runSteps(ConfigFsLayout.forceClean(options), sh)
            }
            arbiter?.release()
            arbiter = null
            sh?.close()
            shell = null
            udc = null
            everConnected = false
            lastHeartbeatMs = 0L
            state = ModuleState.STOPPED
            publishState("stopped")
        }
    }

    private fun fail(reason: String) {
        lastError = reason
        state = ModuleState.ERROR
        Log.e(TAG, "gadget error: $reason")
        runCatching {
            val sh = shell
            if (sh != null && !sh.isBroken) {
                val options = currentOptions ?: GadgetOptions()
                runSteps(ConfigFsLayout.forceClean(options), sh)
            }
            arbiter?.release()
            arbiter = null
            closeDevices()
            sh?.close()
            shell = null
        }.onFailure { Log.w(TAG, "失败清理异常：${it.message}") }
        publishState("error: $reason")
    }

    private fun publishState(detail: String) {
        EventBus.post(GadgetStateEvent(state, linkSpeed, udc?.name, detail))
    }

    override fun sendInputReport(report: ByteArray): Boolean = hidDev?.sendInputReport(report) ?: false

    override fun isReady(): Boolean = state.isActive && hidDev?.isReady() == true

    override fun statusText(): String {
        val u = udc
        return buildString {
            append("state=$state")
            append(" udc=${u?.name ?: "-"}")
            append(" speed=${linkSpeed.label}")
            if (u != null && u.rawSpeed.isNotBlank()) append(" (${u.rawSpeed})")
            lastError?.let { append(" err=$it") }
        }
    }

    companion object {
        private const val TAG = "GadgetManager"
        private const val DESC_FILE_NAME = "hid_report_desc.bin"

        fun currentLinkSpeed(m: Module?): LinkSpeed? = (m as? GadgetManager)?.linkSpeed
    }
}
