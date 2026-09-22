package com.allperiph.gadget

import com.allperiph.core.Log
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
    // v1.11：UVC 默认关闭——真机实测 configfs 树 build 后 UDC 绑定被内核拒绝
    // （udc-state=[not attached]，2026-09-21 22:07），整复合设备挂载失败。
    // 内核 f_uvc 存在（CONFIG_USB_CONFIGFS_F_UVC=y），问题在 configfs 属性/
    // symlink 组合未被当前内核接受；待数据面完成后由 CameraModule 单独挂载
    // 迭代（App 内可抓 dmesg 定位具体被拒环节），不再拖累主开关。
    /**
     * f_uvc 摄像头。**默认关闭 —— v1.34 真机诊断已确认本 ROM 不可用**，原因不是描述符写错，
     * 而是**内核的 UVC configfs 软链操作会挂住内核**：
     *
     * ```
     * RootShell: timeout: ln -sf .../control/header/h → .../control/class/fs/h
     * RootShell: timeout: ln -sf .../streaming/mjpeg/m → .../streaming/header/h/m
     * GadgetManager: step failed: ln -sf ... (code=-1) → gadget error: ConfigFS 挂载失败
     * ```
     *
     * 挂住之后同一 shell 里后续所有 configfs 操作都失败，整个复合设备挂载被迫中止
     * （HID/ACM/音频一并不可用）。而 `ln -sf` 是 UVC 描述符组装的**必需**环节
     * （header 与格式/速率的关联），无法绕过。
     *
     * 逐层 mkdir 与帧目录路径已按真机修正（`streaming/mjpeg/m/720p`，configfs 须逐层建）；
     * 内核自动生成的树（含 control/header/h、streaming/{mjpeg,uncompressed,framebased}）
     * 已由 mount 内的 `ls -R` 落盘证实。代码保留以便在其他 ROM 上复用。
     *
     * 想让摄像头出图，走 ModuleId.CAMERA 注释里的另一条路线：
     * **系统摄像头** —— Camera2 采集 → 编码 → 经 NCM/TCP 上行（与副屏共用同一条 bulk 通道）。
     */
    UVC("uvc.usb0", false, true, "f_uvc 摄像头（本 ROM 的 configfs 软链会挂内核，默认关闭）"),
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

    /** 生成挂载命令序列（含 UDC 绑定） */
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

        steps += Step(
            "for l in '${o.configDir()}'/*; do rm -f \"\$l\" 2>/dev/null; done",
            optional = true,
        )
        for (f in o.features.sortedBy { it.ordinal }) {
            val fd = o.functionDir(f)
            val optional = f.optional
            steps += Step("mkdir -p '$fd'", optional)
            steps += functionProps(f, fd, o)
            steps += Step("rm -f '${o.configDir()}/${f.instance}' 2>/dev/null", optional = true)
            steps += Step(
                "cd '${o.configDir()}' && ln -s '../../functions/${f.instance}' '${f.instance}'",
                optional,
            )
        }

        if (GadgetFeature.FFS in o.features) {
            for (f in RELEASABLE_FFS) {
                steps += Step(
                    "{ umount '/dev/usb-ffs/$f' 2>&1; echo \"umount $f rc=\$?\"; } " +
                        ">> /data/local/tmp/apx_ffs.txt",
                    optional = true,
                )
            }
            steps += Step("mkdir -p '${SysPath.FFS_MOUNT_DIR}'", optional = true)
            steps += Step("umount '${SysPath.FFS_MOUNT_DIR}' 2>/dev/null", optional = true)
            steps += Step(
                "{ mount -t functionfs '${SysPath.FFS_INSTANCE}' '${SysPath.FFS_MOUNT_DIR}' 2>&1; " +
                    "echo \"ffs mount rc=\$?\"; " +
                    "echo \"ffs.apx exists: \$(test -e '${SysPath.CONFIGFS_USB_GADGET}'/" +
                    "${GadgetConst.GADGET_NAME}/functions/ffs.apx && echo yes || echo no)\"; " +
                    "} >> /data/local/tmp/apx_ffs.txt",
                optional = true,
            )
            steps += Step(
                "echo '--- ffs instances:' >> /data/local/tmp/apx_ffs.txt; " +
                    "ls '/dev/usb-ffs' >> /data/local/tmp/apx_ffs.txt 2>&1",
                optional = true,
            )
        }

        steps += Step(
            "for u in /config/usb_gadget/*/UDC; do " +
                "echo \"\$u rc=\$(echo '' > \"\$u\" 2>&1; echo \$?)\"; " +
                "done >> /data/local/tmp/apx_unbind.txt 2>&1",
            optional = true,
        )
        steps += Step(
            "for i in \$(seq 12); do " +
                "s=\$(cat '/sys/class/udc/$udc/state' 2>/dev/null); " +
                "case \"\$s\" in *configured*|*addressed*|*default*) sleep 0.5;; *) break;; esac; done; " +
                "echo \"udc-state=[\$s] after \$i rounds\" >> /data/local/tmp/apx_ffs.txt",
            optional = true,
        )
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
        // UDC 绑定已移至 UdcBinder（多步策略：常规 → 杀 HAL → 重试）。
        // 这里只做诊断日志，不再尝试绑定。
        steps += Step(
            "echo \"pre-bind: udc=$udc\" >> /data/local/tmp/apx_ffs.txt; " +
                "cat '/sys/class/udc/$udc/state' 2>/dev/null >> /data/local/tmp/apx_ffs.txt",
            optional = true,
        )
        steps += Step("chmod 666 '${SysPath.HIDG_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.ACM_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.FFS_MOUNT_DIR}'/ep* 2>/dev/null", optional = true)
        steps += Step("chmod 666 /dev/snd/pcmC*D* 2>/dev/null", optional = true)
        steps += Step("dmesg > /data/local/tmp/apx_dmesg.txt 2>&1", optional = true)
        steps += Step(
            "dmesg | grep -i -E 'uvc|udc|gadget|ffs|configfs|video' > /data/local/tmp/apx_dmesg_uvc.txt 2>&1",
            optional = true
        )
        return steps
    }

    /**
     * 复用模式：gadget / strings / configs 均已由系统 HAL 建好，只追加 function。
     */
    private fun mountReuse(o: GadgetOptions): List<Step> {
        val steps = mutableListOf<Step>()
        for (f in o.features.sortedBy { it.ordinal }) {
            val fd = o.functionDir(f)
            val optional = f.optional
            steps += Step("rmdir '$fd' 2>/dev/null", optional = true)
            steps += Step("mkdir '$fd'", optional = true)
            steps += functionProps(f, fd, o)
            steps += Step("rm -f '${o.configDir()}/${f.instance}' 2>/dev/null", optional = true)
            steps += Step(
                "cd '${o.configDir()}' && ln -s '../../functions/${f.instance}' '${f.instance}'",
                optional,
            )
        }
        steps += Step("chmod 666 '${SysPath.HIDG_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.ACM_DEVICE}'", optional = true)
        return steps
    }

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
                steps += Step("printf '%s' '${AudioConst.CHANNEL_MASK_STEREO}' > '$fd/c_chmask'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_RATE}' > '$fd/c_srate'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_SIZE_BYTES}' > '$fd/c_ssize'", optional)
                steps += Step("printf '%s' '${AudioConst.CHANNEL_MASK_STEREO}' > '$fd/p_chmask'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_RATE}' > '$fd/p_srate'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_SIZE_BYTES}' > '$fd/p_ssize'", optional)
                steps += Step("printf '%s' '${AudioConst.C_TERMINAL}' > '$fd/c_terminal'", optional)
                steps += Step("printf '%s' '${AudioConst.P_TERMINAL}' > '$fd/p_terminal'", optional)
            }
            GadgetFeature.UVC -> {
                val fdU = "'$fd"
                steps += Step(
                    "{ echo '--- uvc tree after mkdir:'; ls -R $fdU'; } > /data/local/tmp/apx_uvc_tree.txt 2>&1",
                    optional = true
                )
                steps += Step("mkdir -p $fdU/control/header/h'")
                steps += Step("mkdir -p $fdU/streaming/mjpeg/m'", optional = true)
                steps += Step("dmesg | tail -40 > /data/local/tmp/apx_uvc_err.txt 2>&1", optional = true)
                steps += Step(
                    "{ echo '--- after mkdir m:'; ls -R $fdU/streaming'; } >> /data/local/tmp/apx_uvc_tree.txt 2>&1",
                    optional = true
                )
                steps += Step("mkdir -p $fdU/streaming/mjpeg/m/720p'", optional = true)
                steps += Step("printf '%s' '1' > $fdU/streaming/mjpeg/m/bmaControls'", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming/mjpeg/m/bCopyProtect'", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming/mjpeg/m/bmInterlaceFlags'", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming/mjpeg/m/bVariableSize'", optional = true)
                steps += Step("printf '%s' '1280' > $fdU/streaming/mjpeg/m/720p/wWidth'")
                steps += Step("printf '%s' '720' > $fdU/streaming/mjpeg/m/720p/wHeight'")
                steps += Step("printf '%s' '221184000' > $fdU/streaming/mjpeg/m/720p/dwMinBitRate'", optional = true)
                steps += Step("printf '%s' '221184000' > $fdU/streaming/mjpeg/m/720p/dwMaxBitRate'", optional = true)
                steps += Step("printf '%s' '524288' > $fdU/streaming/mjpeg/m/720p/dwMaxVideoFrameBufferSize'", optional = true)
                steps += Step("printf '%s' '1' > $fdU/streaming/mjpeg/m/720p/bFrameIntervalType'", optional = true)
                steps += Step("printf '%s' '333333' > $fdU/streaming/mjpeg/m/720p/dwFrameInterval'")
                steps += Step("printf '%s' '1' > $fdU/streaming/mjpeg/m/bDefaultFrameInterval'", optional = true)
                steps += Step("mkdir -p $fdU/streaming/header/h'")
                steps += Step("ln -sf '$fd/streaming/mjpeg/m' '$fd/streaming/header/h/m'")
                steps += Step("ln -sf '$fd/streaming/header/h' '$fd/streaming/class/fs/h'", optional = true)
                steps += Step("ln -sf '$fd/streaming/header/h' '$fd/streaming/class/hs/h'", optional = true)
                steps += Step("ln -sf '$fd/streaming/header/h' '$fd/streaming/class/ss/h'", optional = true)
                steps += Step("printf '%s' '1024' > $fdU/streaming_maxpacket' 2>/dev/null || printf '%s' '1024' > $fdU/maxpacket' 2>/dev/null", optional = true)
                steps += Step("printf '%s' '0' > $fdU/streaming_maxburst' 2>/dev/null || printf '%s' '0' > $fdU/maxburst' 2>/dev/null", optional = true)
                steps += Step("echo 'apx-uvc' > $fdU/function_name' 2>/dev/null", optional = true)
            }
            else -> Unit
        }
        return steps
    }

    fun unmount(o: GadgetOptions): List<Step> {
        val g = o.root()
        val steps = mutableListOf<Step>()
        if (o.reuse) {
            for (f in o.features.sortedByDescending { it.ordinal }) {
                steps += Step("rm -f '${o.configDir()}/${f.instance}'", optional = true)
                steps += Step("rmdir '${o.functionDir(f)}' 2>/dev/null", optional = true)
            }
            return steps
        }
        steps += Step("echo '' > '$g/UDC'", optional = true)
        steps += Step("umount '${SysPath.FFS_MOUNT_DIR}' 2>/dev/null", optional = true)
        return steps
    }

    fun forceClean(o: GadgetOptions): List<Step> {
        val g = o.root()
        val steps = mutableListOf<Step>()
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
        return steps
    }

    private val RELEASABLE_FFS = listOf("ipcr", "aoa", "ctrl")
}
