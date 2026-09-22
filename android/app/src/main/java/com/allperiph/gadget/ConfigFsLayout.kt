package com.allperiph.gadget

import com.allperiph.core.AudioConst
import com.allperiph.core.GadgetConst
import com.allperiph.core.SysPath
import com.allperiph.core.UsbId

/**
 * 复合设备的 ConfigFS 布局（纯函数：只生成命令序列，不执行）。
 *
 * 抽成纯函数的好处：
 * 1. 可以在 JVM 单测里断言命令序列，不需要 root 设备；
 * 2. 与 scripts/apx_gadget.sh 保持同一套顺序，App 与脚本行为一致。
 *
 * 挂载顺序（内核要求）：gadget → strings → configs → functions → 软链 → 写 UDC。
 *
 * 另有 **复用模式**（[GadgetOptions.reuse]）：不创建 gadget，只往系统已有的
 * gadget 里追加 function。原因见 [mountReuse] 注释。
 */
enum class GadgetFeature(
    /** ConfigFS 下 functions/<instance> 的实例名 */
    val instance: String,
    /** 默认尝试挂载 */
    val enabledByDefault: Boolean,
    /**
     * 内核缺少该 function 时是否允许失败。
     *
     * 只有 HID / ACM 是架构上的必需项（它们的缺失意味着核心功能不可用）；
     * UAC2 / UVC / NCM 依赖厂商内核是否编译了对应 gadget function，
     * 有些机型（实测 Xiaomi HyperOS）就没有 `usb_f_uac2`，
     * 此时应当**跳过并降级**，而不是让整个复合设备挂载失败。
     */
    val optional: Boolean,
    val label: String,
) {
    HID("hid.usb0", true, false, "f_hid 复合 HID（传感器/触控/按键/电池/Vendor）"),
    ACM("acm.usb0", true, false, "f_acm CDC ACM（GPS NMEA → COM 口）"),
    UAC2("uac2.usb0", true, true, "f_uac2 声卡（手机麦克风 + 扬声器；内核无此 function 时自动跳过）"),
    // v1.13：UVC 已启用——CameraModule 实现了 Camera2→V4L2 数据面。
    // ConfigFS 树由 GadgetManager 挂载，V4L2 节点由内核 f_uvc 创建。
    // 真机若 UDC 绑定失败，optional=true 允许降级（跳过 UVC，其余模块正常）。
    UVC("uvc.usb0", true, true, "f_uvc 摄像头（MJPEG 720p@30，CameraModule 数据面已实现）"),
    NCM("ncm.usb0", false, true, "f_ncm 网卡/控制面"),
    /**
     * f_fs FunctionFS（副屏 bulk：video 下行 / touch 上行）。
     *
     * **默认关闭，且本机实测不可用** —— 原因见 REALDEVICE-NOTES §3.6：
     * 内核 FFS 上下文已被系统 6 个实例（adb/aoa/ctrl/ipcr/mtp/ptp）占满，
     * `mount -t functionfs` 能成功，但 ffs function 在 bind 阶段建不出上下文
     * （dmesg：`Can't create any more FFS log contexts`），进而拖垮整个 gadget 的
     * UDC 绑定（`udc ...: failed to start apx: -19`）。系统实例又被进程占用、
     * umount 不掉，无法让出。代码保留以便在其他 ROM 上复用。
     *
     * 副屏通道请改用架构 §4 主线：`f_ncm` 网卡 + TCP（见 TcpBulkTransport）。
     */
    FFS(
        "ffs.apx",
        false,
        true,
        "f_fs FunctionFS（副屏 bulk）；本机内核 FFS 上下文被系统占满，默认关闭",
    ),
    ;

    companion object {
        fun defaults(): Set<GadgetFeature> = entries.filter { it.enabledByDefault }.toSet()
    }
}

data class GadgetOptions(
    val gadget: String = GadgetConst.GADGET_NAME,
    val config: String = GadgetConst.CONFIG_NAME,
    val features: Set<GadgetFeature> = GadgetFeature.defaults(),
    /** 按实测速度二选一：USB3 才能声明 0x0300（架构 §6.1） */
    val bcdUsb: String = UsbId.BCD_USB_20,
    val serial: String = UsbId.SERIAL,
    val maxPowerMa: Int = 500,
    val reportLength: Int = GadgetConst.HID_REPORT_LENGTH_FALLBACK,
    /** 报告描述符来源（已由 root 侧 cp 到该路径） */
    val reportDescSource: String? = null,
    /**
     * usb_gadget 的实际挂载点，由 GadgetManager.resolveConfigfsRoot() 运行时探测后传入。
     *
     * **不可硬编码 /config**：实测 Xiaomi HyperOS 的 configfs 并不挂在 /config，
     * 直接 `mkdir /config/usb_gadget/apx` 会失败并返回误导性的 "Out of memory"。
     */
    val configfsRoot: String = SysPath.CONFIGFS_USB_GADGET,
    /**
     * 复用系统已有 gadget（Android 固定用 g1），而不是创建新的。
     *
     * 实测 Xiaomi HyperOS：USB HAL 在 `sys.usb.config=none` 之后**不释放 UDC**，
     * 内核侧的 gadget 设备对象（androidN）也不释放。此状态下创建第二个 gadget
     * 必然因 kobject 重名失败，并被内核报成误导性的 ENOMEM。
     * 因此改为直接复用 g1，只往它的 functions/ 与 configs/b.1/ 追加我们的
     * function —— UDC 由 HAL 保持绑定，function 挂上即刻生效。
     */
    val reuse: Boolean = false,
) {
    fun root(): String = "$configfsRoot/$gadget"
    fun configDir(): String = "${root()}/configs/$config"
    fun functionDir(f: GadgetFeature): String = "${root()}/functions/${f.instance}"
}

data class Step(
    val cmd: String,
    /** 允许失败：可选 function 在部分内核不存在，不能因此中断整个挂载 */
    val optional: Boolean = false,
)

object ConfigFsLayout {

    /** 生成挂载命令序列 */
    fun mount(o: GadgetOptions, udc: String): List<Step> {
        if (o.reuse) return mountReuse(o)

        val g = o.root()
        val steps = mutableListOf<Step>()

        steps += Step("mkdir -p '$g'")
        steps += Step("printf '%s' '${UsbId.ID_VENDOR}' > '$g/idVendor'")
        steps += Step("printf '%s' '${UsbId.ID_PRODUCT}' > '$g/idProduct'")
        steps += Step("printf '%s' '${UsbId.BCD_DEVICE}' > '$g/bcdDevice'")
        steps += Step("printf '%s' '${o.bcdUsb}' > '$g/bcdUSB'")

        steps += Step("mkdir -p '$g/strings/${UsbId.LANG_US}'")
        steps += Step("printf '%s' '${o.serial}' > '$g/strings/${UsbId.LANG_US}/serialnumber'")
        steps += Step("printf '%s' '${UsbId.MANUFACTURER}' > '$g/strings/${UsbId.LANG_US}/manufacturer'")
        steps += Step("printf '%s' '${UsbId.PRODUCT}' > '$g/strings/${UsbId.LANG_US}/product'")

        steps += Step("mkdir -p '${o.configDir()}/strings/${UsbId.LANG_US}'")
        steps += Step(
            "printf '%s' '${UsbId.CONFIGURATION}' > '${o.configDir()}/strings/${UsbId.LANG_US}/configuration'",
        )
        steps += Step("printf '%s' '${o.maxPowerMa}' > '${o.configDir()}/MaxPower'")

        // **先清空 c.1 里所有旧软链**。
        //
        // 2026-09-21 真机根因（MS1 Gadget 挂载 EBUSY）：FFS 曾在 features 里挂过
        // 一轮，后来默认关闭 —— 但 c.1 里指向 functions/ffs.apx 的旧软链**无人摘除**。
        // 内核写 UDC 时会实例化 config 里的**全部** function，包括那个 ffs 实例
        // 上下文已耗尽（`Can't create any more FFS log contexts`）的 ffs.apx，
        // 整个 gadget `start: -19`，写 UDC 报 EBUSY。forceClean 也删不掉它
        // （rmdir c.1 因目录非空失败）。configfs 的 symlink 用 `rm` 即 unlink，
        // 对非链接文件（MaxPower/bmAttributes）`rm -f` 失败无副作用。
        steps += Step(
            "for l in '${o.configDir()}'/*; do rm -f \"\$l\" 2>/dev/null; done",
            optional = true,
        )
        for (f in o.features.sortedBy { it.ordinal }) {
            val fd = o.functionDir(f)
            val optional = f.optional
            steps += Step("mkdir -p '$fd'", optional)
            steps += functionProps(f, fd, o)
            // 软链必须**幂等**：gadget 是常驻的（原因见 [unmount]），重复挂载时
            // 链接已存在，直接 `ln -s` 会失败。
            // 三个约束（都实测过）：不能带 `-f`（configfs 对已存在链接做
            // unlink+recreate 返回 EINVAL）；target 必须相对；且 toybox 的 ln 按
            // **当前工作目录**校验 target，所以要先 cd 到链接所在目录。
            steps += Step("rm -f '${o.configDir()}/${f.instance}' 2>/dev/null", optional = true)
            steps += Step(
                "cd '${o.configDir()}' && ln -s '../../functions/${f.instance}' '${f.instance}'",
                optional,
            )
        }

        // FunctionFS 挂载点必须在**绑定 UDC 之前**就绪：用户态要先能把描述符写进 ep0，
        // 内核 ffs_func_bind() 才能完成绑定（它内部 wait_event 等描述符就绪）。
        //
        // 每轮都**先 umount 再 remount**，不复用旧挂载点：configfs 里的 ffs.apx 每轮
        // 都会被 forceClean 删掉重建，而旧挂载点仍引用着上一轮的 ffs 实例 ——
        // 复用就会让实例泄漏，反复几次后内核报 `Can't create any more FFS log contexts`，
        // 并连带把 UDC 绑定拖垮（实测 `udc ...: failed to start apx: -19`）。
        // umount 要求没有进程打开 ep0，由 App 侧在挂载前先调 FfsChannel.close() 保证。
        // 先让出系统占用的、与当前模式无关的 FunctionFS 实例。
        //
        // dmesg 铁证：`Can't create any more FFS log contexts` —— 内核 FFS 上下文已被
        // 系统 6 个实例（adb/aoa/ctrl/ipcr/mtp/ptp）占满，导致我们的 ffs function
        // 在 bind 阶段建不出上下文，进而拖垮整个 gadget 的 UDC 绑定
        // （`udc a600000.dwc3: failed to start apx`）。
        // 其中 ipcr / aoa / ctrl 只在对应 USB 模式下才用得上（当前是 adb），可让出；
        // **adb / mtp / ptp 绝不触碰** —— 那是调试通道本身，动了会直接失联。
        if (GadgetFeature.FFS in o.features) {
            for (f in RELEASABLE_FFS) {
                // 让出结果必须落盘：FFS 上下文耗尽是 UDC 绑定失败的主因之一，
                // 但 umount 是否真让出过实例，此前无任何记录可查。
                steps += Step(
                    "{ umount '/dev/usb-ffs/$f' 2>&1; echo \"umount $f rc=\$?\"; } " +
                        ">> /data/local/tmp/apx_ffs.txt",
                    optional = true,
                )
            }
            // 2026-09-21 真机裁决：FFS mount 全家桶**必须**收在本条件内。此前它们
            // 无条件执行 —— 即使 FFS feature 关闭（本机默认），每轮 mount functionfs
            // 也会触发 HyperOS 私有内核钩子的 FFS log context 检查，耗尽后整个
            // gadget（HID/ACM 与 FFS 无关！）`start: -19`，表现为写 UDC 报 EBUSY。
            // 这就是「FFS 明明没启用却拖垮整个复合设备」的根因。
            steps += Step("mkdir -p '${SysPath.FFS_MOUNT_DIR}'", optional = true)
            steps += Step("umount '${SysPath.FFS_MOUNT_DIR}' 2>/dev/null", optional = true)
            // 失败信息**落盘**：挂载成功后 adb 立即断开，logcat 是拿不到的；
            // 而这一步此前标了 optional，错误被完全吞掉，导致无法判断失败原因
            // （是 -ENOMEM 上下文耗尽，还是别的）。落到 /data/local/tmp 便于切回后读取。
            steps += Step(
                "{ mount -t functionfs '${SysPath.FFS_INSTANCE}' '${SysPath.FFS_MOUNT_DIR}' 2>&1; " +
                    "echo \"ffs mount rc=\$?\"; " +
                    "echo \"ffs.apx exists: \$(test -e '${SysPath.CONFIGFS_USB_GADGET}'/" +
                    "${GadgetConst.GADGET_NAME}/functions/ffs.apx && echo yes || echo no)\"; " +
                    "} >> /data/local/tmp/apx_ffs.txt",
                optional = true,
            )
            // 落盘当前 FFS 实例清单：核对「让出 ipcr/aoa/ctrl」之后还剩哪些实例占用
            // 上下文（内核报 `Can't create any more FFS log contexts` 时的关键证据）。
            steps += Step(
                "echo '--- ffs instances:' >> /data/local/tmp/apx_ffs.txt; " +
                    "ls '/dev/usb-ffs' >> /data/local/tmp/apx_ffs.txt 2>&1",
                optional = true,
            )
        }

        // 绑定 UDC 前**再解绑一次**。
        //
        // acquire() 里清过一次，但厂商 HAL 之后可能又把系统 gadget 绑回 UDC ——
        // 实测写自己 UDC 时稳定得到 EBUSY（"printf: write: Device or resource busy"），
        // 而系统里同时存在 g1 / g2 两个 gadget（sys.usb.configfs=2 时活动的是 g2）。
        // 把解绑紧贴在这里，可把竞态窗口压到最小。
        // 用 glob 枚举而非固定名字：厂商用哪个名字不可预设。
        //
        // 2026-09-21 真机教训：此 step 标 optional 且 `2>/dev/null`，解绑失败被完全
        // 吞掉，之后写 UDC 只剩一个孤立 EBUSY —— 根因（g1/g2 是否真被解绑、glob
        // 是否为空）无从判断。改为逐条落盘 rc（挂载成功后 adb 断开，logcat 不可得）。
        steps += Step(
            "for u in /config/usb_gadget/*/UDC; do " +
                "echo \"\$u rc=\$(echo '' > \"\$u\" 2>&1; echo \$?)\"; " +
                "done >> /data/local/tmp/apx_unbind.txt 2>&1",
            optional = true,
        )
        // 轮询等待 UDC 真正空闲（state 离开 configured/addressed/default）再绑定。
        //
        // 厂商 HAL 收到 sys.usb.config=none 后释放是**异步**的；抢在它前面写 UDC
        // 得到 EBUSY（实测 HyperOS + dwc3）。只读 sysfs，语义与 [UsbHalArbiter.isUdcFree]
        // 一致：读 /config/usb_gadget 会把内核挂住，绝不触碰。
        steps += Step(
            "for i in \$(seq 12); do " +
                "s=\$(cat '/sys/class/udc/$udc/state' 2>/dev/null); " +
                "case \"\$s\" in *configured*|*addressed*|*default*) sleep 0.5;; *) break;; esac; done; " +
                "echo \"udc-state=[\$s] after \$i rounds\" >> /data/local/tmp/apx_ffs.txt",
            optional = true,
        )
        // v1.9：HID 描述符**回读校验**——cp report_desc 之后立刻回读比对。
        //
        // 真机 2026-09-21：HyperOS 内核对 f_hid report_desc 的 show 恒返回
        // PAGE_SIZE(4096)（写入 2622 读回 4096，尾部为 0x00——HID 规范里
        // 0x00 前缀是合法 padding item，不致命）。因此校验不能用「长度相等」
        // （会恒失败拦死挂载——真机教训），改为**前 N 字节内容 cmp 一致**：
        // head -c 2622 | cmp 与源 bin 逐字节比对，一致才算写入成功。
        o.reportDescSource?.let { src ->
            val hidFd = o.functionDir(GadgetFeature.HID)
            steps += Step(
                "src=\$(wc -c < '$src' 2>/dev/null || echo 0); " +
                    "rd=\$(wc -c < '$hidFd/report_desc' 2>/dev/null || echo 0); " +
                    "ok=0; " +
                    "if [ \"\$rd\" = \"\$src\" ]; then ok=1; " +
                    "elif [ \"\$src\" != \"0\" ] && head -c \"\$src\" '$hidFd/report_desc' 2>/dev/null | " +
                    "cmp -s - '$src'; then ok=1; fi; " +
                    "echo \"hid desc src=\$src device=\$rd ok=\$ok\" >> /data/local/tmp/apx_ffs.txt; " +
                    "test \"\$ok\" = \"1\"",
            )
        }
        // 最后一步才绑定 UDC：此前任何失败都不会影响手机原有 USB 功能。
        //
        // 2026-09-21 真机：写 UDC 的 EBUSY 常为**瞬态**（厂商 HAL 与我们的解绑
        // 存在秒级竞态），一次失败就 fail 会让整轮挂载前功尽弃。改为最多重试
        // 3 次、每次间隔 1s；每次结果落盘，最终仍失败才判挂载失败。
        steps += Step(
            "ok=0; for t in 1 2 3; do " +
                "if printf '%s' '$udc' > '$g/UDC' 2>/tmp/apx_udc_err; then ok=1; break; else " +
                "echo \"udc try \$t rc=\$? err=\$(cat /tmp/apx_udc_err)\" >> /data/local/tmp/apx_unbind.txt; " +
                "sleep 1; fi; done; " +
                "test \$ok -eq 1",
        )
        steps += Step("chmod 666 '${SysPath.HIDG_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.ACM_DEVICE}'", optional = true)
        // FunctionFS 的 ep0/ep1/ep2 默认只有 root 能访问，App 会拿到 EACCES(errno=13)
        steps += Step("chmod 666 '${SysPath.FFS_MOUNT_DIR}'/ep* 2>/dev/null", optional = true)
        // UAC2 的 PCM 节点默认仅 audio 组可读写，而 App 属 untrusted_app 打不开；
        // 挂载期间 SELinux 已切 permissive，这里一并放开，供音频模块直接 read/write。
        steps += Step("chmod 666 /dev/snd/pcmC*D* 2>/dev/null", optional = true)
        return steps
    }

    /**
     * 复用模式：gadget / strings / configs 均已由系统 HAL 建好，只追加 function。
     *
     * 与创建模式有三个关键差异：
     * 1. `mkdir` **不带 -p** —— `-p` 会 stat 父目录，而该目录在内核异常状态下
     *    会把读取挂住，进而让 RootShell 超时断链（实测会丢失全部后续输出）；
     * 2. **不写 UDC** —— HAL 已绑定，抢着写等于自己把自己断开；
     * 3. 用 `ln -sf` 而非 `ln -s`，重复挂载时幂等。
     */
    private fun mountReuse(o: GadgetOptions): List<Step> {
        val steps = mutableListOf<Step>()
        for (f in o.features.sortedBy { it.ordinal }) {
            val fd = o.functionDir(f)
            val optional = f.optional
            // mkdir 允许失败：上一轮已建过的 function 会返回 EEXIST，
            // 这属于幂等复用的正常情况，不应中断挂载。
            // 先清掉可能残留的半残 function：若上一轮创建中断，目录可能存在
            // 但不是有效的 function item，链它会被内核判为非法目标（ln 报 EINVAL）。
            // 先删再建，保证 item 干净。
            steps += Step("rmdir '$fd' 2>/dev/null", optional = true)
            steps += Step("mkdir '$fd'", optional = true)
            steps += functionProps(f, fd, o)
            // **不能带 -f**：configfs 对「已存在链接」做 unlink+recreate 会返回
            // EINVAL（实测 "cannot create symbolic link ...: Invalid argument"）。
            // 因此先显式 rm（不存在也无妨），再 ln -s。
            steps += Step("rm -f '${o.configDir()}/${f.instance}' 2>/dev/null", optional = true)
            // 链接目标必须用**相对路径**：绝对路径在 configfs 上返回 EINVAL
            // （内核在 dentry 层解析失败），而从 configs/<name>/ 出发的
            // ../../functions/<fn> 才能被正确解析。
            // toybox 的 ln 会按**当前工作目录**校验 target 是否存在，而 configfs
            // 要求 target 相对**链接所在目录**解析 —— 只有 CWD 就是该目录时两者
            // 才一致。所以必须先 cd 过去再建链，否则报 ENOENT。
            steps += Step(
                "cd '${o.configDir()}' && ln -s '../../functions/${f.instance}' '${f.instance}'",
                optional,
            )
        }
        steps += Step("chmod 666 '${SysPath.HIDG_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.ACM_DEVICE}'", optional = true)
        return steps
    }

    /**
     * 各 function 的专属属性。
     *
     * **f_hid**：描述符字节流只能来自 shared/；缺失时创建模式的挂载必须失败（不写半个设备）。
     * 但复用模式下允许失败：function 若由上一轮建好，描述符已写入且 UDC 已绑定，
     * 重复写会返回 EBUSY —— 这不是错误，最终由 /dev/hidg0 能否打开来判定成败。
     *
     * **f_uac2**：音频格式必须在挂载时声明。不声明的话内核走默认值，很可能与 Android 侧
     * AudioRecord/AudioTrack 的格式对不上，表现为「声卡认到了但没声音」——这类问题
     * 在两端各自"看起来正常"时极难定位，所以两处必须用同一组 AudioConst 常量。
     *
     * **f_uvc / f_ncm**：走内核默认属性。
     */
    private fun functionProps(f: GadgetFeature, fd: String, o: GadgetOptions): List<Step> {
        val optional = f.optional
        val steps = mutableListOf<Step>()
        when (f) {
            GadgetFeature.HID -> {
                steps += Step("printf '%s' '${GadgetConst.HID_SUBCLASS_NONE}' > '$fd/subclass'", optional)
                steps += Step("printf '%s' '${GadgetConst.HID_PROTOCOL_NONE}' > '$fd/protocol'", optional)
                steps += Step("printf '%s' '${o.reportLength}' > '$fd/report_length'", optional)
                val src = o.reportDescSource
                if (src == null) {
                    steps += Step("false # missing HID report descriptor")
                } else {
                    steps += Step("cp '$src' '$fd/report_desc'", optional = o.reuse)
                }
            }
            GadgetFeature.UAC2 -> {
                // 手机 → PC（capture）：PC 侧看到"麦克风"
                steps += Step("printf '%s' '${AudioConst.CHANNEL_MASK_STEREO}' > '$fd/c_chmask'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_RATE}' > '$fd/c_srate'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_SIZE_BYTES}' > '$fd/c_ssize'", optional)
                // PC → 手机（playback）：PC 侧看到"扬声器"
                steps += Step("printf '%s' '${AudioConst.CHANNEL_MASK_STEREO}' > '$fd/p_chmask'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_RATE}' > '$fd/p_srate'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_SIZE_BYTES}' > '$fd/p_ssize'", optional)
                // 终端名：PC 设备列表里显示的名字
                steps += Step("printf '%s' '${AudioConst.C_TERMINAL}' > '$fd/c_terminal'", optional)
                steps += Step("printf '%s' '${AudioConst.P_TERMINAL}' > '$fd/p_terminal'", optional)
            }
            GadgetFeature.UVC -> {
                // v1.13：f_uvc 摄像头 configfs 树（内核 gadget-testing.rst UVC 节）。
                // 真机内核已确认 CONFIG_USB_CONFIGFS_F_UVC=y 且 usb_f_uvc 已加载。
                // 选 **MJPEG**：Windows usbvideo.sys 原生支持、无 GUID 对齐烦恼，
                // 且 Camera2 ImageReader(JPEG) 产出可直接喂 V4L2 输出节点（零转码）。
                // 所有写均为 optional：缺属性文件名（内核版本差异）不致命。
                val fdU = "'$fd"
                // —— 控制面 ——
                steps += Step("mkdir -p $fdU/control/header/h'")
                steps += Step("ln -sf $fdU/control/header/h' $fdU/control/class/fs/h", optional = true)
                steps += Step("ln -sf $fdU/control/header/h' $fdU/control/class/ss/h", optional = true)
                // —— MJPEG 格式 + 720p@30 帧 ——
                steps += Step("mkdir -p $fdU/streaming/mjpeg/m/frame/720p'")
                steps += Step("printf '%s' '1' > $fdU/streaming/mjpeg/m/bmaControls'", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming/mjpeg/m/bCopyProtect'", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming/mjpeg/m/bmInterlaceFlags'", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming/mjpeg/m/bVariableSize'", optional = true)
                steps += Step("printf '%s' '1280' > $fdU/streaming/mjpeg/m/frame/720p/wWidth'")
                steps += Step("printf '%s' '720' > $fdU/streaming/mjpeg/m/frame/720p/wHeight'")
                // 1280*720*8*30 ≈ 221 Mbps
                steps += Step("printf '%s' '221184000' > $fdU/streaming/mjpeg/m/frame/720p/dwMinBitRate'", optional = true)
                steps += Step("printf '%s' '221184000' > $fdU/streaming/mjpeg/m/frame/720p/dwMaxBitRate'", optional = true)
                steps += Step("printf '%s' '524288' > $fdU/streaming/mjpeg/m/frame/720p/dwMaxVideoFrameBufferSize'", optional = true)
                // 30fps = 333333×100ns；bFrameIntervalType=1（单一间隔）
                steps += Step("printf '%s' '1' > $fdU/streaming/mjpeg/m/frame/720p/bFrameIntervalType'", optional = true)
                steps += Step("printf '%s' '333333' > $fdU/streaming/mjpeg/m/frame/720p/dwFrameInterval'")
                steps += Step("printf '%s' '1' > $fdU/streaming/mjpeg/m/bDefaultFrameInterval'", optional = true)
                // —— 流头部与三类速率引用 ——
                steps += Step("mkdir -p $fdU/streaming/header/h'")
                steps += Step("ln -sf $fdU/streaming/mjpeg/m' $fdU/streaming/header/h/m")
                steps += Step("ln -sf $fdU/streaming/header/h' $fdU/streaming/class/fs/h", optional = true)
                steps += Step("ln -sf $fdU/streaming/header/h' $fdU/streaming/class/hs/h", optional = true)
                steps += Step("ln -sf $fdU/streaming/header/h' $fdU/streaming/class/ss/h", optional = true)
                // —— 等时/批量端点参数（属性名随内核版本有差异，全部 optional）——
                steps += Step("printf '%s' '1024' > $fdU/streaming_maxpacket' 2>/dev/null || printf '%s' '1024' > $fdU/maxpacket' 2>/dev/null", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming_maxburst' 2>/dev/null || printf '%s' '0' > $fdU/maxburst' 2>/dev/null", optional = true)
                steps += Step("echo 'apx-uvc' > $fdU/function_name' 2>/dev/null", optional = true)
            }
            else -> Unit  // f_ncm 走内核默认属性
        }
        return steps
    }

    /**
     * 生成卸载命令序列。
     *
     * **只解绑 UDC，绝不删除 gadget 本体** —— 硬要求，是多次重启换来的：
     *
     * 该内核在 gadget 被 `rmdir` 之后**不释放** `/sys/devices/virtual/android_usb/androidN`
     * （dmesg 铁证：`kobject_add_internal failed for android2 with -EEXIST`）。对象一直
     * 占着名字，于是**同一开机周期内再也创建不出 gadget**，唯一出路是重启手机。
     *
     * 反之只要 gadget 目录还在，下次挂载的 `mkdir -p` 会直接 stat 命中并跳过，
     * **不调用 mkdir(2)**，也就不触发内核注册，因此可以反复挂载/卸载。
     * 代价：需要改描述符之类配置时，走"解绑 UDC → 改属性 → 重新绑定"，
     * 而不是重建 gadget（`mount` 的属性写入本来就是幂等的）。
     */
    fun unmount(o: GadgetOptions): List<Step> {
        val g = o.root()
        val steps = mutableListOf<Step>()
        if (o.reuse) {
            // 系统 gadget：只摘掉我们追加的 function 与软链，
            // 绝不解绑 UDC（HAL 还绑着）、绝不删 g1 本体
            for (f in o.features.sortedByDescending { it.ordinal }) {
                steps += Step("rm -f '${o.configDir()}/${f.instance}'", optional = true)
                steps += Step("rmdir '${o.functionDir(f)}' 2>/dev/null", optional = true)
            }
            return steps
        }
        // 自建 gadget：只解绑 UDC。
        // 用 echo 而非 printf ''：后者产生 0 字节，shell 不会 write()，
        // configfs 收不到数据就不会真正执行解绑。
        steps += Step("echo '' > '$g/UDC'", optional = true)
        // 一并释放 functionfs：不 umount 会让 ffs 实例跨轮泄漏，反复挂载后内核
        // 耗尽 FFS 上下文并拖垮 UDC 绑定（详见 mount 中的说明）。
        steps += Step("umount '${SysPath.FFS_MOUNT_DIR}' 2>/dev/null", optional = true)
        return steps
    }

    /**
     * 现场兜底清理：把 gadget 恢复到「可以重新挂载」的状态。
     *
     * 与 [unmount] 同理，**绝不删除 gadget 本体** —— 删掉会触发内核 androidN
     * 不释放的死结（同一开机周期再也建不出 gadget，只能重启）。
     * 这里只做三件事：解绑 UDC、摘掉 configs 里的软链、清掉 functions 子项，
     * 好让下一次 [mount] 幂等地把子项重建回来。gadget 目录本身**始终保留**。
     *
     * 另有一条老约束：**不能用 `rm -rf`**。configfs 里属性文件无法 unlink，
     * `rm -rf` 第一步就报错退出，等于什么都没删，必须逐级 `rmdir`。
     */
    fun forceClean(o: GadgetOptions): List<Step> {
        val g = o.root()
        val steps = mutableListOf<Step>()
        // 先解绑 UDC：configfs 不允许删除仍绑定 UDC 的 gadget
        steps += Step("echo '' > '$g/UDC' 2>/dev/null", optional = true)
        for (f in o.features.sortedByDescending { it.ordinal }) {
            steps += Step(
                "rm -f '$g/configs/${o.config}/${f.instance}' 2>/dev/null",
                optional = true,
            )
            steps += Step("rmdir '$g/functions/${f.instance}' 2>/dev/null", optional = true)
        }
        steps += Step(
            "rmdir '$g/configs/${o.config}/strings/${UsbId.LANG_US}' 2>/dev/null",
            optional = true,
        )
        steps += Step("rmdir '$g/configs/${o.config}' 2>/dev/null", optional = true)
        steps += Step("rmdir '$g/strings/${UsbId.LANG_US}' 2>/dev/null", optional = true)
        // 刻意**不** rmdir '$g'：gadget 本体必须保留（原因见上）
        return steps
    }

    /**
     * 可以临时让出的系统 FunctionFS 实例（挂在 /dev/usb-ffs/<name> 下）。
     *
     * 内核的 FFS 上下文数量有限（实测 dmesg：`Can't create any more FFS log contexts`），
     * 被系统实例占满后我们自己就建不出来。这三个只在对应 USB 模式下才需要
     * （ipcr = 跨处理器通信、aoa = Android Open Accessory、ctrl = 控制），
     * 当前 adb 模式下让出去没有副作用。
     *
     * **adb / mtp / ptp 绝不能列入**：它们是调试与文件传输通道本身，
     * umount 掉会直接让设备失联。
     */
    private val RELEASABLE_FFS = listOf("ipcr", "aoa", "ctrl")
}
