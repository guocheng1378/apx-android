// JNI 桥：把 shared/ 的协议实现暴露给 Kotlin（core/ApxNative.kt + core/UsbBulkChannel）。
//
// 契约（与 ApxNative.kt 文件头一致）：
//   动态库 libapx.so；ApxNative 是 object，故 C++ 第二参数为 jobject（不是 jclass）。
//
// 本文件只做「参数搬运 + 调 shared」，不含任何协议逻辑：
//   单位换算 / 定标 / 轴向对齐在 units.h，报告布局在 hid_layout.h，描述符在 hid_descriptor.h。
#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "apx/frame.h"
#include "apx/hid_descriptor.h"
#include "apx/hid_layout.h"
#include "apx/sensors_id.h"

#if defined(APX_HAVE_ANDROID_LOG)
#include <android/log.h>
#define APX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "apx", __VA_ARGS__)
#define APX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "apx", __VA_ARGS__)
#else
#define APX_LOGI(...) ((void)0)
#define APX_LOGW(...) ((void)0)
#endif

// UsbBulkChannel / HidFeature / AlsaPcm 的原生实现需要 POSIX 文件描述符
#if !defined(_WIN32)
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>
#define APX_HAVE_POSIX_IO 1
#endif

#if defined(__ANDROID__)
// 内核 uapi（NDK sysroot 自带），裸 ALSA PCM 的 ioctl 与结构体定义
#include <sound/asound.h>
#define APX_HAVE_ALSA_UAPI 1
// FunctionFS：副屏 bulk 通道（描述符由用户态写 ep0）
#include <linux/usb/ch9.h>
#include <linux/usb/functionfs.h>
#define APX_HAVE_FUNCTIONFS_UAPI 1
// V4L2：f_uvc 摄像头 gadget 的输出节点（Camera2 采集 JPEG → write 到 /dev/videoN）
#include <linux/videodev2.h>
#define APX_HAVE_V4L2_UAPI 1
#endif

namespace {

constexpr jint kProtocolVersion = 0x0100;  // §5，当前 v1.0

jbyteArray toByteArray(JNIEnv* env, const uint8_t* data, size_t len) {
    std::vector<jbyte> tmp(len);
    for (size_t i = 0; i < len; ++i) tmp[i] = static_cast<jbyte>(data[i]);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr == nullptr) return nullptr;
    if (len > 0) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), tmp.data());
    return arr;
}

// bulk 端点：每个 streamId 维护**一对** fd（读 / 写），路径可用 nativeSetPaths 覆盖。
//
// 为什么不是一个 fd：FunctionFS 的端点方向在描述符里就固定了（IN 或 OUT），
// 单个 epN 文件只能单向使用；而上层 BulkTransport 要求同一通道既能读（收 video）
// 又能写（发 touch）。故拆成 rx / tx 两个 fd。
struct BulkChannel {
    int         fdRx = -1;   ///< 读：PC → 手机（FFS 的 OUT 端点）
    int         fdTx = -1;   ///< 写：手机 → PC（FFS 的 IN 端点）
    std::string rxPath;
    std::string txPath;

    bool isOpen() const { return fdRx >= 0 && fdTx >= 0; }
};

BulkChannel& channelOf(jint streamId) {
    static BulkChannel gChannels[5];  // 0=video 1=audio 2=touch 3=ctrl 4=telemetry
    if (streamId < 0) streamId = 0;
    if (streamId > 4) streamId = 4;
    return gChannels[streamId];
}

// jbyteArray -> std::vector<uint8_t>
bool fromByteArray(JNIEnv* env, jbyteArray src, std::vector<uint8_t>& out) {
    if (src == nullptr) return false;
    const jsize n = env->GetArrayLength(src);
    if (n < 0) return false;
    out.resize(static_cast<size_t>(n));
    if (n == 0) return true;
    jbyte* p = env->GetByteArrayElements(src, nullptr);
    if (p == nullptr) return false;
    for (jsize i = 0; i < n; ++i) out[static_cast<size_t>(i)] = static_cast<uint8_t>(p[i]);
    env->ReleaseByteArrayElements(src, p, JNI_ABORT);
    return true;
}

}  // namespace

extern "C" {

// ============================ core.ApxNative ===============================
JNIEXPORT jboolean JNICALL
Java_com_allperiph_core_ApxNative_nativeInit(JNIEnv*, jobject) {
    // 自检：描述符非空且最大报告长度合法
    const std::vector<uint8_t> desc = apx::buildReportDescriptor();
    const uint32_t maxLen = apx::maxReportLength();
    const bool ok = !desc.empty() && maxLen > 0 && maxLen <= apx::kMaxReportSize;
    APX_LOGI("nativeInit desc=%u maxReport=%u ok=%d",
             static_cast<unsigned>(desc.size()), static_cast<unsigned>(maxLen),
             static_cast<int>(ok));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_ApxNative_protocolVersion(JNIEnv*, jobject) {
    return kProtocolVersion;
}

JNIEXPORT jbyteArray JNICALL
Java_com_allperiph_core_ApxNative_hidReportDescriptor(JNIEnv* env, jobject) {
    const std::vector<uint8_t> desc = apx::buildReportDescriptor();
    return toByteArray(env, desc.empty() ? nullptr : desc.data(), desc.size());
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_ApxNative_hidMaxReportLength(JNIEnv*, jobject) {
    return static_cast<jint>(apx::maxReportLength());
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_ApxNative_hidReportSize(JNIEnv*, jobject, jint reportId) {
    if (reportId < 0 || reportId > 255) return 0;
    return static_cast<jint>(apx::reportSizeById(static_cast<uint8_t>(reportId)));
}

/**
 * 返回所有「传感器」TLC 的 Report ID（Usage Page = Sensors 0x20）。
 *
 * Kotlin 侧据此调用 HidFeature 登记 Feature Report。
 * 必须由 native 给权威列表：core/ApxIds.kt 的 ReportId 仍是 v1.0 的旧值
 * （LOW_FREQ = 2），而 v1.2 已把低频拆成 9 个独立 Report ID，两边会漂移。
 */
JNIEXPORT jintArray JNICALL
Java_com_allperiph_core_ApxNative_sensorReportIds(JNIEnv* env, jobject) {
    size_t n = 0;
    const uint8_t* ids = apx::tlcReportIds(n);
    std::vector<jint> out;
    out.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        if (apx::usagePageOf(ids[i]) == apx::kPageSensor) {
            out.push_back(static_cast<jint>(ids[i]));
        }
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (arr != nullptr && !out.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    }
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_allperiph_core_ApxNative_packImuBatch(JNIEnv* env, jobject, jint sensorId,
                                               jint flags, jlong periodNs, jlong baseTsNs,
                                               jfloatArray xyz, jint sampleCount,
                                               jint accuracy) {
    if (xyz == nullptr || sampleCount <= 0) return nullptr;
    const jsize need = static_cast<jsize>(sampleCount) * 3;
    if (env->GetArrayLength(xyz) < need) return nullptr;

    jfloat* src = env->GetFloatArrayElements(xyz, nullptr);
    if (src == nullptr) return nullptr;
    std::vector<float> vals(static_cast<size_t>(need));
    for (jsize i = 0; i < need; ++i) vals[static_cast<size_t>(i)] = static_cast<float>(src[i]);
    env->ReleaseFloatArrayElements(xyz, src, JNI_ABORT);

    std::vector<uint8_t> buf(apx::kSizeImuBatchReport);
    const size_t n = apx::packImuBatch(buf.data(), buf.size(),
                                       static_cast<uint8_t>(sensorId),
                                       static_cast<uint8_t>(flags),
                                       static_cast<uint32_t>(periodNs),
                                       static_cast<uint64_t>(baseTsNs),
                                       vals.data(), static_cast<size_t>(sampleCount),
                                       accuracy);
    if (n == 0) return nullptr;
    return toByteArray(env, buf.data(), n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_allperiph_core_ApxNative_packLowFreq(JNIEnv* env, jobject, jint sensorId, jint state,
                                              jint event, jlong tsNs, jfloat v0, jfloat v1,
                                              jfloat v2) {
    // v1.2：协议把低频传感器拆成各自独立的 Report ID(7..15)，但**JNI 契约保持不变** ——
    // Kotlin 侧仍按 sensorId 调用，这里映射一次即可，不必让 Android 侧跟着改。
    const uint8_t reportId = apx::lowFreqReportOfSensorId(static_cast<uint8_t>(sensorId));
    if (reportId == 0) return nullptr;  // 非低频传感器编号（含 IMU 与电池温度）

    std::vector<uint8_t> buf(apx::kSizeLowFreqReport);
    const size_t n = apx::packLowFreqById(buf.data(), buf.size(), reportId,
                                          static_cast<uint8_t>(state),
                                          static_cast<uint8_t>(event),
                                          static_cast<uint64_t>(tsNs), static_cast<float>(v0),
                                          static_cast<float>(v1), static_cast<float>(v2));
    if (n == 0) return nullptr;
    return toByteArray(env, buf.data(), n);
}

JNIEXPORT jbyteArray JNICALL
Java_com_allperiph_core_ApxNative_packVendorStatus(JNIEnv* env, jobject, jint status,
                                                   jint linkSpeed, jlong moduleMask,
                                                   jint errorCode, jlong uptimeMs, jint lastSeq) {
    std::vector<uint8_t> buf(apx::kSizeVendorStatusRep);
    const size_t n = apx::packVendorStatus(buf.data(), buf.size(), static_cast<uint8_t>(status),
                                           static_cast<uint8_t>(linkSpeed),
                                           static_cast<uint64_t>(moduleMask),
                                           static_cast<uint32_t>(errorCode),
                                           static_cast<uint64_t>(uptimeMs),
                                           static_cast<uint8_t>(lastSeq));
    if (n == 0) return nullptr;
    return toByteArray(env, buf.data(), n);
}

// §2.6 Report ID 4 —— Consumer 按键（v1.2 usage 位图）。
// 按下与释放均发全量位图（4B：reportId + u16 位图 + reserved），
// PC 侧按位比对得边沿事件；蓝牙版走 BtHidDevice 的 Consumer TLC（Report ID 3）。
JNIEXPORT jbyteArray JNICALL
Java_com_allperiph_core_ApxNative_packConsumerBitmap(JNIEnv* env, jobject, jint keyBitmap) {
    std::vector<uint8_t> buf(apx::kSizeConsumerReport);
    const size_t n = apx::packConsumerBitmap(buf.data(), buf.size(),
                                             static_cast<uint16_t>(keyBitmap & 0xFFFF));
    if (n == 0) return nullptr;
    return toByteArray(env, buf.data(), n);
}

// 返回 int[]：{cmd, seq, payloadLen, payload...}
JNIEXPORT jintArray JNICALL
Java_com_allperiph_core_ApxNative_parseVendorOut(JNIEnv* env, jobject, jbyteArray report) {
    std::vector<uint8_t> raw;
    if (!fromByteArray(env, report, raw)) return nullptr;

    std::vector<uint8_t> payload(apx::kMaxVendorPayload);
    uint8_t cmd = 0, seq = 0;
    size_t plen = 0;
    if (!apx::parseVendorOut(raw.empty() ? nullptr : raw.data(), raw.size(), cmd, seq,
                             payload.data(), payload.size(), plen)) {
        return nullptr;
    }
    std::vector<jint> out;
    out.reserve(3 + plen);
    out.push_back(static_cast<jint>(cmd));
    out.push_back(static_cast<jint>(seq));
    out.push_back(static_cast<jint>(plen));
    for (size_t i = 0; i < plen; ++i) out.push_back(static_cast<jint>(payload[i]));

    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_ApxNative_crc32(JNIEnv* env, jobject, jbyteArray data) {
    std::vector<uint8_t> raw;
    if (!fromByteArray(env, data, raw)) return 0;
    const uint32_t c = apx::crc32(raw.empty() ? nullptr : raw.data(), raw.size());
    return static_cast<jint>(c);
}

// ========================== core.UsbBulkChannel ============================
// bulk 端点的原生读写（FunctionFS）。
//
// **读与写必须用两个 fd**：FFS 的端点方向在描述符里就固定（IN / OUT），单个 epN
// 文件只能单向使用；而上层 BulkTransport 要求同一通道既能收 video（读）又能发
// touch（写）。因此：
//   rx = FFS 的 OUT 端点 → PC 下行（video / ctrl）
//   tx = FFS 的 IN  端点 → 手机上行（touch / telemetry）
//
// 默认路径 /dev/usb-ffs/apx/ep1（rx）与 ep2（tx），与 FfsChannel 写入的描述符一致；
// 可用 nativeSetPaths 覆盖。所有 streamId 复用同一对物理端点，按 §3 帧头里的
// streamId 字段分流（端点布局见 docs/PROTOCOL.md §3）。
#if defined(APX_HAVE_POSIX_IO)

namespace {

/// 与 FfsChannel 描述符中的端点顺序对应：ep1 = OUT（下行），ep2 = IN（上行）
constexpr const char* kFFsRxDevice = "/dev/usb-ffs/apx/ep1";
constexpr const char* kFFsTxDevice = "/dev/usb-ffs/apx/ep2";

std::string defaultRxPathFor(jint /*streamId*/) { return kFFsRxDevice; }
std::string defaultTxPathFor(jint /*streamId*/) { return kFFsTxDevice; }

}  // namespace

/// 覆盖读/写端点路径；某个方向传 nullptr 表示保持默认。需在 open 之前调用。
JNIEXPORT jint JNICALL
Java_com_allperiph_core_UsbBulkChannel_nativeSetPaths(JNIEnv* env, jobject, jint streamId,
                                                      jstring rxPath, jstring txPath) {
    BulkChannel& ch = channelOf(streamId);
    if (rxPath != nullptr) {
        const char* p = env->GetStringUTFChars(rxPath, nullptr);
        if (p == nullptr) return -1;
        ch.rxPath = p;
        env->ReleaseStringUTFChars(rxPath, p);
    }
    if (txPath != nullptr) {
        const char* p = env->GetStringUTFChars(txPath, nullptr);
        if (p == nullptr) return -1;
        ch.txPath = p;
        env->ReleaseStringUTFChars(txPath, p);
    }
    return 0;
}

// 返回 0 成功，其它为 errno
JNIEXPORT jint JNICALL
Java_com_allperiph_core_UsbBulkChannel_nativeOpen(JNIEnv*, jobject, jint streamId) {
    BulkChannel& ch = channelOf(streamId);
    if (ch.isOpen()) return 0;
    const std::string rx = ch.rxPath.empty() ? defaultRxPathFor(streamId) : ch.rxPath;
    const std::string tx = ch.txPath.empty() ? defaultTxPathFor(streamId) : ch.txPath;

    const int fdRx = ::open(rx.c_str(), O_RDWR);
    if (fdRx < 0) {
        const int e = errno;
        APX_LOGW("bulk open rx %s failed errno=%d", rx.c_str(), e);
        return e;
    }
    const int fdTx = ::open(tx.c_str(), O_RDWR);
    if (fdTx < 0) {
        const int e = errno;
        APX_LOGW("bulk open tx %s failed errno=%d", tx.c_str(), e);
        ::close(fdRx);
        return e;
    }
    ch.fdRx = fdRx;
    ch.fdTx = fdTx;
    APX_LOGI("bulk opened rx=%s tx=%s", rx.c_str(), tx.c_str());
    return 0;
}

// 返回读取字节数；0 = EOF；负数 = -errno
JNIEXPORT jint JNICALL
Java_com_allperiph_core_UsbBulkChannel_nativeRead(JNIEnv* env, jobject, jint streamId,
                                                  jbyteArray dst, jint maxLen) {
    BulkChannel& ch = channelOf(streamId);
    if (ch.fdRx < 0) return -static_cast<jint>(ENOTCONN);
    if (dst == nullptr || maxLen <= 0) return 0;
    const jsize cap = env->GetArrayLength(dst);
    const jint want = (maxLen < cap) ? maxLen : cap;
    std::vector<uint8_t> tmp(static_cast<size_t>(want));
    ssize_t n;
    do {
        n = ::read(ch.fdRx, tmp.data(), static_cast<size_t>(want));
    } while (n < 0 && errno == EINTR);
    if (n < 0) return -static_cast<jint>(errno);
    if (n > 0) {
        std::vector<jbyte> jb(static_cast<size_t>(n));
        for (ssize_t i = 0; i < n; ++i) {
            jb[static_cast<size_t>(i)] = static_cast<jbyte>(tmp[static_cast<size_t>(i)]);
        }
        env->SetByteArrayRegion(dst, 0, static_cast<jsize>(n), jb.data());
    }
    return static_cast<jint>(n);
}

// 返回写入字节数；负数 = -errno
JNIEXPORT jint JNICALL
Java_com_allperiph_core_UsbBulkChannel_nativeWrite(JNIEnv* env, jobject, jint streamId,
                                                   jbyteArray src, jint off, jint len) {
    BulkChannel& ch = channelOf(streamId);
    if (ch.fdTx < 0) return -static_cast<jint>(ENOTCONN);
    if (src == nullptr || len <= 0) return 0;
    std::vector<uint8_t> raw;
    if (!fromByteArray(env, src, raw)) return -static_cast<jint>(EINVAL);
    if (off < 0 || static_cast<size_t>(off) >= raw.size()) return -static_cast<jint>(EINVAL);
    size_t n = static_cast<size_t>(len);
    const size_t avail = raw.size() - static_cast<size_t>(off);
    if (n > avail) n = avail;
    // Touch 上行契约是"写入即发"，短写会把一帧截断，所以循环写到完为止。
    size_t done = 0;
    while (done < n) {
        const ssize_t w = ::write(ch.fdTx, raw.data() + off + done, n - done);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -static_cast<jint>(errno);
        }
        done += static_cast<size_t>(w);
    }
    return static_cast<jint>(done);
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_UsbBulkChannel_nativeClose(JNIEnv*, jobject, jint streamId) {
    BulkChannel& ch = channelOf(streamId);
    if (ch.fdRx >= 0) {
        ::close(ch.fdRx);
        ch.fdRx = -1;
    }
    if (ch.fdTx >= 0) {
        ::close(ch.fdTx);
        ch.fdTx = -1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_allperiph_core_UsbBulkChannel_nativeIsOpen(JNIEnv*, jobject, jint streamId) {
    return channelOf(streamId).isOpen() ? JNI_TRUE : JNI_FALSE;
}

// ========================= core.HidFeature ================================
// 主动把 Feature Report 喂给内核的 f_hid。
//
// 为什么必须由用户态提供：主机的 GET_REPORT 是**控制传输**，不走 INTERRUPT OUT，
// 因此永远不出现在 /dev/hidg0 的 read() 里（实测挂载数分钟内零 OUT 事件）。
// Linux 6.12 起 f_hid 提供 GADGET_HID_WRITE_GET_REPORT，允许用户态登记应答内容。
//
// 不登记的后果：Windows SensorsHIDClassDriver 启动阶段读不到 Report State /
// Report Interval，直接以 Code 10（STATUS_INVALID_PARAMETER）失败 ——
// 表现为设备管理器里每个传感器 TLC 都"无法启动"。
//
// NDK sysroot 未收录 linux/usb/g_hid.h（较新且不常用），下面按 v6.12 uapi 原样定义。
namespace {

/// 与内核 uapi 的 MAX_REPORT_LENGTH 一致
// v1.8 回退：data[64] 必须与内核 struct usb_hidg_report 完全一致——
// _IOW 的 ioctl 号包含 sizeof，改成 320 会导致全部登记 ENOTTY(-25)（真机已验证）。
// 257B 认证 blob 无法经此登记，已从描述符移除 PTPHQA feature（见 hid_descriptor.cpp）。
constexpr size_t kHidgReportMaxLen = 64;

/// 与内核 struct usb_hidg_report 逐字段一致（含尾部 padding，保证 32/64 位下大小相同）
struct HidgReport {
    uint8_t  report_id;
    uint8_t  userspace_req;
    uint16_t length;
    uint8_t  data[kHidgReportMaxLen];
    uint8_t  padding[4];
};

}  // namespace

// _IOW('g', 0x42, struct usb_hidg_report)；_IOW 会取 sizeof(HidgReport) 参与编码
#ifndef GADGET_HID_WRITE_GET_REPORT
#define GADGET_HID_WRITE_GET_REPORT _IOW('g', 0x42, HidgReport)
#endif

/// 返回 0 成功；负数为 -errno
JNIEXPORT jint JNICALL
Java_com_allperiph_core_HidFeature_nativeWriteGetReport(JNIEnv* env, jobject, jstring path,
                                                        jint reportId, jbyteArray data) {
    if (path == nullptr || data == nullptr) return -EINVAL;
    const jsize len = env->GetArrayLength(data);
    if (len <= 0 || static_cast<size_t>(len) > kHidgReportMaxLen) return -EINVAL;

    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return -EINVAL;
    const int fd = ::open(p, O_RDWR);
    const int openErr = (fd < 0) ? errno : 0;
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) return -openErr;

    HidgReport rep{};
    rep.report_id = static_cast<uint8_t>(reportId);
    // 0 = 该应答同时用于「当前挂起的请求」和「此后的所有请求」，
    // 即登记一次长期有效，不必每来一个 GET_REPORT 都重发。
    rep.userspace_req = 0;
    rep.length = static_cast<uint16_t>(len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(rep.data));

    const int rc = ::ioctl(fd, GADGET_HID_WRITE_GET_REPORT, &rep);
    const int err = (rc < 0) ? errno : 0;
    ::close(fd);
    return (err == 0) ? 0 : -err;
}

// ========================= core.AlsaPcm ===================================
// UAC2 gadget 的 PCM 读写（裸 ALSA ioctl，不依赖 alsa-lib）。
//
// 为什么裸写：NDK 不提供 libasound，Android 上也没有；但内核 uapi 的
// <sound/asound.h> 足够完成 open / hw_params / sw_params / prepare / start
// 以及 read/write。
//
// 方向语义（UAC2 gadget 侧；实测 /proc/asound/pcm 报告
// "01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1"）：
//   pcmC?D0p (playback) —— 应用写入 → 经 USB 发给主机（PC 侧表现为麦克风输入）
//   pcmC?D0c (capture)  —— 主机发来 → 应用读取（PC 侧表现为扬声器输出）

#if defined(APX_HAVE_ALSA_UAPI)

namespace {

constexpr unsigned kAlsaPeriodFrames = 1024;
constexpr unsigned kAlsaBufferFrames = 4096;

void alsaSetMask(struct snd_mask* m, unsigned int bit) {
    for (unsigned i = 0; i < (SNDRV_MASK_MAX / 32); ++i) m->bits[i] = 0;
    m->bits[bit / 32] = 1u << (bit % 32);
}

void alsaSetInterval(struct snd_interval* it, unsigned int v) {
    it->min = v;
    it->max = v;
    it->openmin = 0;
    it->openmax = 0;
    it->integer = 1;
    it->empty = 0;
}

/// 把 hw_params 整体初始化为「任意」（等价 alsa-lib 的 snd_pcm_hw_params_any_init）。
///
/// **必须这么做，不能只 memset(0)**：memset 会把所有未显式赋值的 interval 变成
/// min=max=0，而 HW_PARAMS 会校验**每一个**参数 —— 例如 SAMPLE_BITS、FRAME_BITS
/// 被约束成「只能取 0」，内核判定不可满足，直接返回 EINVAL（实测 errno=22）。
/// 置为「任意」后，未涉及的参数不再构成约束，只校验我们真正设置的那几项。
void alsaParamsAny(struct snd_pcm_hw_params* p) {
    std::memset(p, 0, sizeof(*p));
    for (unsigned i = 0; i < (SNDRV_MASK_MAX / 32); ++i) {
        p->masks[SNDRV_PCM_HW_PARAM_ACCESS - SNDRV_PCM_HW_PARAM_FIRST_MASK].bits[i] = ~0u;
        p->masks[SNDRV_PCM_HW_PARAM_FORMAT - SNDRV_PCM_HW_PARAM_FIRST_MASK].bits[i] = ~0u;
        p->masks[SNDRV_PCM_HW_PARAM_SUBFORMAT - SNDRV_PCM_HW_PARAM_FIRST_MASK].bits[i] = ~0u;
    }
    for (int i = 0; i <= (SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL);
         ++i) {
        p->intervals[i].min = 0;
        p->intervals[i].max = UINT_MAX;
        p->intervals[i].openmin = 0;
        p->intervals[i].openmax = 0;
        p->intervals[i].integer = 0;
        p->intervals[i].empty = 0;
    }
}

/// 打开并配置一个 PCM 流。返回 fd（>=0）或负的 -errno。
int alsaOpenStream(const char* path, bool playback, unsigned rate, unsigned channels) {
    const int fd = ::open(path, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        APX_LOGW("alsa: open %s errno=%d", path, errno);
        return -errno;
    }

    struct snd_pcm_hw_params hw;
    // 先整体置「任意」再覆写目标值。理由见 alsaParamsAny 注释：
    // 只 memset(0) 会把 SAMPLE_BITS/FRAME_BITS 等未涉及的参数约束成 0，
    // HW_PARAMS 逐个校验时判定不可满足 → EINVAL。
    alsaParamsAny(&hw);
    alsaSetMask(&hw.masks[SNDRV_PCM_HW_PARAM_ACCESS - SNDRV_PCM_HW_PARAM_FIRST_MASK],
                SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    alsaSetMask(&hw.masks[SNDRV_PCM_HW_PARAM_FORMAT - SNDRV_PCM_HW_PARAM_FIRST_MASK],
                SNDRV_PCM_FORMAT_S16_LE);
    alsaSetMask(&hw.masks[SNDRV_PCM_HW_PARAM_SUBFORMAT - SNDRV_PCM_HW_PARAM_FIRST_MASK],
                SNDRV_PCM_SUBFORMAT_STD);
    alsaSetInterval(
        &hw.intervals[SNDRV_PCM_HW_PARAM_CHANNELS - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL], channels);
    alsaSetInterval(
        &hw.intervals[SNDRV_PCM_HW_PARAM_RATE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL], rate);
    alsaSetInterval(
        &hw.intervals[SNDRV_PCM_HW_PARAM_PERIOD_SIZE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL],
        kAlsaPeriodFrames);
    alsaSetInterval(
        &hw.intervals[SNDRV_PCM_HW_PARAM_BUFFER_SIZE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL],
        kAlsaBufferFrames);

    if (::ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &hw) < 0) {
        const int e = errno;
        APX_LOGW("alsa: HW_PARAMS %s errno=%d", path, e);
        ::close(fd);
        return -e;
    }
    APX_LOGI("alsa: %s hw_params ok period=%u buffer=%u rate=%u ch=%u", path, kAlsaPeriodFrames,
             kAlsaBufferFrames, rate, channels);

    struct snd_pcm_sw_params sw;
    std::memset(&sw, 0, sizeof(sw));
    sw.tstamp_mode = SNDRV_PCM_TSTAMP_NONE;
    sw.avail_min = kAlsaPeriodFrames;
    // 两个方向都用 1：playback 首次 write 即启动（避免空缓冲下 xrun），
    // capture 立即开始采集（用 period_size 会先攒够一整个周期才启动，对实时性不利）。
    sw.start_threshold = 1;
    sw.stop_threshold = kAlsaBufferFrames;
    sw.silence_threshold = kAlsaBufferFrames;
    sw.silence_size = 0;
    ::ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw);  // 失败不致命

    if (::ioctl(fd, SNDRV_PCM_IOCTL_PREPARE, 0) < 0) {
        const int e = errno;
        APX_LOGW("alsa: PREPARE %s errno=%d", path, e);
        ::close(fd);
        return -e;
    }
    // START 失败**不致命**：playback 流在空缓冲上显式 START 会返回 EPIPE(-32)，
    // 这是正常行为 —— 它在首次 write 达到 start_threshold 时自动启动。
    // 之前把它当致命错误，导致 playback 流白白打不开（实测 rc=-32）。
    if (::ioctl(fd, SNDRV_PCM_IOCTL_START, 0) < 0) {
        APX_LOGW("alsa: START %s errno=%d（非致命，read/write 会自动启动）", path, errno);
    }
    return fd;
}

}  // namespace

JNIEXPORT jint JNICALL
Java_com_allperiph_core_AlsaPcm_nativeOpen(JNIEnv* env, jobject, jstring path, jboolean playback,
                                           jint rate, jint channels) {
    if (path == nullptr) return -EINVAL;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return -EINVAL;
    const int fd = alsaOpenStream(p, playback == JNI_TRUE, static_cast<unsigned>(rate),
                                  static_cast<unsigned>(channels));
    env->ReleaseStringUTFChars(path, p);
    return static_cast<jint>(fd);
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_AlsaPcm_nativeWrite(JNIEnv* env, jobject, jint fd, jbyteArray src) {
    if (fd < 0 || src == nullptr) return -EINVAL;
    const jsize n = env->GetArrayLength(src);
    if (n <= 0) return 0;
    std::vector<uint8_t> buf(static_cast<size_t>(n));
    env->GetByteArrayRegion(src, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    ssize_t off = 0;
    while (off < n) {
        const ssize_t w = ::write(fd, buf.data() + off, static_cast<size_t>(n) - off);
        if (w < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            return -errno;
        }
        off += w;
    }
    return static_cast<jint>(off);
}

JNIEXPORT jint JNICALL
Java_com_allperiph_core_AlsaPcm_nativeRead(JNIEnv* env, jobject, jint fd, jbyteArray dst) {
    if (fd < 0 || dst == nullptr) return -EINVAL;
    const jsize cap = env->GetArrayLength(dst);
    if (cap <= 0) return 0;
    std::vector<uint8_t> buf(static_cast<size_t>(cap));
    ssize_t n;
    do {
        n = ::read(fd, buf.data(), static_cast<size_t>(cap));
    } while (n < 0 && (errno == EAGAIN || errno == EINTR));
    if (n < 0) return -errno;
    if (n > 0) {
        env->SetByteArrayRegion(dst, 0, static_cast<jsize>(n),
                                reinterpret_cast<jbyte*>(buf.data()));
    }
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL
Java_com_allperiph_core_AlsaPcm_nativeClose(JNIEnv*, jobject, jint fd) {
    if (fd >= 0) {
        ::ioctl(fd, SNDRV_PCM_IOCTL_DROP, 0);
        ::close(fd);
    }
}

#endif  // APX_HAVE_ALSA_UAPI

// ========================== core.FfsChannel ===============================
// FunctionFS：副屏的 bulk 数据面（video 下行 / touch 上行）。
//
// 与 f_hid / f_acm 的本质差异：FFS 在 configfs 里**不自带** interface 与 endpoint
// 声明 —— 接口/端点描述符必须由**用户态写 ep0** 提供。内核在 `ffs_func_bind()` 里
// 会等待描述符就绪（`wait_event_interruptible`），因此 `write(ep0)` 会**阻塞到
// UDC 绑定完成**才返回。必须放独立线程里调用，绝不能挡在挂载流程中间。
//
// 端点分配（2 个 bulk 端点，各 streamId 复用，按 §3 帧头的 streamId 字段分流）：
//   ep1 = OUT（PC → 手机）：video / ctrl 下行
//   ep2 = IN （手机 → PC）：touch / telemetry 上行
//
// 坑：bionic 的 <linux/usb/functionfs.h> 把 `usb_functionfs_descs_head_v2` **裁剪成
// 只有 magic/length/flags 三个字段**，而内核是按 flags 决定后面跟几个 count 字段的
// （见 f_fs.c 的 ffs_parse_descriptors）。所以这里按内核的解析顺序自己拼装，
// **不能直接 sizeof 那个被裁剪的 struct**。

#if defined(APX_HAVE_FUNCTIONFS_UAPI)

namespace {

/// bulk 端点最大包长：full-speed 64，high-speed 512
constexpr uint16_t kFfsBulkMaxPacketFs = 64;
constexpr uint16_t kFfsBulkMaxPacketHs = 512;

/// 端点地址：0x01 = OUT（主机→设备），0x82 = IN（设备→主机）
constexpr uint8_t kFfsEpOutAddr = 0x01;
constexpr uint8_t kFfsEpInAddr = 0x82;

/// 描述符头部（内核 usb_functionfs_descs_head_v2 的完整形态）
struct FfsDescsHead {
    uint32_t magic;
    uint32_t length;   ///< 整个缓冲（含头与 count 字段）的字节数
    uint32_t flags;
    uint32_t fsCount;  ///< FS 描述符字节数
    uint32_t hsCount;  ///< HS 描述符字节数
    uint32_t ssCount;  ///< SS 描述符字节数（本实现不提供，填 0）
} __attribute__((packed));

/// 一个 interface + 两个 bulk 端点；FS / HS 只有 wMaxPacketSize 不同
struct FfsIfBlock {
    struct usb_interface_descriptor ifd;
    struct usb_endpoint_descriptor_no_audio epOut;
    struct usb_endpoint_descriptor_no_audio epIn;
} __attribute__((packed));

void fillFfsIfBlock(FfsIfBlock* b, uint16_t maxPacket) {
    std::memset(b, 0, sizeof(*b));
    b->ifd.bLength = USB_DT_INTERFACE_SIZE;
    b->ifd.bDescriptorType = USB_DT_INTERFACE;
    b->ifd.bInterfaceNumber = 0;
    b->ifd.bAlternateSetting = 0;
    b->ifd.bNumEndpoints = 2;
    b->ifd.bInterfaceClass = USB_CLASS_VENDOR_SPEC;
    b->ifd.iInterface = 0;

    b->epOut.bLength = USB_DT_ENDPOINT_SIZE;
    b->epOut.bDescriptorType = USB_DT_ENDPOINT;
    b->epOut.bEndpointAddress = kFfsEpOutAddr;
    b->epOut.bmAttributes = USB_ENDPOINT_XFER_BULK;
    b->epOut.wMaxPacketSize = maxPacket;
    b->epOut.bInterval = 0;

    b->epIn.bLength = USB_DT_ENDPOINT_SIZE;
    b->epIn.bDescriptorType = USB_DT_ENDPOINT;
    b->epIn.bEndpointAddress = kFfsEpInAddr;
    b->epIn.bmAttributes = USB_ENDPOINT_XFER_BULK;
    b->epIn.wMaxPacketSize = maxPacket;
    b->epIn.bInterval = 0;
}

/// 装配可直接 write(ep0) 的完整描述符缓冲
std::vector<uint8_t> buildFfsDescriptors() {
    FfsIfBlock fs{};
    FfsIfBlock hs{};
    fillFfsIfBlock(&fs, kFfsBulkMaxPacketFs);
    fillFfsIfBlock(&hs, kFfsBulkMaxPacketHs);

    FfsDescsHead head{};
    head.magic = FUNCTIONFS_DESCRIPTORS_MAGIC_V2;
    head.flags = FUNCTIONFS_HAS_FS_DESC | FUNCTIONFS_HAS_HS_DESC;
    head.fsCount = sizeof(fs);
    head.hsCount = sizeof(hs);
    head.ssCount = 0;
    head.length = sizeof(head) + sizeof(fs) + sizeof(hs);

    std::vector<uint8_t> buf(head.length);
    size_t off = 0;
    std::memcpy(buf.data() + off, &head, sizeof(head));
    off += sizeof(head);
    std::memcpy(buf.data() + off, &fs, sizeof(fs));
    off += sizeof(fs);
    std::memcpy(buf.data() + off, &hs, sizeof(hs));
    off += sizeof(hs);
    return buf;
}

/// ep0 必须**长期保持打开**：内核限定同一时刻只有一个 writer；且 UDC 重绑后
/// （`ffs_func_unbind` 会重置描述符状态）需要再次写入，复用同一 fd 最稳。
/// 放文件级缓存。
int gFfsEp0Fd = -1;

}  // namespace

/// 写 FunctionFS 描述符。**会阻塞到 UDC 绑定完成**，调用方必须在独立线程里执行。
/// @return 0 成功；负数为 -errno
JNIEXPORT jint JNICALL
Java_com_allperiph_core_FfsChannel_nativeWriteDescriptors(JNIEnv* env, jclass, jstring ep0Path) {
    if (ep0Path == nullptr) return -EINVAL;
    const char* p = env->GetStringUTFChars(ep0Path, nullptr);
    if (p == nullptr) return -EINVAL;
    const std::string path(p);
    env->ReleaseStringUTFChars(ep0Path, p);

    if (gFfsEp0Fd < 0) {
        gFfsEp0Fd = ::open(path.c_str(), O_RDWR);
        if (gFfsEp0Fd < 0) {
            const int e = errno;
            APX_LOGW("ffs: open ep0 %s failed errno=%d", path.c_str(), e);
            return -e;
        }
    }

    const std::vector<uint8_t> descs = buildFfsDescriptors();
    size_t off = 0;
    while (off < descs.size()) {
        const ssize_t w = ::write(gFfsEp0Fd, descs.data() + off, descs.size() - off);
        if (w < 0) {
            const int e = errno;
            if (e == EINTR) continue;
            APX_LOGW("ffs: write ep0 failed errno=%d", e);
            // fd 可能因 functionfs 被重新挂载而失效 —— 关掉，下次调用重开
            ::close(gFfsEp0Fd);
            gFfsEp0Fd = -1;
            return -e;
        }
        off += static_cast<size_t>(w);
    }
    APX_LOGI("ffs: descriptors written (%zu bytes)", descs.size());
    // ep0 **保持打开**：内核后续会通过它投递 FUNCTIONFS_SETUP / BIND / ENABLE 等
    // 控制事件，关掉会导致控制面请求无人应答。纯数据面场景不主动读，但也不 close。
    return 0;
}

/// 关闭缓存的 ep0 fd。
///
/// **卸载时必须调用**：只要还有 fd 打开，functionfs 就 umount 不掉（EBUSY）；
/// 而 umount 不掉会让 ffs 实例一直存活，反复挂载后内核累积 FFS 上下文 ——
/// 实测 dmesg 出现 `Can't create any more FFS log contexts`，进而拖垮整个
/// gadget 的 UDC 绑定（`udc ...: failed to start apx: -19`）。
JNIEXPORT jint JNICALL
Java_com_allperiph_core_FfsChannel_nativeClose(JNIEnv*, jclass) {
    if (gFfsEp0Fd >= 0) {
        ::close(gFfsEp0Fd);
        gFfsEp0Fd = -1;
    }
    return 0;
}

#endif  // APX_HAVE_FUNCTIONFS_UAPI

#endif  // APX_HAVE_POSIX_IO

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

}  // extern "C"

// ===================== camera.UvcOutput（f_uvc V4L2 输出） =====================
//
// 数据流：Camera2 → JPEG 帧 → write() 到 f_uvc 创建的 /dev/videoN → PC 经 UVC 协议读取。
// 与副屏（FunctionFS bulk）不同，这里**不做任何协议封装**：整帧 MJPEG 直接交给内核，
// 由 f_uvc 按 UVC Payload 头切分上行，PC 侧看到的就是标准 USB 摄像头（免驱）。
#if defined(APX_HAVE_POSIX_IO) && defined(APX_HAVE_V4L2_UAPI)

namespace {

/// CameraModule 是单例（AgentController 只注册一个），全局 fd 即可
int gUvcFd = -1;

/// 与 camera/CameraModule.kt 的 720p 采集尺寸保持一致
const int kUvcWidth = 1280;
const int kUvcHeight = 720;

/// 小写化（不引入 <algorithm>，避免 NDK 版本差异）
std::string uvcToLower(const char* s) {
    std::string out;
    for (const char* p = s; *p != '\0'; ++p) {
        const char c = *p;
        out.push_back((c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c);
    }
    return out;
}

/**
 * 探测 f_uvc 创建的 V4L2 输出节点：driver 或 card 名含 "uvc"（大小写不敏感）。
 *
 * 不强制检查 V4L2_CAP_VIDEO_OUTPUT：gadget 侧的 capability 标记在内核版本间不一致
 * （有的写在 capabilities、有的只在 device_caps），按名字匹配更稳。
 */
bool isUvcOutputNode(const char* path) {
    const int fd = ::open(path, O_RDWR | O_NONBLOCK);
    if (fd < 0) return false;
    struct v4l2_capability cap{};
    const bool ok = ::ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0;
    ::close(fd);
    if (!ok) return false;
    const std::string drv = uvcToLower(reinterpret_cast<const char*>(cap.driver));
    const std::string card = uvcToLower(reinterpret_cast<const char*>(cap.card));
    return drv.find("uvc") != std::string::npos || card.find("uvc") != std::string::npos;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_allperiph_camera_UvcOutput_nativeFindDevice(JNIEnv* env, jobject) {
    for (int i = 0; i < 16; ++i) {
        const std::string path = "/dev/video" + std::to_string(i);
        if (isUvcOutputNode(path.c_str())) {
            APX_LOGI("uvc: 找到输出节点 %s", path.c_str());
            return env->NewStringUTF(path.c_str());
        }
    }
    APX_LOGW("uvc: 未找到 V4L2 输出节点（UVC feature 未挂载？）");
    return nullptr;
}

JNIEXPORT jint JNICALL
Java_com_allperiph_camera_UvcOutput_nativeOpen(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return -EINVAL;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return -EINVAL;
    const std::string dev(p);
    env->ReleaseStringUTFChars(path, p);

    if (gUvcFd >= 0) ::close(gUvcFd);
    gUvcFd = ::open(dev.c_str(), O_WRONLY | O_NONBLOCK);
    if (gUvcFd < 0) {
        const int e = errno;
        APX_LOGW("uvc: open %s 失败 errno=%d", dev.c_str(), e);
        return -e;
    }

    // 设置输出格式（MJPEG）。部分 gadget 节点接受默认格式，
    // S_FMT 失败只告警不致命 —— 继续按默认格式写帧。
    struct v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = static_cast<__u32>(kUvcWidth);
    fmt.fmt.pix.height = static_cast<__u32>(kUvcHeight);
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_MJPEG;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    fmt.fmt.pix.sizeimage = static_cast<__u32>(kUvcWidth * kUvcHeight / 2);
    if (::ioctl(gUvcFd, VIDIOC_S_FMT, &fmt) != 0) {
        APX_LOGW("uvc: VIDIOC_S_FMT 失败 errno=%d（按默认格式继续）", errno);
    } else {
        APX_LOGI("uvc: 输出格式 %ux%u MJPEG", fmt.fmt.pix.width, fmt.fmt.pix.height);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_allperiph_camera_UvcOutput_nativeWriteFrame(JNIEnv* env, jobject, jbyteArray data) {
    if (gUvcFd < 0) return -ENOTCONN;
    if (data == nullptr) return -EINVAL;
    const jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    // write 可能只写一部分，循环写完整帧
    size_t off = 0;
    while (off < buf.size()) {
        const ssize_t n = ::write(gUvcFd, buf.data() + off, buf.size() - off);
        if (n < 0) {
            const int e = errno;
            if (e == EINTR) continue;
            APX_LOGW("uvc: write 失败 errno=%d", e);
            return -e;
        }
        if (n == 0) return -EIO;
        off += static_cast<size_t>(n);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_allperiph_camera_UvcOutput_nativeClose(JNIEnv*, jobject) {
    if (gUvcFd >= 0) {
        ::close(gUvcFd);
        gUvcFd = -1;
        APX_LOGI("uvc: 输出节点已关闭");
    }
}

}  // extern "C"

#endif  // APX_HAVE_POSIX_IO && APX_HAVE_V4L2_UAPI
