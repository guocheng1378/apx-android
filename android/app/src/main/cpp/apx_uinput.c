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

/* 虚拟鼠标的 JNI 类与手柄不同（UinputMouse vs UinputGamepad），单独一个类名宏，
 * 由构建脚本经 APX_JNI_CLASS_MOUSE 传入 —— 同一份 .c 服务两个 Kotlin 类，符号不冲突。 */
#ifndef APX_JNI_CLASS_MOUSE
#define APX_JNI_CLASS_MOUSE com_allperiph_shared_input_UinputMouse
#endif
#define APX_JNI_M(name)     APX_JNI2(APX_JNI_CLASS_MOUSE, name)

/* 虚拟键盘（UinputKeyboard）同款：独立类名宏 APX_JNI_CLASS_KB。 */
#ifndef APX_JNI_CLASS_KB
#define APX_JNI_CLASS_KB com_allperiph_shared_input_UinputKeyboard
#endif
#define APX_JNI_K(name)     APX_JNI2(APX_JNI_CLASS_KB, name)

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

/* ————————————————————————————————————————————————————————————————————————————
 * 虚拟鼠标（与虚拟手柄同款 uinput 老式接口，但独立 fd）：
 *   - 只声明 EV_KEY（左/右/中键）+ EV_REL（X/Y/WHEEL/HWHEEL），不发 ABS；
 *   - 设备名 "APX Virtual Mouse" —— EvdevPointerReader 扫描时按名字排除含
 *     apx/allperiph 的设备，防止把自己当成真实鼠标产生自激；
 *   - REL 移动是相对位移，系统原生光标真动（与插一只 USB 鼠标同一条通路）。
 * ———————————————————————————————————————————————————————————————————————————— */

static int g_mfd = -1;

static void apx_memit(int type, int code, int value) {
    if (g_mfd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    /* 写失败通常是设备被销毁；静默忽略，由 isMouseReady() 反映真实状态 */
    if (write(g_mfd, &ev, sizeof(ev)) < 0) {
        LOGW("虚拟鼠标 uinput 写入失败：%s", strerror(errno));
    }
}

static void apx_msyn(void) { apx_memit(EV_SYN, SYN_REPORT, 0); }

/** 创建虚拟鼠标。可重复调用，已创建时直接返回 true。 */
JNIEXPORT jboolean JNICALL APX_JNI_M(openMouse)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_mfd >= 0) return JNI_TRUE;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        LOGW("打开 /dev/uinput 失败：%s（无权限时虚拟鼠标不可用，移动仍走蓝点浮层）", strerror(errno));
        return JNI_FALSE;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        LOGW("UI_SET_EVBIT 失败：%s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    /* 按键：左/右/中键（声明键位后 Android 才会把它归为可点击的指针设备） */
    static const int btns[] = { BTN_LEFT, BTN_RIGHT, BTN_MIDDLE };
    for (unsigned i = 0; i < sizeof(btns) / sizeof(btns[0]); i++) {
        ioctl(fd, UI_SET_KEYBIT, btns[i]);
    }

    /* 相对轴：X/Y 移动 + 垂直/水平滚轮 */
    static const int rels[] = { REL_X, REL_Y, REL_WHEEL, REL_HWHEEL };
    for (unsigned i = 0; i < sizeof(rels) / sizeof(rels[0]); i++) {
        if (ioctl(fd, UI_SET_RELBIT, rels[i]) < 0) {
            LOGW("UI_SET_RELBIT(%d) 失败：%s", rels[i], strerror(errno));
        }
    }

    apx_uinput_dev_t udev;
    memset(&udev, 0, sizeof(udev));
    snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "APX Virtual Mouse");
    udev.id.bustype = BUS_USB;
    udev.id.vendor = 0x2b1a;
    udev.id.product = 0x0abd;   /* 与手柄 0x0abc 区分，是两只不同的虚拟设备 */
    udev.id.version = 1;

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

    g_mfd = fd;
    LOGI("虚拟鼠标已创建：APX Virtual Mouse（REL 移动 + 三键 + 滚轮，内核级）");
    return JNI_TRUE;
}

/** 销毁虚拟鼠标（不调用也不会崩，进程退出内核会回收） */
JNIEXPORT void JNICALL APX_JNI_M(closeMouse)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_mfd < 0) return;
    ioctl(g_mfd, UI_DEV_DESTROY);
    close(g_mfd);
    g_mfd = -1;
    LOGI("虚拟鼠标已销毁");
}

JNIEXPORT jboolean JNICALL APX_JNI_M(isMouseReady)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_mfd >= 0 ? JNI_TRUE : JNI_FALSE;
}

/** 相对位移：code 用 Linux REL_* 值（X/Y/WHEEL/HWHEEL），value 为像素增量或滚轮格数 */
JNIEXPORT void JNICALL APX_JNI_M(sendRel)(JNIEnv *env, jobject thiz, jint code, jint value) {
    (void) env; (void) thiz;
    apx_memit(EV_REL, code, value);
    apx_msyn();
}

/** 鼠标按键：btn 用 Linux BTN_* 值，down=true 按下 */
JNIEXPORT void JNICALL APX_JNI_M(sendMouseButton)(JNIEnv *env, jobject thiz, jint btn, jboolean down) {
    (void) env; (void) thiz;
    apx_memit(EV_KEY, btn, down ? 1 : 0);
    apx_msyn();
}

/* ————————————————————————————————————————————————————————————————————————————
 * 虚拟键盘（与虚拟手柄/鼠标同款 uinput 老式接口，独立第三个 fd）：
 *   - 组合键的唯一正解：root `input keyevent` 自带 down+up（修饰状态留不住）、
 *     无障碍注不了任意按键、evdev 又常因 /dev/input 权限不可用 —— uinput 直接新建
 *     一只内核键盘设备，修饰键以独立 down/up 事件进出，内核据此合成 META_* 状态。
 *   - 键位位图整段声明（0..KEY_MAX）：Kotlin 侧按「安卓键码 → Linux 键码」映射后直接发
 *     编号，两边不共享键位清单 —— 整段声明杜绝「C 漏声明一个键、写进去被内核丢弃」的暗坑。
 *   - 设备名 "APX Virtual Keyboard" —— evdev 扫描类工具按名字排除含 apx/allperiph
 *     的设备，防止把自己当成真实键盘产生自激。
 * ———————————————————————————————————————————————————————————————————————————— */

static int g_kfd = -1;

static void apx_kemit(int type, int code, int value) {
    if (g_kfd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    /* 写失败通常是设备被销毁；静默忽略，由 isKeyboardReady() 反映真实状态 */
    if (write(g_kfd, &ev, sizeof(ev)) < 0) {
        LOGW("虚拟键盘 uinput 写入失败：%s", strerror(errno));
    }
}

static void apx_ksyn(void) { apx_kemit(EV_SYN, SYN_REPORT, 0); }

/** 创建虚拟键盘。可重复调用，已创建时直接返回 true。 */
JNIEXPORT jboolean JNICALL APX_JNI_K(openKeyboard)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_kfd >= 0) return JNI_TRUE;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        LOGW("打开 /dev/uinput 失败：%s（无权限时虚拟键盘不可用，按键走原通道）", strerror(errno));
        return JNI_FALSE;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        LOGW("UI_SET_EVBIT 失败：%s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    /* 键位位图整段声明（见上方说明）：只有 EV_KEY 没有 REL/ABS，内核归为纯键盘 */
    for (int code = 0; code <= KEY_MAX; code++) {
        ioctl(fd, UI_SET_KEYBIT, code);
    }

    apx_uinput_dev_t udev;
    memset(&udev, 0, sizeof(udev));
    snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "APX Virtual Keyboard");
    udev.id.bustype = BUS_USB;
    udev.id.vendor = 0x2b1a;
    udev.id.product = 0x0abe;   /* 与手柄 0x0abc / 鼠标 0x0abd 区分，三只虚拟设备 */
    udev.id.version = 1;

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

    g_kfd = fd;
    LOGI("虚拟键盘已创建：APX Virtual Keyboard（修饰键/F 键独立 down-up，内核级组合键）");
    return JNI_TRUE;
}

/** 销毁虚拟键盘（不调用也不会崩，进程退出内核会回收） */
JNIEXPORT void JNICALL APX_JNI_K(closeKeyboard)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (g_kfd < 0) return;
    ioctl(g_kfd, UI_DEV_DESTROY);
    close(g_kfd);
    g_kfd = -1;
    LOGI("虚拟键盘已销毁");
}

JNIEXPORT jboolean JNICALL APX_JNI_K(isKeyboardReady)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_kfd >= 0 ? JNI_TRUE : JNI_FALSE;
}

/** 键盘按键：key 用 Linux KEY_* 值，down=true 按下（修饰状态由内核按 down/up 序列合成） */
JNIEXPORT void JNICALL APX_JNI_K(sendKeyboardKey)(JNIEnv *env, jobject thiz, jint key, jboolean down) {
    (void) env; (void) thiz;
    apx_kemit(EV_KEY, key, down ? 1 : 0);
    apx_ksyn();
}
