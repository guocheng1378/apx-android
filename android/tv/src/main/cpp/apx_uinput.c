/*
 * apx_uinput.c —— **虚拟手柄**（uinput 内核级），供被控端注入手柄按钮与摇杆。
 *
 * ## 为什么必须用 uinput
 *
 * 我们已有的两条通道都**给不出摇杆**：
 *   - evdev 写 `/dev/input/event*` 是"冒充某个已存在的设备"——本机那台键盘设备
 *     （aml_keypad）只有 EV_KEY / EV_REL，**没有 ABS 轴**，写摇杆值内核不认；
 *   - root 的 `input` 命令根本没有摇杆语义。
 *
 * 而 `/dev/uinput` 能**新建一个内核输入设备**：声明我们需要的按键与 ABS 轴，
 * 之后写进去的事件就是"这个手柄产生的" —— 系统会像对待真手柄一样对待它
 * （`SOURCE_GAMEPAD`、摇杆轴、按键全都是真的）。
 *
 * ## 兼容性
 *
 * 走的是**老式 uinput 接口**（`write(uinput_user_dev)` + `UI_DEV_CREATE`），
 * 因为 Android 7 的盒子内核还是 3.10/4.x，没有 `UI_DEV_SETUP`。
 * `uinput_user_dev` 结构体这里**自己定义**（布局与内核 uapi 一致），
 * 免得受不同 NDK 版本 uapi 头增删的影响。
 *
 * ## JNI 类名
 *
 * 两个模块（tv / app）各自的 Kotlin 类包名不同，用编译期宏 `APX_JNI_CLASS`
 * 传进来，避免为每个模块复制两份符号名。
 *
 * 无法打开 `/dev/uinput`（无权限）时 [openGamepad] 返回 false，调用方自动退回
 * "按钮走 evdev / root"的老路径，摇杆则不可用。
 */
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <sys/time.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <android/log.h>

#define LOG_TAG "ApxUinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef UINPUT_MAX_NAME_SIZE
#define UINPUT_MAX_NAME_SIZE 80
#endif

/* JNI 符号名：Java_<APX_JNI_CLASS>_<method>。多一层宏让 APX_JNI_CLASS 先展开。 */
#define APX_JNI3(cls, name) Java_##cls##_##name
#define APX_JNI2(cls, name) APX_JNI3(cls, name)
#define APX_JNI(name)       APX_JNI2(APX_JNI_CLASS, name)

/* 与内核 struct uinput_user_dev 布局一致（自己定义，不受 uapi 头增删影响） */
typedef struct {
    char name[UINPUT_MAX_NAME_SIZE];
    struct input_id id;
    unsigned int ff_effects_max;
    int absmax[ABS_CNT];
    int absmin[ABS_CNT];
    int absfuzz[ABS_CNT];
    int absflat[ABS_CNT];
} apx_uinput_dev_t;

static int g_fd = -1;

static void apx_emit(int type, int code, int value) {
    if (g_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    /* 写失败通常是设备被销毁；静默忽略，由 isReady() 反映真实状态 */
    if (write(g_fd, &ev, sizeof(ev)) < 0) {
        LOGW("uinput 写入失败：%s", strerror(errno));
    }
}

static void apx_syn(void) { apx_emit(EV_SYN, SYN_REPORT, 0); }

/** 创建虚拟手柄。可重复调用，已创建时直接返回 true。 */
JNIEXPORT jboolean JNICALL APX_JNI(openGamepad)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_fd >= 0) return JNI_TRUE;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        LOGW("打开 /dev/uinput 失败：%s（无权限时摇杆不可用，按钮仍走 evdev/root）", strerror(errno));
        return JNI_FALSE;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        LOGW("UI_SET_EVBIT 失败：%s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    /* 按键：与 Kotlin 侧 GAMEPAD_KEYCODES 的位序一一对应（A B X Y L1 R1 L2 R2 SELECT START C Z MODE THUMBL THUMBR） */
    static const int btns[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
        BTN_SELECT, BTN_START, BTN_C, BTN_Z,
        BTN_MODE, BTN_THUMBL, BTN_THUMBR,
    };
    for (unsigned i = 0; i < sizeof(btns) / sizeof(btns[0]); i++) {
        ioctl(fd, UI_SET_KEYBIT, btns[i]);
    }

    /* 摇杆：左 X/Y、右 RX/RY */
    static const int axes[] = { ABS_X, ABS_Y, ABS_RX, ABS_RY };
    for (unsigned i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
        if (ioctl(fd, UI_SET_ABSBIT, axes[i]) < 0) {
            LOGW("UI_SET_ABSBIT(%d) 失败：%s", axes[i], strerror(errno));
        }
    }

    apx_uinput_dev_t udev;
    memset(&udev, 0, sizeof(udev));
    snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "APX Virtual Gamepad");
    udev.id.bustype = BUS_USB;
    udev.id.vendor = 0x2b1a;
    udev.id.product = 0x0abc;
    udev.id.version = 1;
    for (unsigned i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
        int a = axes[i];
        udev.absmin[a] = -128;
        udev.absmax[a] = 127;
        udev.absfuzz[a] = 0;
        udev.absflat[a] = 6;
    }

    if (write(fd, &udev, sizeof(udev)) != (ssize_t) sizeof(udev)) {
        LOGW("写 uinput_user_dev 失败：%s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGW("UI_DEV_CREATE 失败：%s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    g_fd = fd;
    LOGI("虚拟手柄已创建：APX Virtual Gamepad（按键 + 双摇杆 ABS，内核级）");
    return JNI_TRUE;
}

/** 销毁虚拟手柄（被控端停用时调用；不调用也不会崩，进程退出内核会回收） */
JNIEXPORT void JNICALL APX_JNI(closeGamepad)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_fd < 0) return;
    ioctl(g_fd, UI_DEV_DESTROY);
    close(g_fd);
    g_fd = -1;
    LOGI("虚拟手柄已销毁");
}

JNIEXPORT jboolean JNICALL APX_JNI(isReady)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_fd >= 0 ? JNI_TRUE : JNI_FALSE;
}

/** 手柄按键：btn 用 Linux BTN_* 值，down=true 按下 */
JNIEXPORT void JNICALL APX_JNI(sendButton)(JNIEnv *env, jobject thiz, jint btn, jboolean down) {
    (void) env; (void) thiz;
    apx_emit(EV_KEY, btn, down ? 1 : 0);
    apx_syn();
}

/** 摇杆轴：axis 用 Linux ABS_* 值，value ∈ [-128, 127] */
JNIEXPORT void JNICALL APX_JNI(sendAxis)(JNIEnv *env, jobject thiz, jint axis, jint value) {
    (void) env; (void) thiz;
    apx_emit(EV_ABS, axis, value);
    apx_syn();
}
