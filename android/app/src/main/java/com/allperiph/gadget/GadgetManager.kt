package com.allperiph.gadget

import android.content.Context
import com.allperiph.core.AgentRuntime
import com.allperiph.core.AgentStatus
import com.allperiph.core.ApxNative
import com.allperiph.core.EventBus
import com.allperiph.core.FfsChannel
import com.allperiph.core.GadgetConst
import com.allperiph.core.GadgetStateEvent
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
 * 优化 #3：
 * - heal() 增加退避策略，连续失败达到上限后停止自愈，
 *   避免内核 ConfigFS 挂了无限循环 heal→fail→heal。
 * - 连接恢复（收到心跳）时重置计数。
 * - start() 里所有提前 return 路径确保 arbiter.release()，
 *   避免 USB HAL 占用泄漏。
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

    /**
     * 当前失败 / 退避重试的原因，**公开**给 UI。
     *
     * 必须外露的理由：USB 通道没起来时用户看到的现象就是"点不动"，而真正原因（root 未授权 /
     * UDC 被系统 USB HAL 占着 / 节点打不开）原先只躺在私有字段和日志里。
     */
    @Volatile
    var lastReason: String? = null
        private set

    /** UDC 被占用时的退避重试：已试次数 + 后台线程（[stop] 会打断，避免"用户已关还在重试"） */
    private var retryCount = 0
    private var retryThread: Thread? = null

    /**
     * UDC 当前状态（watchdog 每秒刷新）。
     *
     * **挂载成功 ≠ 主机认识我们**：gadget 绑上 UDC 只说明手机侧就绪，主机还得**选择配置**才算通。
     * 真机踩过：手机侧一切正常（hidg0 已打开），但 PC 设备管理器里是
     * 「USB Composite Device · Code 10 无法启动」，UDC 状态永远停在 `addressed`、一个子接口都没起
     * —— 用户只看到"USB 通道用不了"，谁也猜不到是主机拒绝了整个 configuration。
     */
    @Volatile
    var hostState: String = "unknown"
        private set

    /** 主机持续未完成配置的起始时刻（超过 [HOST_UNCONFIGURED_HINT_MS] 就建议重启手机） */
    @Volatile
    private var hostUnconfiguredSinceMs = 0L

    /** 上次"被踢出总线后自动重绑"的时刻（限频，避免和系统 HAL 拉锯） */
    @Volatile
    private var lastRebindMs = 0L

    /** 主机是否真的完成了配置（`configured`）。否则给用户明确指引（重新插拔 / 缺驱动）。 */
    val hostConfigured: Boolean get() = hostState.contains("configured")

    @Volatile
    private var retryAbort = false

    private var healCount = 0

    override fun start(ctx: ModuleContext) {
        synchronized(lock) {
            if (state.isActive) return
            state = ModuleState.STARTING
            retryAbort = false
            retryCount = 0
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
                    val msg = "当前链路为 ${best.rawSpeed}，非 USB 3.0 SuperSpeed：音频带宽受限，已降级运行"
                    Log.w(TAG, msg)
                    EventBus.post(LinkSpeedDegradedEvent(best.speed, msg))
                }

                val arb = UsbHalArbiter(sh)
                arbiter = arb
                // 返回值必须接住：它表示"系统 gadget 的 UDC 是否真的被释放干净"。
                // 原先丢弃 —— 明明测出 UDC 还忙，却照样往下绑，最后只能以笼统的
                // "ConfigFS 挂载失败"收场，真正的 EBUSY 原因就此丢失。
                val freed = arb.acquire()

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

                Log.i(TAG, "build=own-v3，直接自建 gadget")
                val options = GadgetOptions(
                    bcdUsb = if (best.speed.isSuperSpeed) UsbId.BCD_USB_30 else UsbId.BCD_USB_20,
                    reportLength = ApxNative.hidMaxReportLengthOrDefault(),
                    reportDescSource = descFile,
                ).copy(configfsRoot = resolveConfigfsRoot(sh))
                val mounted = runSteps(ConfigFsLayout.mount(options, best.name), sh)
                if (!mounted) {
                    val diag = sh.exec("dmesg 2>/dev/null | tail -40 | tr '\\n' ' '")
                    Log.e(TAG, "内核日志：${diag.out.take(400)}")
                    // 最常见的真因：**UDC 还被系统 USB HAL（adb / MTP）占着** —— 实测 adbd
                    // 释放 UDC 是异步的，"解绑 → 立刻绑"经常赶不上。
                    // 这种失败**绝不能**走 fail()：fail() 会 forceClean（逐级 rmdir 掉 gadget
                    // 本体），而内核同一开机周期只允许成功创建一次 gadget，名额就此烧掉
                    // （docs/REALDEVICE-NOTES §3.1/§8「失败路径不要自动重试」）。
                    // 此刻 gadget 目录已由本次 mount 的 `mkdir -p` 建好（幂等），所以安全做法是：
                    // **只退避重绑 UDC**，既不删 gadget 也不重建。
                    val udcBusy = !freed || !arb.isUdcFree()
                    if (udcBusy) {
                        lastReason = "UDC 被占用（系统 USB HAL / adb 仍在用 USB 口）"
                        scheduleRetryUdcBusy(best, options, lastReason!!)
                    } else {
                        fail("ConfigFS 挂载失败")
                    }
                    return
                }
                currentOptions = options
                afterMount(best, options)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                fail(t.message ?: "unknown")
            }
        }
    }

    /**
     * 挂载成功后的收尾：打开节点 → 登记 Feature → 接上 runtime → 置状态 → 起看门狗。
     *
     * 抽成独立方法是为了让 [retryMount]（UDC 被占用后的退避重绑）复用同一套收尾 ——
     * 否则"重试成功"会停在"gadget 绑上了、但模块没接上"的半死状态（界面显示已就绪却发不出去）。
     */
    private fun afterMount(best: UdcInfo, options: GadgetOptions) {
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
        lastReason = null
        healCount = 0
        retryCount = 0
        state = if (best.speed.isSuperSpeed) ModuleState.RUNNING else ModuleState.DEGRADED
        publishState("mounted on ${best.name} (${best.rawSpeed})")
        startWatchdog()
    }

    /**
     * UDC 被占用 → 带退避的后台重试（**只重绑 UDC，不重建 gadget**，理由见 start() 里的注释）。
     *
     * 为什么不复用 [heal]：heal 走的是 `forceClean + mount`，那是"已经连通过、心跳掉了"的修复
     * 路径；而这里 gadget **从未成功挂上**，forceClean 会把仅有一次的 gadget 名额烧掉。
     *
     * 真机现象（值得记住）：app 22:31 启动，直到 23:18 才挂上 USB —— 中间 47 分钟没有任何
     * 第二次尝试，用户只能看到"USB 通道用不了"。
     */
    private fun scheduleRetryUdcBusy(best: UdcInfo, options: GadgetOptions, reason: String) {
        if (retryCount >= MAX_RETRY) {
            lastReason = "$reason；已退避重试 $retryCount 次仍未成功"
            fail(lastReason!!)
            return
        }
        val backoff = RETRY_BACKOFF_MS[retryCount.coerceAtMost(RETRY_BACKOFF_MS.size - 1)]
        lastReason = "$reason，${backoff / 1000}s 后自动重试（第 ${retryCount + 1}/$MAX_RETRY 次）"
        Log.w(TAG, lastReason!!)
        publishState("retry ${retryCount + 1}/$MAX_RETRY in ${backoff}ms: $reason")
        retryCount++
        retryThread = kotlin.concurrent.thread(start = true, name = "apx-gadget-retry") {
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return@thread
            }
            // 用户已经关掉 / 模块已不在活动态 → 放弃（否则会变成"关了还在偷偷挂"）
            if (retryAbort || !state.isActive) return@thread
            retryMount(best, options)
        }
    }

    /** 一次重绑尝试（在 [scheduleRetryUdcBusy] 的后台线程里跑，内部再同步 lock） */
    private fun retryMount(best: UdcInfo, options: GadgetOptions) {
        synchronized(lock) {
            if (retryAbort || !state.isActive) return
            val sh = shell
            if (sh == null || sh.isBroken) {
                lastReason = "root shell 已失效，停止重试"
                fail(lastReason!!)
                return
            }
            // 再清一次系统 gadget 的 UDC（acquire 幂等，不会重复释放/归还）
            arbiter?.acquire()
            if (!runSteps(ConfigFsLayout.mount(options, best.name), sh)) {
                scheduleRetryUdcBusy(best, options, "UDC 仍被占用 / 绑定失败")
                return
            }
            currentOptions = options
            Log.i(TAG, "退避重试成功：gadget 已挂上（第 $retryCount 次尝试）")
            afterMount(best, options)
        }
    }

    private fun startFfsDescriptorWriter() {
        val sh = shell ?: return
        val probe = sh.exec("test -e '${SysPath.FFS_EP0}' && echo yes")
        if (!probe.out.contains("yes")) {
            Log.w(TAG, "FunctionFS ep0 不存在：副屏 bulk 通道不可用")
            return
        }
        kotlin.concurrent.thread(start = true, name = "apx-ffs-desc") {
            val rc = FfsChannel.writeDescriptors()
            if (rc == 0) Log.i(TAG, "FunctionFS 描述符已写入：副屏 bulk 端点就绪")
            else Log.w(TAG, "FunctionFS 描述符写入失败 rc=$rc")
        }
    }

    private fun registerSensorFeatureReports() {
        Log.i(TAG, "传感器 TLC 已移出描述符（蓝屏规避），跳过 Feature 登记")
    }

    private fun registerPtpFeatureReports() {
        Log.i(TAG, "PTP 描述符已移除，跳过 Feature 登记")
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
            healCount = 0
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
            if (healCount >= MAX_HEAL) {
                Log.e(TAG, "heal 已连续失败 $healCount 次（上限 $MAX_HEAL），停止自愈等待手动干预")
                return
            }
            val backoff = HEAL_BACKOFF_MS[healCount.coerceAtMost(HEAL_BACKOFF_MS.size - 1)]
            Log.w(TAG, "heal: $reason (attempt ${healCount + 1}/$MAX_HEAL, backoff ${backoff}ms)")
            healCount++
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }

            closeDevices()
            val options = GadgetOptions(
                bcdUsb = if (best.speed.isSuperSpeed) UsbId.BCD_USB_30 else UsbId.BCD_USB_20,
                reportLength = ApxNative.hidMaxReportLengthOrDefault(),
                reportDescSource = SysPath.SHARED_TMP_DESC,
            )
            // ⚠️ 这里**绝不能** forceClean：内核同一开机周期只允许成功创建一次 gadget，
            // forceClean 会把 gadget 本体逐级 rmdir 掉，之后 mkdir 必然撞 -EEXIST（被报成
            // ENOMEM）—— 名额就此烧掉，表现为"USB 彻底不工作、只能重启手机"。
            // 真机踩过：重启后自愈循环跑几轮，名额就没了。mount() 本身是 `mkdir -p` +
            // 幂等属性写入（目录已存在就跳过创建），自愈只需要"解绑 UDC → 重新绑定"。
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
        hidDev?.close(); hidDev = null
        serialDev?.close(); serialDev = null
    }

    /// 读 UDC 状态：`configured` = 主机已选配置；`addressed` = 主机枚举了却**拒了配置**（见 [hostState]）
    private fun readUdcState(name: String?): String {
        if (name.isNullOrBlank()) return "unknown"
        val sh = shell ?: return "unknown"
        return sh.exec("cat '/sys/class/udc/$name/state' 2>/dev/null").out.trim().ifBlank { "unknown" }
    }

    private fun startWatchdog() {
        val t = kotlin.concurrent.thread(start = true, name = "apx-gadget-wd") {
            while (state.isActive) {
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                // 顺带刷新"主机有没有真的认我们"——用户重新插拔后会自然变成 configured
                val st = readUdcState(udc?.name)
                if (st != hostState) {
                    hostState = st
                    if (!hostConfigured) {
                        Log.w(TAG, "主机尚未完成 USB 配置（UDC state=$st）：PC 侧可能需要重新插拔数据线，" +
                                "或该主机缺少对应驱动")
                    } else {
                        Log.i(TAG, "主机已完成 USB 配置（UDC state=$st）：USB HID 通道可用")
                        hostUnconfiguredSinceMs = 0L
                    }
                }
                // 主机**长期**（1 分钟+）没完成配置：多半不是插拔能解决的 —— 内核 gadget 状态在
                // 长开机周期里被反复建/卸磨坏（REALDEVICE-NOTES §3.1：同一开机周期只能成功
                // 创建一次 gadget，唯一可靠恢复 = 重启）。必须把"重启手机"说给用户，否则
                // 用户会像今天一样在拔插/驱动上白耗一小时。
                if (!hostConfigured) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (hostUnconfiguredSinceMs == 0L) hostUnconfiguredSinceMs = now
                    if (now - hostUnconfiguredSinceMs > HOST_UNCONFIGURED_HINT_MS) {
                        val hint = "主机长期未完成 USB 配置（UDC=$hostState）：建议重启手机后重试" +
                                "（gadget 内核状态可能已耗尽）"
                        if (lastReason != hint) {
                            lastReason = hint
                            Log.w(TAG, hint)
                        }
                    }
                } else {
                    hostUnconfiguredSinceMs = 0L
                }
                // 自愈②：gadget 被系统 USB HAL 踢出总线（UDC 回到 not attached），但本模块仍在
                // 运行态、且这个周期里主机**确实连过**（everConnected）→ 大概率是被踢而不是被拔，
                // 只重绑一次（mount 幂等、不销毁本体），限频 30s 防止与 HAL 拉锯成"一卡一卡"。
                if (state.isActive && everConnected && st == "not attached") {
                    val now2 = android.os.SystemClock.elapsedRealtime()
                    if (now2 - lastRebindMs > REBIND_MIN_INTERVAL_MS) {
                        lastRebindMs = now2
                        Log.w(TAG, "gadget 掉出总线（UDC=not attached）但主机此前连过 → 只重绑 UDC")
                        runCatching {
                            val s = shell ?: return@runCatching
                            val opt = currentOptions ?: return@runCatching
                            val u = udc?.name ?: return@runCatching
                            runSteps(ConfigFsLayout.mount(opt, u), s)
                        }.onFailure { Log.w(TAG, "重绑失败：${it.message}") }
                    }
                }
                sendStatusReport()
                val elapsed = android.os.SystemClock.elapsedRealtime() - lastHeartbeatMs
                if (everConnected && lastHeartbeatMs > 0 && elapsed > HEARTBEAT_TIMEOUT_MS) {
                    heal("heartbeat lost ${elapsed}ms")
                }
            }
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
                state = ModuleState.STOPPED; return
            }
            state = ModuleState.STOPPING
            // 打断退避重试：用户已经关掉，不能让它偷偷再挂上 USB
            retryAbort = true
            retryThread?.interrupt(); retryThread = null
            retryCount = 0
            watchdog?.interrupt(); watchdog = null
            closeDevices(); FfsChannel.close()
            val sh = shell; val options = currentOptions ?: GadgetOptions()
            if (sh != null && !sh.isBroken) {
                // 只解绑 UDC（unmount），**不** forceClean：gadget 本体常驻 configfs，
                // 下次挂载的 mkdir -p 直接命中跳过 —— 这是同一开机周期内能反复挂载的唯一方式
                // （REALDEVICE-NOTES §3.1「对策：gadget 常驻」；删本体 = 烧掉一次性名额）。
                runSteps(ConfigFsLayout.unmount(options), sh)
            }
            arbiter?.release(); arbiter = null
            sh?.close(); shell = null
            udc = null; everConnected = false; lastHeartbeatMs = 0L; healCount = 0
            state = ModuleState.STOPPED
            publishState("stopped")
        }
    }

    private fun fail(reason: String) {
        lastError = reason; lastReason = reason; state = ModuleState.ERROR
        Log.e(TAG, "gadget error: $reason")
        runCatching {
            val sh = shell
            if (sh != null && !sh.isBroken) {
                val options = currentOptions ?: GadgetOptions()
                runSteps(ConfigFsLayout.forceClean(options), sh)
            }
            arbiter?.release(); arbiter = null
            closeDevices(); sh?.close(); shell = null
        }.onFailure { Log.w(TAG, "失败清理异常：${it.message}") }
        publishState("error: $reason")
    }

    private fun publishState(detail: String) {
        EventBus.post(GadgetStateEvent(state, linkSpeed, udc?.name, detail))
    }

    override fun sendInputReport(report: ByteArray): Boolean = hidDev?.sendInputReport(report) ?: false

    override fun isReady(): Boolean = state.isActive && hidDev?.isReady() == true

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> {
            val u = udc
            val speed = if (linkSpeed.isSuperSpeed) "USB 3.0" else "USB 2.0"
            val base = "Gadget 运行中 · $speed · ${u?.name ?: ""}".trimEnd(' ', '·')
            // 主机没完成配置就明说（否则用户只看到"USB 通道用不了"，猜不到是主机侧的事）
            if (hostConfigured) base else "$base · ${lastReason ?: "主机未完成配置（UDC=$hostState）：试重新插拔 USB 线"}"
        }
        ModuleState.DEGRADED -> "Gadget 降级运行（${linkSpeed.label}）"
        // 启动中带上原因：UDC 被占用正在退避重试时，界面必须能看出"为什么还没有 USB 通道"
        ModuleState.STARTING -> lastReason?.let { "Gadget 启动中（$it）" } ?: "Gadget 启动中"
        ModuleState.STOPPING -> "Gadget 停止中"
        ModuleState.STOPPED -> "Gadget 已停止"
        ModuleState.IDLE -> "Gadget 未启动"
        ModuleState.ERROR -> lastError?.let { "Gadget 错误：$it" } ?: "Gadget 错误"
        else -> state.name
    }

    companion object {
        private const val TAG = "GadgetManager"
        private const val DESC_FILE_NAME = "hid_report_desc.bin"
        private const val MAX_HEAL = 3
        private val HEAL_BACKOFF_MS = longArrayOf(2000, 5000, 15000)

        /** UDC 被占用时的退避重试上限与间隔（与 HEAL 同风格；只重绑 UDC，不重建 gadget） */
        private const val MAX_RETRY = 3
        private val RETRY_BACKOFF_MS = longArrayOf(3000, 10000, 30000)

        /** 主机持续未完成配置多久后，提示"重启手机"（内核 gadget 状态耗尽的典型表现） */
        private const val HOST_UNCONFIGURED_HINT_MS = 60_000L

        /** 被踢出总线后自动重绑的最小间隔（防止与系统 USB HAL 拉锯成"一卡一卡"） */
        private const val REBIND_MIN_INTERVAL_MS = 30_000L
        fun currentLinkSpeed(m: Module?): LinkSpeed? = (m as? GadgetManager)?.linkSpeed
    }
}