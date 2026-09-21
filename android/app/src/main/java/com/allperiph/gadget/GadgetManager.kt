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

    /**
     * 本次挂载实际使用的布局，供卸载时复用。
     *
     * 必须保存：复用系统 gadget（reuse=true）与自建 gadget 的清理动作完全不同 ——
     * 前者绝不能解绑 UDC，更不能 rm -rf g1（那是 USB HAL 的设备）。
     */
    private var currentOptions: GadgetOptions? = null

    @Volatile
    private var linkSpeed: LinkSpeed = LinkSpeed.UNKNOWN

    @Volatile
    private var lastHeartbeatMs = 0L

    /** 只有曾经连上过才做断线自愈，避免从未连接时无限重挂 */
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

                // 1) 速度门禁（§6.1）
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

                // 2) UDC 抢占（§6.2）
                val arb = UsbHalArbiter(sh)
                arbiter = arb
                arb.acquire()

                // 3) 描述符：只能来自 shared/
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

                // 4) 挂载。顺序关键：**优先复用系统 gadget**。
                //
                // 实测 Xiaomi HyperOS：USB HAL 收到 sys.usb.config=none 后并不释放 UDC，
                // 内核侧的 gadget 设备对象（androidN）也不释放。此状态下新建 gadget 必然
                // kobject 重名失败，且内核把它报成误导性的 ENOMEM。故第一选择是复用 HAL
                // 的 g1，只往它的 functions/ 与 configs/ 追加我们的 function。
                val systemGadget = "g1"     // Android init.usb.rc 固定命名
                val systemConfig = "b.1"
                Log.i(TAG, "build=reuse-v2，优先复用 $systemGadget/$systemConfig")

                // 挂载前先彻底清掉自建 gadget 的残留。
                // 反复失败的尝试会在 configfs 里留下半残的 gadget/function，
                // 之后写 function 属性会返回 EBUSY（实测 subclass 写入被拒），
                // 表现为「明明新建却像已被绑定」。
                val ownRoot = resolveConfigfsRoot(sh)
                // 先释放 ep0：只要还有 fd 打开，functionfs 就 umount 不掉，
                // ffs 实例会一轮轮泄漏，最终耗尽内核 FFS 上下文并拖垮 UDC 绑定。
                FfsChannel.close()
                runSteps(ConfigFsLayout.forceClean(GadgetOptions(configfsRoot = ownRoot)), sh)
                val base = {
                    GadgetOptions(
                        bcdUsb = if (best.speed.isSuperSpeed) UsbId.BCD_USB_30 else UsbId.BCD_USB_20,
                        reportLength = ApxNative.hidMaxReportLengthOrDefault(),
                        reportDescSource = descFile,
                    )
                }
                // 直接走自建 gadget。
                //
                // 复用系统 g1 已实测不可行：往 b.1 链 function 被内核恒定拒绝
                // （ln 报 EINVAL，与 SELinux 无关，permissive 下同样如此）。
                // 而关键在于——内核创建 gadget 的「名额」是一次性的：
                // androidN kobject 在失败后不会释放，每次失败的尝试都会白白
                // 消耗一次机会。因此绝不做无谓的复用尝试。
                Log.i(TAG, "build=own-v3，直接自建 gadget（复用 g1 已证实不可行）")
                val options = base().copy(configfsRoot = resolveConfigfsRoot(sh))
                val mounted = runSteps(ConfigFsLayout.mount(options, best.name), sh)
                if (!mounted) {
                    // 失败原因只在内核日志里，而 dmesg 读取不阻塞；
                    // 绝不读 /sys/devices/virtual/android_usb/ ——
                    // 该目录的读操作会把内核挂住，导致 RootShell 断链、输出全丢。
                    val diag = sh.exec("dmesg 2>/dev/null | tail -40 | tr '\\n' ' '")
                    Log.e(TAG, "内核日志：${diag.out.take(400)}")
                    // 写 UDC 失败（EBUSY）时必须知道 UDC 到底是不是真被占着。
                    // **只读 sysfs**：读 /config/usb_gadget 下任何内容（含 glob 展开）
                    // 在内核异常状态下都会挂起，把 RootShell 拖成 broken 并吞掉后续输出
                    // —— 这是 §3.3 记过的坑，此前那版诊断就是因此输出全空的。
                    val udcState = sh.exec(
                        "for u in /sys/class/udc/*/state; do echo \"\$u=[\$(cat \"\$u\" 2>&1)]\"; done",
                    )
                    Log.e(TAG, "UDC 状态：${udcState.out.take(300)}")
                    fail("ConfigFS 挂载失败")
                    return
                }
                currentOptions = options

                // 5) 打开设备节点
                val hid = HidDevice()
                if (!hid.open()) {
                    fail("${SysPath.HIDG_DEVICE} 打不开（权限或 SELinux 限制）")
                    return
                }
                hid.setReportListener { onHidReport(it) }
                hidDev = hid

                // Windows 的 SensorsHIDClassDriver 启动时要用 GET_REPORT 索取
                // Report State / Report Interval。该请求走**控制传输**，不会出现在
                // /dev/hidg0 的 read() 里（实测挂载数分钟内零 OUT 事件），必须由我们
                // 主动登记应答内容；否则每个传感器 TLC 都会以 Code 10 启动失败，
                // 而通用 usage 的 TLC（驱动识别不出类型、不去初始化）反而显示正常。
                registerSensorFeatureReports()
                registerPtpFeatureReports()   // v1.8：PTP feature 应答（精确式触控板识别依赖）

                val ser = SerialDevice()
                if (!ser.open()) {
                    Log.w(TAG, "${SysPath.ACM_DEVICE} 打开失败：GPS over ACM 不可用，其余功能继续")
                } else {
                    serialDev = ser
                    runtime.attachSerial(ser)
                }

                runtime.attachHid(this)

                // 副屏 bulk 通道（FunctionFS）：接口/端点描述符**必须由用户态写 ep0**，
                // 而该 write 会阻塞到内核 ffs_func_bind() 拿到描述符为止（UDC 绑定是
                // 挂载流程的最后一步）—— 同步调用必然死锁，必须放独立线程。
                startFfsDescriptorWriter()

                // 音频（UAC2）由 AudioModule 在 gadget 就绪后自行定位声卡并搬运 PCM，
                // 这里只确保 /dev/snd 节点已放开权限（见 ConfigFsLayout.mount 的 chmod）。

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

    /**
     * 启动 FunctionFS 描述符写入线程（副屏 bulk 通道）。
     *
     * **为什么必须异步**：`write(ep0)` 会阻塞到内核 `ffs_func_bind()` 取到描述符才返回，
     * 而 UDC 绑定又排在挂载流程的最后一步 —— 同步调用会直接死锁。这里只负责启动线程，
     * 不等待结果，挂载流程照常返回。
     *
     * 降级：内核未编 `f_fs`（configfs 建不出 `functions/ffs.apx`）时，functionfs 挂不上、
     * ep0 不存在。此情形只记一条日志，不影响其余功能。
     */
    private fun startFfsDescriptorWriter() {
        val sh = shell ?: return
        val probe = sh.exec("test -e '${SysPath.FFS_EP0}' && echo yes")
        if (!probe.out.contains("yes")) {
            Log.w(TAG, "FunctionFS ep0 不存在（内核无 f_fs 或挂载失败）：副屏 bulk 通道不可用")
            return
        }
        kotlin.concurrent.thread(start = true, name = "apx-ffs-desc") {
            val rc = FfsChannel.writeDescriptors()
            // 落盘结果：挂载成功后 adb 立刻断开（logcat 取不到），App 退出后缓冲区还会滚动
            // 丢失。把关键现场写到 /data/local/tmp，便于切回后读取。
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

    /**
     * 为每个传感器 TLC 登记 Feature Report 应答内容。
     *
     * 必要性见调用点：Windows 的 SensorsHIDClassDriver 靠 `GET_REPORT` 读
     * Report State / Report Interval，而该请求走**控制传输**、不进 `/dev/hidg0`
     * 的读队列，只能由用户态经 `GADGET_HID_WRITE_GET_REPORT`（Linux 6.12+）登记。
     *
     * 载荷布局与 `hid_descriptor.cpp` 中各传感器 TLC 的 Feature 段严格一致
     * （IMU 与 9 个低频 TLC 相同；v1.5 补 Sensor Status）：
     *   ReportState(1B) + SensorStatus(1B) + ChangeSensitivity(2B)
     *   + ReportInterval(4B, 小端) = 8 字节
     *
     * 登记时 `userspace_req = 0`（见 [HidFeature]）：内核收到 GET_REPORT 会**立即**
     * 用该内容应答，且对后续所有请求持续有效，因此登记一次即可。
     */
    private fun registerSensorFeatureReports() {
        // v1.9：传感器 TLC 已整体移出 USB 描述符（hidparse 对 Unit 无 Physical
        // Range 的 value cap 计算除零 → 蓝屏 0x3B/0xC0000094，真机三连）。
        // 这里同步禁用登记——对描述符中不存在的 rid 登记只会徒增失败日志。
        Log.i(TAG, "传感器 TLC 已移出描述符（蓝屏规避），跳过 Feature 登记")
        return
        /* val ids = ApxNative.sensorReportIdsOrNull()
        if (ids.isEmpty()) {
            Log.w(TAG, "未取到传感器 Report ID，跳过 Feature Report 登记（传感器可能 Code 10）")
            return
        }
        // HID Sensor Usage Tables：Report State 取值 1 = Ready
        val stateReady: Byte = 1
        // 上报间隔（毫秒）。驱动据此设置采样周期，也是它启动时校验的参数之一。
        val intervalMs = 100
        var ok = 0
        val diag = StringBuilder()
        for (id in ids) {
            // 载荷必须是 **10 字节且首字节为 Report ID**（v1.6：Feature 追加
            // Power State，由 9B 增至 10B）：
            // HID 规范 §7.2.1 —— 使用 Report ID 时，传输数据的第一个字节就是 Report ID。
            // 内核只是把 data 原样挂到 ep0 上（`req->buf = ptr->report_data.data`），
            // 不会替我们补，所以必须自己带上。缺了它 Windows 会拿到错位的数据，
            // 驱动仍以 Code 10（STATUS_INVALID_PARAMETER）启动失败。
            // 布局（与 hid_descriptor.cpp 的 Feature 段一致）：
            //   ReportID(1B) + ReportState(1B) + SensorStatus(1B)
            //   + ChangeSensitivity(2B) + ReportInterval(4B) + PowerState(1B)
            val payload = ByteArray(10)
            payload[0] = id.toByte()           // Report ID
            payload[1] = stateReady            // Report State
            payload[2] = 0x00                  // Sensor Status：0 = data-ready 无错误
            payload[3] = 0x01                  // Change Sensitivity 低字节
            payload[4] = 0x00
            payload[5] = intervalMs.toByte()   // Report Interval（u32，小端）
            payload[6] = 0x00
            payload[7] = 0x00
            payload[8] = 0x00
            payload[9] = 0x00                  // Power State：0 = D0 full power

            val rc = HidFeature.writeGetReport(SysPath.HIDG_DEVICE, id, payload)
            // 结果必须落盘：登记发生在挂载成功**之后**，此刻 adb 已被 Gadget 接管，
            // logcat 拿不到 —— Code 10 诊断只能靠事后读取该文件。
            diag.appendLine("id=$id rc=$rc")
            if (rc == 0) ok++ else Log.w(TAG, "登记 Feature Report id=$id 失败 rc=$rc")
        }
        diag.appendLine("total $ok/${ids.size}")
        runCatching {
            java.io.File("/data/local/tmp/apx_hidfeat.txt").writeText(diag.toString())
        }
        Log.i(TAG, "传感器 Feature Report 登记 $ok/${ids.size} 个 TLC")
         */
    }

    /**
     * v1.8：登记 PTP（Windows Precision Touchpad）的 4 个 Feature Report 应答。
     * Windows 枚举触控板时会 GET_REPORT 这些 ID，缺应答 = 不认精确式触控板。
     * Report ID 与 hid_descriptor.cpp 的 kPtpDescriptor 一致（16..20）。
     *
     * v1.9：PTP 描述符段已从 USB 描述符移除（内核 64B GET_REPORT 上限无法承载
     * 257B 认证 blob），登记无从谈起——整体禁用。
     */
    private fun registerPtpFeatureReports() {
        Log.i(TAG, "PTP 描述符已移除，跳过 Feature 登记")
        return
        /* val diag = StringBuilder()
        // rid 17：Input Mode（1B data，0=鼠标模式；Windows 枚举后会 SET 1）
        fun reg(id: Int, data: ByteArray) {
            val payload = ByteArray(1 + data.size)
            payload[0] = id.toByte()
            data.copyInto(payload, 1)
            val rc = HidFeature.writeGetReport(SysPath.HIDG_DEVICE, id, payload)
            diag.appendLine("id=$id rc=$rc")
            if (rc != 0) Log.w(TAG, "PTP Feature 登记 id=$id 失败 rc=$rc")
        }
        reg(17, byteArrayOf(0x01))                        // Input Mode: 1=触控板模式
                                                          // （GET 响应 0 会让驱动认为设备在鼠标模式
                                                          //   而不消费触摸输入——真机「PTP 不动」根因）
        reg(18, byteArrayOf(0x03))                        // Surface Switch=1, Button Switch=1
        reg(19, byteArrayOf(0x05, 0x00))                  // MaxContacts=5, PadType=0(Depressible)

        runCatching {
            java.io.File("/data/local/tmp/apx_ptpfeat.txt").writeText(diag.toString())
        }
        Log.i(TAG, "PTP Feature Report 登记 4 个完成")
        // v1.8 注意：登记发生在 UDC 绑定之前（hidg0 节点在 mkdir functions 时已存在），
        // Windows 首次枚举时 feature 应答即就绪——**不要**在此后重枚举 UDC：
        // 解绑/重绑会打断 ffs ep0 描述符写入，设备会枚举到一半直接掉线（真机已验证）。
         */
    }

    /** 把描述符写到 app 私有目录，再 root 侧 cp 到 /data/local/tmp（与 scripts/ 共用同一路径） */
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

    /**
     * 返回 ConfigFS 中 usb_gadget 的挂载点。
     *
     * **必须用全局挂载点 /config，不得自建私有实例** —— 这条是用「只能重启手机」
     * 的代价换来的：
     *
     * configfs 的挂载**按 mount namespace 生效**。App 里 `mount -t configfs` 挂到
     * /dev/apx_cfg 只有 App 自己的 namespace 看得见；进程一旦被杀（`adb install -r`、
     * LMK、崩溃），namespace 销毁、挂载点直接消失，但内核创建 gadget 时注册的
     * androidN 设备对象**不会跟着释放**。残留名一直占着，之后每次建 gadget 都
     * kobject 重名失败，而内核把 `-EEXIST` 报成 "Out of memory"；此刻连清理入口
     * 都没有（挂载点已不存在），只能重启 —— 调试循环就此锁死。
     *
     * /config 由 init 在全局 namespace 挂好，gadget 生命周期与 App 进程解耦：
     * 进程重启后仍看得见、也删得掉残留，死结自然消失。
     *
     * 这里**不做任何探测**：探测（lstat / 读目录）在内核异常状态下会挂起，
     * 把 RootShell 拖成 broken 并吞掉后续全部输出，反而掩盖真实错误。
     * `libcomposite` 已加载时 /config/usb_gadget 必然存在，直接用即可。
     */
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
        // §2.7 lastSeq 回显：EventBus 为同步派发，post 返回即代表同步消费者已处理完，
        // 未被拒绝（如严格模式采样率不达标）才回显 seq，否则保留 errorCode 供 PC 读取。
        runtime.beginCommand(cmd.seq)
        EventBus.post(VendorCommandEvent(cmd))
        runtime.endCommand()
    }

    /** 断线自愈：心跳丢失后重新挂载（协议 §4） */
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
                // 自愈会重新绑 UDC：f_hid 的已登记报告与 FFS 的描述符都会随之失效，
                // 两者都必须重新下发。
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

    /** §2.7 状态上报（手机 → PC） */
    private fun sendStatusReport() {
        val st = runtime.snapshotStatus(linkSpeed)
        val status = if (st.state == ModuleState.ERROR) AgentStatus.ERROR
        else if (st.state == ModuleState.DEGRADED || !linkSpeed.isSuperSpeed) AgentStatus.DEGRADED_USB2
        else AgentStatus.RUNNING
        // v1.1：状态经 Input 报告上行；moduleMask 为 §2.9 的 64 位图，lastSeq 回显命令执行
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

            // 释放 ep0：unmount 会 umount functionfs，而只要还有 fd 打开就 umount 不掉；
            // umount 失败会让 ffs 实例跨轮泄漏，最终耗尽内核 FFS 上下文并拖垮 UDC 绑定。
            FfsChannel.close()

            val sh = shell
            // 用挂载时的同一份布局卸载：复用模式与自建模式的清理动作不同
            val options = currentOptions ?: GadgetOptions()
            if (sh != null && !sh.isBroken) {
                runSteps(ConfigFsLayout.unmount(options), sh)
                // 卸载后残留目录会导致下次挂载失败，兜底清一次
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
        // 失败路径**必须**归还 UDC 并恢复原 USB 配置：
        // 否则 sys.usb.config 会留在 "none"，使内核 gadget 子系统处于卸载状态，
        // 下次挂载时 mkdir 会返回误导性的 ENOMEM（实测 Xiaomi HyperOS 复现），
        // 同时用户手机也会失去 adb / 文件传输能力。
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

        /** 供 UI/诊断读取的当前链路速度 */
        fun currentLinkSpeed(m: Module?): LinkSpeed? = (m as? GadgetManager)?.linkSpeed
    }
}
