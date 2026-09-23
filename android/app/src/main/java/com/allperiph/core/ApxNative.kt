package com.allperiph.core

/**
 * shared/（C++17 协议库）的唯一 Kotlin 入口。
 *
 * 设计原则：
 * 1. 二进制布局**只**在 shared 里生成，Kotlin 侧绝不手写字节序或 HID 描述符魔数
 *    （ARCHITECTURE §4「描述符字节流由 shared/ 生成，禁止各模块手写魔数」）；
 * 2. Kotlin 只传**物理量原始值 + 元数据**，单位换算 / 轴向转换 / 定标指数由 shared 完成；
 * 3. native 库缺失时本对象降级为不可用，调用方应走 [isAvailable] 分支而不是崩溃。
 *
 * ——————————————————————————— JNI 符号契约（供 L1 / core-proto 实现）—————————————————————————
 * 本文件声明为 `object`，因此各 external 函数在 JVM 层是 **ApxNative 单例的实例方法**，
 * C++ 侧第二参数为 `jobject`（不是 `jclass`）。动态库名 `libapx.so`（System.loadLibrary("apx")），
 * 由 `android/app/src/main/cpp/CMakeLists.txt` 产出，target 名必须为 `apx`。
 *
 *   Java_com_allperiph_core_ApxNative_nativeInit(JNIEnv*, jobject) -> jboolean
 *   Java_com_allperiph_core_ApxNative_protocolVersion(JNIEnv*, jobject) -> jint
 *   Java_com_allperiph_core_ApxNative_hidReportDescriptor(JNIEnv*, jobject) -> jbyteArray
 *   Java_com_allperiph_core_ApxNative_hidMaxReportLength(JNIEnv*, jobject) -> jint
 *   Java_com_allperiph_core_ApxNative_hidReportSize(JNIEnv*, jobject, jint reportId) -> jint
 *   Java_com_allperiph_core_ApxNative_packImuBatch(JNIEnv*, jobject, jint sensorId, jint flags,
 *                                                   jlong periodNs, jlong baseTsNs,
 *                                                   jfloatArray xyz, jint sampleCount,
 *                                                   jint accuracy) -> jbyteArray
 *   Java_com_allperiph_core_ApxNative_packLowFreq(JNIEnv*, jobject, jint sensorId, jint state,
 *                                                  jint event, jlong tsNs,
 *                                                  jfloat v0, jfloat v1, jfloat v2) -> jbyteArray
 *   Java_com_allperiph_core_ApxNative_packVendorStatus(JNIEnv*, jobject, jint status, jint linkSpeed,
 *                                                       jlong moduleMask, jint errorCode,
 *                                                       jlong uptimeMs, jint lastSeq) -> jbyteArray
 *   Java_com_allperiph_core_ApxNative_parseVendorOut(JNIEnv*, jobject, jbyteArray report) -> jintArray
 *   Java_com_allperiph_core_ApxNative_crc32(JNIEnv*, jobject, jbyteArray data) -> jint
 *
 * 若 L1 更希望静态方法（`jclass`），请把这些声明改为顶层 external 并给文件加
 * `@file:JvmName("ApxNative")`；**两种写法符号名相同、仅第二参数不同**，改动前请同步本注释。
 */
object ApxNative {

    private const val TAG = "ApxNative"
    private const val LIB = "apx"

    /** native 库是否可用；false 时所有打包接口返回 null，由调用方降级 */
    @Volatile
    var isAvailable: Boolean = false
        private set

    /** 加载失败原因（排障用，UI 可直接展示） */
    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary(LIB)
            isAvailable = nativeInit()
            Log.i(TAG, "shared lib loaded, init=$isAvailable")
        } catch (t: Throwable) {
            loadError = t.message
            isAvailable = false
            // 本机无 NDK 产物时属预期情况，不 crash（L1 未交付 shared/ 时走该分支）
            Log.w(TAG, "shared lib unavailable: ${t.message}")
        }
    }

    // ————————————————————————— 能力查询 —————————————————————————

    /** 协议版本 u16（§5，当前 1.0 → 0x0100） */
    external fun protocolVersion(): Int

    /** 完整 HID 报告描述符字节流（6 个 TLC），写入 ConfigFS report_desc */
    external fun hidReportDescriptor(): ByteArray

    /** ConfigFS f_hid 的 report_length 取值（= 描述符中最大报告长度） */
    external fun hidMaxReportLength(): Int

    /** 指定 Report ID 的完整报告长度（含首字节 Report ID），用于校验/补齐 */
    external fun hidReportSize(reportId: Int): Int

    /**
     * 所有「传感器」TLC 的 Report ID（Usage Page = Sensors 0x20）。
     *
     * 挂载后据此为每个 TLC 登记 Feature Report —— Windows 的 SensorsHIDClassDriver
     * 启动时会索取 Report State / Report Interval，不登记就会 Code 10 启动失败。
     */
    external fun sensorReportIds(): IntArray

    /** [sensorReportIds] 的安全包装；libapx 不可用时返回空数组。 */
    fun sensorReportIdsOrNull(): IntArray =
        if (isAvailable) runCatching { sensorReportIds() }.getOrDefault(IntArray(0)) else IntArray(0)

    // ————————————————————————— 报告打包（PROTOCOL §2）—————————————————————————

    /**
     * §2.3 Report ID 1：IMU 批量。
     * @param xyz 长度 3*sampleCount，Android 原始单位（m/s²、rad/s、µT）
     * @param periodNs 相邻样本间隔；@param baseTsNs 第 0 个样本时间戳
     * @return 完整报告（首字节 Report ID），失败返回空数组
     */
    external fun packImuBatch(
        sensorId: Int,
        flags: Int,
        periodNs: Long,
        baseTsNs: Long,
        xyz: FloatArray,
        sampleCount: Int,
        accuracy: Int,
    ): ByteArray

    /**
     * §2.4 Report ID 2：低频传感器。v0..v2 为 Android 原始单位浮点值，
     * 定标整数与指数由 shared 按描述符声明处理。
     */
    external fun packLowFreq(
        sensorId: Int,
        state: Int,
        event: Int,
        tsNs: Long,
        v0: Float,
        v1: Float,
        v2: Float,
    ): ByteArray

    /**
     * §2.7 Report ID 5 状态上报（手机→PC）。
     *
     * v1.1：该 TLC 描述符必须同时声明 **Input / Output / Feature**，状态经 **Input 报告**
     * 上行（不再只依赖 FEATURE 的 Get_Report）；尾部新增 `lastSeq` 回显最后执行的命令 seq。
     *
     * @param moduleMask §2.9 位图，64 位（传感器 bit0..25 + 模块 bit32..37）
     * @param lastSeq 最后执行成功的命令 seq；命令被拒绝时保持上一次的值
     */
    external fun packVendorStatus(
        status: Int,
        linkSpeed: Int,
        moduleMask: Long,
        errorCode: Int,
        uptimeMs: Long,
        lastSeq: Int,
    ): ByteArray

    /**
     * §2.7 OUT 报告解析。
     * @return [cmd, seq, payloadLen, p0..pN]，payload 字节以无符号展开为 Int；
     *         非法报告返回空数组。
     */
    external fun parseVendorOut(report: ByteArray): IntArray

    /**
     * §2.6 Report ID 4：Consumer 按键（usage 位图，v1.2）。
     * 位定义见 ApxIds 的 ConsumerKeyBit（bit0=音量+ … bit6=下一曲）。
     * **按下与释放均发全量位图**（4B：reportId + u16 + reserved），PC 按位比对得边沿。
     * @return 4 字节报告（首字节 Report ID=4）；失败返回空数组
     */
    external fun packConsumerBitmap(keyBitmap: Int): ByteArray

    /** [packConsumerBitmap] 的安全包装；libapx 不可用时返回空数组。 */
    fun packConsumerBitmapOrNull(keyBitmap: Int): ByteArray =
        if (isAvailable) runCatching { packConsumerBitmap(keyBitmap) }.getOrDefault(ByteArray(0))
        else ByteArray(0)

    /**
     * §2.13 Report ID 22：USB 游戏手柄（16 键位图 + 4 轴，7B）。
     * @param buttons bit0..15 → 按钮 1..16；x/y 左摇杆、rx/ry 右摇杆（-127..127，内部限幅）
     * @return 完整报告（首字节 Report ID=22）；失败返回空数组
     */
    external fun packGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int): ByteArray

    /** [packGamepad] 的安全包装；libapx 不可用时返回空数组。 */
    fun packGamepadOrNull(buttons: Int, x: Int, y: Int, rx: Int, ry: Int): ByteArray =
        if (isAvailable) runCatching { packGamepad(buttons, x, y, rx, ry) }.getOrDefault(ByteArray(0))
        else ByteArray(0)

    /** §3 帧 CRC32（M4 bulk 通道用） */
    external fun crc32(data: ByteArray): Int

    // ————————————————————————— 生命周期 —————————————————————————

    external fun nativeInit(): Boolean

    // ————————————————————————— 便捷封装 —————————————————————————

    /** 不可用时返回 null，避免调用方到处判空 */
    fun packImuBatchOrNull(
        sensorId: Int,
        flags: Int,
        periodNs: Long,
        baseTsNs: Long,
        xyz: FloatArray,
        sampleCount: Int,
        accuracy: Int,
    ): ByteArray? = guard {
        packImuBatch(sensorId, flags, periodNs, baseTsNs, xyz, sampleCount, accuracy)
    }

    fun packLowFreqOrNull(
        sensorId: Int,
        state: Int,
        event: Int,
        tsNs: Long,
        v0: Float,
        v1: Float,
        v2: Float,
    ): ByteArray? = guard { packLowFreq(sensorId, state, event, tsNs, v0, v1, v2) }

    fun packVendorStatusOrNull(
        status: Int,
        linkSpeed: Int,
        moduleMask: Long,
        errorCode: Int,
        uptimeMs: Long,
        lastSeq: Int,
    ): ByteArray? = guard { packVendorStatus(status, linkSpeed, moduleMask, errorCode, uptimeMs, lastSeq) }

    fun hidReportDescriptorOrNull(): ByteArray? = guard { hidReportDescriptor() }

    fun hidMaxReportLengthOrDefault(): Int =
        if (isAvailable) {
            runCatching { hidMaxReportLength() }
                .getOrDefault(GadgetConst.HID_REPORT_LENGTH_FALLBACK)
                .coerceIn(8, HID_MAX_REPORT_BYTES)
        } else {
            GadgetConst.HID_REPORT_LENGTH_FALLBACK
        }

    fun hidReportSizeOrDefault(reportId: Int): Int =
        if (isAvailable) {
            runCatching { hidReportSize(reportId) }
                .getOrNull()
                ?.takeIf { it in 1..HID_MAX_REPORT_BYTES }
                ?: 0
        } else {
            0
        }

    private inline fun guard(block: () -> ByteArray): ByteArray? =
        if (!isAvailable) {
            null
        } else {
            runCatching(block)
                .onFailure { Log.w(TAG, "native call failed: ${it.message}") }
                .getOrNull()
        }
}
