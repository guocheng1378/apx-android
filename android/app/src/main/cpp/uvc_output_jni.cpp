// uvc_output_jni.cpp — V4L2 gadget 帧输出 JNI 桥接。
//
// 职责：打开内核 f_uvc 创建的 /dev/videoN，通过 write() 写入 JPEG 帧，
//       PC 端通过 USB UVC 协议读取并识别为标准摄像头（免驱动）。
//
// JNI 呑名规则：Kotlin 侧 UvcOutput 是普通 class（非 object），
//   native 方法在 companion object 中声明，C++ 侧用 Java_com_allperiph_camera_UvcOutput_nativeXxx。

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <linux/videodev2.h>
#include <cstring>
#include <cstdio>
#include <cerrno>

#ifdef APX_HAVE_ANDROID_LOG
#include <android/log.h>
#define LOG_TAG "UvcOutput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGW(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

// ——————————————————————————————————————————————
// 内部上下文
// ——————————————————————————————————————————————

struct UvcContext {
    int fd = -1;
    int width = 1280;
    int height = 720;
};

static UvcContext gCtx;

// ——————————————————————————————————————————————
// 探测 f_uvc 创建的 V4L2 设备节点
// ——————————————————————————————————————————————

/**
 * 遍历 /dev/video0..15，找到 driver 名包含 "uvcvideo" 的节点。
 * f_uvc 内核模块注册的 V4L2 设备 driver 名固定为 "uvcvideo"。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_allperiph_camera_UvcOutput_nativeFindDevice(JNIEnv* env, jobject /* this */) {
    for (int i = 0; i < 16; i++) {
        char path[32];
        snprintf(path, sizeof(path), "/dev/video%d", i);

        int fd = ::open(path, O_RDWR | O_NONBLOCK);
        if (fd < 0) continue;

        struct v4l2_capability cap{};
        if (ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0) {
            if (strstr(reinterpret_cast<const char*>(cap.driver), "uvcvideo")) {
                LOGI("found UVC device: %s (driver=%s)", path, cap.driver);
                ::close(fd);
                return env->NewStringUTF(path);
            }
        }
        ::close(fd);
    }
    LOGW("no UVC video device found in /dev/video0..15");
    return nullptr;
}

// ——————————————————————————————————————————————
// 打开 V4L2 设备
// ——————————————————————————————————————————————

/**
 * 打开指定的 V4L2 设备节点。
 * @param path 设备路径（如 "/dev/video4"）
 * @return 0=成功，负值=errno
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_allperiph_camera_UvcOutput_nativeOpen(JNIEnv* /* env */, jobject /* this */, jstring path) {
    if (gCtx.fd >= 0) {
        LOGW("device already open (fd=%d), closing first", gCtx.fd);
        ::close(gCtx.fd);
        gCtx.fd = -1;
    }

    if (!path) return -EINVAL;

    const char* cpath = nullptr;
    // 直接用 GetStringUTFChars 获取路径
    jboolean isCopy = JNI_FALSE;
    cpath = reinterpret_cast<const char*>(path);
    // 注意：简化实现，实际应 GetStringUTFChars/ReleaseStringUTFChars
    // 但此处路径短且只在打开时用一次，直接传入即可

    // 用 GetStringUTFChars 获取完整路径
    const char* devPath = nullptr;
    // 由于 JNI 调用约定，这里用栈缓冲区安全拷贝
    char pathBuf[64] = {};
    {
        jboolean copy = JNI_FALSE;
        devPath = env->GetStringUTFChars(path, &copy);
        if (!devPath) return -ENOMEM;
        snprintf(pathBuf, sizeof(pathBuf), "%s", devPath);
        env->ReleaseStringUTFChars(path, devPath);
    }

    gCtx.fd = ::open(pathBuf, O_WRONLY);
    if (gCtx.fd < 0) {
        int err = errno;
        LOGE("open %s failed: %s (errno=%d)", pathBuf, strerror(err), err);
        return -err;
    }

    // 查询设备能力（可选，用于日志）
    struct v4l2_capability cap{};
    if (ioctl(gCtx.fd, VIDIOC_QUERYCAP, &cap) == 0) {
        LOGI("opened %s: driver=%s, card=%s, caps=0x%08x",
             pathBuf, cap.driver, cap.card, cap.capabilities);
    }

    LOGI("V4L2 device opened: %s (fd=%d)", pathBuf, gCtx.fd);
    return 0;
}

// ——————————————————————————————————————————————
// 写入一帧 JPEG
// ——————————————————————————————————————————————

/**
 * 将 JPEG 帧数据写入 V4L2 gadget 输出节点。
 * f_uvc 内核模块通过 USB 等时传输将帧发送到 PC。
 *
 * @param jpegData JPEG 编码后的帧数据
 * @return 0=成功，负值=errno
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_allperiph_camera_UvcOutput_nativeWriteFrame(
        JNIEnv* env, jobject /* this */, jbyteArray jpegData) {
    if (gCtx.fd < 0) return -ENODEV;

    jsize len = env->GetArrayLength(jpegData);
    if (len <= 0) return -EINVAL;

    jbyte* data = env->GetByteArrayElements(jpegData, nullptr);
    if (!data) return -ENOMEM;

    // V4L2 输出模式：直接 write() 即可。
    // f_uvc 内核模块在内部处理 USB UVC 协议封装和等时传输。
    ssize_t written = ::write(gCtx.fd, data, len);

    env->ReleaseByteArrayElements(jpegData, data, JNI_ABORT);

    if (written < 0) {
        int err = errno;
        // EAGAIN / EWOULDBLOCK 在非阻塞模式下正常（帧队列满）
        if (err != EAGAIN && err != EWOULDBLOCK) {
            LOGE("write frame failed: %s (errno=%d, len=%d)", strerror(err), err, len);
        }
        return -err;
    }

    if (static_cast<jsize>(written) != len) {
        LOGW("partial write: %zd/%d bytes", written, len);
    }

    return 0;
}

// ——————————————————————————————————————————————
// 关闭设备
// ——————————————————————————————————————————————

extern "C" JNIEXPORT void JNICALL
Java_com_allperiph_camera_UvcOutput_nativeClose(JNIEnv* /* env */, jobject /* this */) {
    if (gCtx.fd >= 0) {
        ::close(gCtx.fd);
        LOGI("V4L2 device closed (was fd=%d)", gCtx.fd);
        gCtx.fd = -1;
    }
}
