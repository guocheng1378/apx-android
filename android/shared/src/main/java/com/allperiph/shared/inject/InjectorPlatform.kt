package com.allperiph.shared.inject

import android.view.KeyEvent

/**
 * 平台特定的注入器配置。
 *
 * 手机端和 TV 端的 [TvInjector] 原先是两份几乎一致的拷贝，差异集中在：
 *  - 光标浮层（[TvOverlay] 在不同包）；
 *  - 通道优先级（TV 端 SELinux Enforcing，root 的 `input` 命令被静默丢弃，故无障碍优先）；
 *  - 线程名前缀（"apx" / "apx-tv"）；
 *  - 额外能力（TV 通知权限自检、TV 屏幕提示 toast）；
 *  - 额外按键分支（手机端的 Tab / PageUp/Down / MoveHome/End / 多媒体键）；
 *  - 主线程切换（手机端浮窗操作必须切回主线程，TV 端不需要）；
 *  - [channelText] / [TvInjector.capabilities] 文案。
 *
 * 通过此接口把这些差异点抽出来，让共享 [TvInjector] 与具体平台解耦。各端在
 * 被控服务初始化时创建自己的实现并传入 [TvInjector.init]。
 *
 * **默认值约定**：两端共用的能力（[channelTextFullKey] 等、[capabilitySpecs]）
 * 默认值取**手机端**文案（以手机端为基线）；只在一端存在的额外能力
 * （[handleExtraKey]、[extraCapabilities]、[channelTextUinputGamepad]、[toast]）
 * 默认值取「空 / 不做事」，由该端按需覆盖。
 */
interface InjectorPlatform {
    /** 光标浮层操作 */
    val overlay: CursorOverlay

    /** 通道优先级：true = 无障碍优先（TV），false = root 优先（手机） */
    val accessibilityFirst: Boolean

    /** 线程名前缀（"apx" 或 "apx-tv"） */
    val threadPrefix: String

    /** 是否需要主线程切换（手机端浮窗走 WindowManager，必须切主线程；TV 端不需要） */
    val needsMainThreadSwitch: Boolean get() = false

    /** 切换到主线程执行（仅当 [needsMainThreadSwitch] 为 true 时被调用） */
    fun runOnMain(action: () -> Unit) { action() }

    /** 额外的控制能力自检项（如 TV 端的通知权限）；状态由各端自算 */
    fun extraCapabilities(): List<Triple<String, Boolean, String>> = emptyList()

    /** 在被控端屏幕上弹提示（TV 用） */
    fun toast(msg: String) {}

    /** 额外的按键处理：返回 true 表示已处理（手机端用于 Tab/PageUp/Down/MoveHome/End/多媒体键） */
    fun handleExtraKey(kc: Int, down: Boolean, injector: TvInjector): Boolean = false

    // —————————————————————— channelText 平台文案 ——————————————————————

    /** root + 虚拟手柄同时可用时的合并文案；返回 null 表示不在 channelText 里展示这一档（TV） */
    fun channelTextUinputGamepad(): String? = null

    fun channelTextFullKey(): String = "root 注入 · 全键可用"

    fun channelTextEvdev(): String = "evdev 内核注入 · 免 root，全键可用"

    fun channelTextAccessibility(): String = "无障碍注入 · 仅点击/滑动/输入框打字，按键不可用"

    fun channelTextNone(): String = "未开启 · 只能看到光标，点不动"

    // —————————————————————— capabilities 平台文案 + 顺序 ——————————————————————
    // 每项 = (状态键, 名称, 排障说明)；状态键由共享 [TvInjector] 映射到实际就绪判断，
    // 这样状态逻辑只留一份在共享侧，文案与展示顺序由各端决定。

    /** 共用的能力自检文案与顺序（默认取手机端） */
    fun capabilitySpecs(): List<Triple<String, String, String>> = listOf(
        Triple(
            "root",
            "root 注入（全键）",
            "未授予 root —— 没有它就无法发任意按键（方向键 / 组合键 / 手柄按钮）",
        ),
        Triple(
            "evdev",
            "evdev 内核注入（免 root）",
            "手机的 /dev/input/event* 通常是 0660 root:input，普通应用打不开（预期会跳过，不影响使用）",
        ),
        Triple(
            "a11y",
            "无障碍注入（点击 / 滑动 / 打字）",
            "到「无障碍 / 辅助功能」里启用本应用",
        ),
        Triple(
            "overlay",
            "悬浮窗（把光标画在屏幕上）",
            "到「显示在其他应用上层」里允许本应用",
        ),
        Triple(
            "ime",
            "输入法（中文打字最稳）",
            "到「输入法 / 键盘」设置里启用「全能外设输入」并切过去",
        ),
        Triple(
            "uinput",
            "虚拟手柄（摇杆）",
            "需要 root（会自动尝试 chmod /dev/uinput）",
        ),
    )
}
