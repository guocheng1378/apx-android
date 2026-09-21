package com.allperiph.core

/**
 * 所有**文件系统路径 / sysfs 节点 / 系统属性名 / USB 标识**集中在此。
 * 其余模块（尤其 gadget/）禁止内联字面量路径，一律引用这里，便于不同机型适配时只改一处。
 */

object SysPath {
    /** ConfigFS 根（Android 8+ 通用挂载点；部分内核为 /config/usb_gadget） */
    const val CONFIGFS_USB_GADGET = "/config/usb_gadget"

    /** UDC 控制器类目录 */
    const val UDC_CLASS = "/sys/class/udc"

    /** UDC 当前速度节点名（相对 /sys/class/udc/<name>/） */
    const val UDC_CURRENT_SPEED = "current_speed"
    const val UDC_STATE = "state"

    /** f_hid 创建的字符设备 */
    const val HIDG_DEVICE = "/dev/hidg0"

    /** f_acm 创建的 CDC ACM 设备（Windows 侧枚举为 COM 口） */
    const val ACM_DEVICE = "/dev/ttyGS0"

    /**
     * FunctionFS 挂载点（副屏 bulk 通道）。
     * 挂载后目录内出现 `ep0`（写描述符）与 `ep1`/`ep2`（bulk 数据）。
     */
    const val FFS_MOUNT_DIR = "/dev/usb-ffs/apx"

    /** FunctionFS 的 ep0：用户态在此写入接口/端点描述符（会阻塞到 UDC 绑定） */
    const val FFS_EP0 = "$FFS_MOUNT_DIR/ep0"

    /** FunctionFS 实例名：对应 configfs 的 `functions/ffs.<name>`，同时是 mount 的 dev_name */
    const val FFS_INSTANCE = "apx"

    /** 描述符临时投递目录：App 以普通权限写入，再由 root 侧 cp 到 ConfigFS */
    const val APP_STAGING_DIR = "apx"

    /** 与 scripts/ 共用的可写目录（adb shell 也用同一路径，便于脱离 App 验证） */
    const val SHARED_TMP_DIR = "/data/local/tmp/apx"
    const val SHARED_TMP_DESC = "$SHARED_TMP_DIR/hid_report_desc.bin"
}

/** USB-IF / ConfigFS 需要的描述符字段 */
object UsbId {
    /** Linux Foundation VID：避免编造厂商号 */
    const val ID_VENDOR = "0x1d6b"
    /** 0x0104 = Multifunction Composite Gadget（内核自带 gadget 惯用） */
    const val ID_PRODUCT = "0x0104"
    const val BCD_DEVICE = "0x0310"

    /** USB 2.0 / USB 3.0 声明：按 UDC 实测速度二选一（架构 §6.1） */
    const val BCD_USB_20 = "0x0200"
    const val BCD_USB_30 = "0x0300"

    const val MANUFACTURER = "AllPeriph"
    const val PRODUCT = "AllPeriph Composite"
    const val SERIAL = "APX00000001"
    const val CONFIGURATION = "AllPeriph Composite"
    const val LANG_US = "0x409"
}

/** ConfigFS 目录布局与 f_hid 参数 */
object GadgetConst {
    const val GADGET_NAME = "apx"
    const val CONFIG_NAME = "c.1"

    /** 非 boot 接口的自定义 HID：bInterfaceSubClass/bInterfaceProtocol 均为 0。
     *  不伪装成 boot 键盘/鼠标，避免 PC 侧走 boot 协议解析我们的多 TLC 描述符。 */
    const val HID_SUBCLASS_NONE = "0"
    const val HID_PROTOCOL_NONE = "0"

    /** f_hid 单次读写上限，必须 ≥ 描述符中最大报告长度 */
    const val HID_REPORT_LENGTH_FALLBACK = HID_MAX_REPORT_BYTES

    /** 等待设备节点出现的重试参数 */
    const val DEVICE_NODE_WAIT_MS = 3_000L
    const val DEVICE_NODE_POLL_MS = 50L
}

/**
 * UAC2（USB Audio Class 2）参数：把手机变成 PC 的麦克风与扬声器。
 *
 * 这些值必须**同时**用于两处，否则会出现"设备认到了但没声音"这类难查的问题：
 *   1. ConfigFS 的 f_uac2 属性（本文件，决定 USB 侧协商格式）
 *   2. App 侧 AudioRecord/AudioTrack 与 ALSA 设备（决定 Android 侧格式）
 * 不声明的话内核会走默认值，与 Android 音频框架很可能对不上。
 */
object AudioConst {
    /** 采样率（Hz）。48k 是 UAC2 与 Android 音频框架的重合点，兼容性最好 */
    const val SAMPLE_RATE = 48000

    /** 采样位宽（字节）：2 = 16-bit PCM */
    const val SAMPLE_SIZE_BYTES = 2

    /** 通道数（AudioRecord/AudioTrack 与 ALSA 的 hw_params 用这个整数值） */
    const val CHANNELS = 2

    /** 通道掩码：3 = 立体声（bit0=L, bit1=R）；单声道用 1 */
    const val CHANNEL_MASK_STEREO = "3"

    /** capture 终端名（PC 侧显示为"手机麦克风"的设备名） */
    const val C_TERMINAL = "AllPeriph Mic"

    /** playback 终端名（PC 侧显示为"手机扬声器"的设备名） */
    const val P_TERMINAL = "AllPeriph Speaker"

    /**
     * ALSA 声卡名关键字。Android 侧据此在 /proc/asound/cards 中定位 UAC2 gadget，
     * 再解析 /proc/asound/pcm 得到 pcmC*D* 设备号 —— **不要硬编码 hw:0,0**，
     * 设备号随内核枚举顺序变化。
     */
    const val ALSA_CARD_KEYWORD = "UAC2Gadget"
}

/** 系统属性：UDC 抢占相关（架构 §6.2） */
object SysProp {
    const val USB_CONFIG = "sys.usb.config"
    const val USB_STATE = "sys.usb.state"
    const val USB_CONFIGFS = "sys.usb.configfs"

    /** 释放 UDC 后等待 Android USB HAL 真正卸载的时间 */
    const val RELEASE_WAIT_MS = 800L
    const val RELEASE_POLL_MS = 50L
}

/** 时间常量：协议统一使用纳秒 */
object TimeConst {
    const val NS_PER_SECOND = 1_000_000_000L
    const val NS_PER_MS = 1_000_000L
    const val US_PER_SECOND = 1_000_000
}
