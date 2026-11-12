package com.allperiph.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HidMouse
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.ui.miuix.applyEdgeToEdge
import com.allperiph.wireless.ControlTarget
import top.yukonga.miuix.kmp.basic.Text

/**
 * 「遥控器」—— 仿小米遥控器的**拨盘式**布局（真机反馈"找不到遥控器界面"，之后又要求更像遥控器）。
 *
 * ## 版式（v223.29.55 起整体迁 Compose + Miuix，与主壳同一套设计体系）
 * ```
 *            遥控器          [选择设备] [光标浮层] [关闭]
 *   正在控制：q201（TV）
 *        ╭───────────────╮
 *        │       ▲       │          ← 大正圆方向环（拨盘）
 *        │   ◀  (OK)  ▶  │            中心是主色圆钮 OK
 *        │       ▼       │
 *        ╰───────────────╯
 *          ⭕   ⭕   ⭕              ← 一行三个圆钮
 *          ⭕   ⭕   ⭕                 功能 / 音量 / 媒体 共三行
 *          ⭕   ⭕   ⭕
 *      ┌──────────────────┐
 *      │   单点＝确认      │       ← 底部触摸控制条（约 1/4 屏高）
 *      └──────────────────┘
 * ```
 * 横屏：旋转 90° 的左右分栏（左拨盘 / 右按键列）—— 电源行贴右列顶，下接三行圆钮、
 * 提示与触摸条（issue#168 第 6 条两轮追补的结论，逐条保留）。
 * 方向对应关系不变：竖屏拨盘下方是 功能 / 音量 / 媒体 三行；电源行只在横屏出现
 * （竖屏空间被 1/4 屏高的触摸条挤占后收敛为三行，见旧实现 issue#168 三追记录）。
 *
 * ## 迁移说明
 *  · 视觉与事件全部换成 Compose（[RemotePanel]）；类名 / 清单入口 / 全部发送通道保持不变；
 *  · 旧实现按**中文字面量**给按钮选色（"关机"红 / "重启"橙）是记录在案的遗留隐患 ——
 *    迁移时改为行定义自带语义色（[RemoteKey]），文案资源怎么改都不影响配色；
 *  · edge-to-edge 按主壳新口径：系统栏显示且透明，内容经 [rememberApInsets] 自行垫边
 *    （旧版隐藏状态栏的口径已随 2026-11-07 全仓反转）。
 *
 * ## 发送通道（全部复用，无重复实现）
 *  · 方向 / OK / 返回 / 主页 / 菜单 → [HidKeys.tap]（HID usage；**被控端**的 `HID_MAP`
 *    翻成 DPAD / ENTER / BACK / HOME / MENU —— 与「TV 遥控」快捷键条同一条路）；
 *  · 音量 / 静音 / 上一曲 / 播放 / 下一曲 → [HotkeyController]（多媒体位图）；
 *  · 关机 / 重启 → [com.allperiph.wireless.TvControllerClient.power]（0x22，需被控端 root）。
 *  · 底部**触摸控制条**（v205，手机控手机 / TV）：单点 = 确认（当前光标处左键点击），
 *    按住滑动 = 移动被控端光标（0x01 相对位移帧，被控端浮层光标跟随）。
 */
class RemoteActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    /** 光标浮层开关（0x32，v223.10）：Compose 状态，chip 展示与发送动作共用同一份 */
    internal val cursorOverlayOn = mutableStateOf(true)

    /** 回前台自增：目标行 / 出口状态按需重算（选完设备回来、连接状态变化后刷新） */
    internal val resumeTick = mutableIntStateOf(0)

    private fun prefs() = getSharedPreferences(PREF, MODE_PRIVATE)

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(ThemePref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        // 强调色要叠在「正在用的这个主题」上才算数：View 弹窗（语音说明 / 关机确认）仍走主题
        ApPalette.applyRuntimeTheme(this)
        // 读回上次偏好并即时生效：重进本页（含重连）后，上次关掉的浮层不会又冒出来
        cursorOverlayOn.value = prefs().getBoolean(KEY_CURSOR_OVERLAY, true)
        com.allperiph.wireless.TvControllerClient.lastOverlayCursorEnabled = cursorOverlayOn.value
        if (ControlTarget.isControlling()) {
            ControlTarget.controlClient?.overlayToggle(cursorOverlayOn.value)
        }
        setContentView(
            androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        RemotePanel(host = this@RemoteActivity)
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++ // 目标行跟随回前台的最新状态
    }

    /** 切换被控端光标浮层：记偏好 + 立即发给当前被控端（没连着就只记，连上自动生效） */
    internal fun toggleCursorOverlay() {
        cursorOverlayOn.value = !cursorOverlayOn.value
        prefs().edit().putBoolean(KEY_CURSOR_OVERLAY, cursorOverlayOn.value).apply()
        com.allperiph.wireless.TvControllerClient.lastOverlayCursorEnabled = cursorOverlayOn.value
        if (ControlTarget.isControlling()) {
            ControlTarget.controlClient?.overlayToggle(cursorOverlayOn.value)
        } else {
            // 浮层是**被控端**画的（PC 面板 / TV 端 0x32），USB / 蓝牙出口没有这个能力：
            // 过去这里静默什么都不做，用户只看到「开了关都没反应」。偏好照样记住，连上再生效。
            Toast.makeText(this, getString(R.string.ui_remote_cursor_need_target), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 选设备：交给主页的选择器（发现 / 连接逻辑只保留一处实现），
     * 但**必须带 [com.allperiph.ui.compose.ApShellActivity.EXTRA_PICK_DEVICE]** ——
     * 否则主页只会停在默认的触控板页。带上之后主页直接弹选择器，
     * 选完 / 取消自动 finish 回到本页，本页 onResume 再刷新目标。
     */
    internal fun pickTarget() {
        startActivity(
            Intent(this, com.allperiph.ui.compose.ApShellActivity::class.java)
                .putExtra(com.allperiph.ui.compose.ApShellActivity.EXTRA_PICK_DEVICE, true),
        )
    }

    /** 多媒体键：按下 + 60ms 释放（被控端按位图边沿识别，见 HotkeyController） */
    internal fun media(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }

    /** 触摸条单点确认的后半段：左键按下发出后，40ms 补抬起（被控端按边沿识别单击） */
    internal fun postConfirmRelease() {
        handler.postDelayed({ HidMouse.move(0, 0, 0) }, 40)
    }

    /** 关机 / 重启：会直接关掉被控设备，必须确认（与键盘页同一套语义） */
    internal fun confirmPower(action: Int, label: String) {
        val c = ControlTarget.controlClient
        if (c == null) {
            Toast.makeText(this, getString(R.string.ui_remote_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        ApDialog.builder(this)
            .setTitle(getString(R.string.ui_remote_dialog_device, label))
            .setMessage(getString(R.string.ui_remote_dialog_no_only_need, label))
            .setPositiveButton(label) { _, _ ->
                c.power(action)
                Toast.makeText(this, getString(R.string.ui_remote_toast_send_done, label), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** b15 语音输入：走手机的麦（手机是输入端）——识别结果经 9511 INPUT_TEXT(COMMIT)
     *  粘进对端焦点框。不发 HID bit9（0x0203 唤起的是对端助手、用对端的麦，会与对端
     *  自己的语音入口重复）。依赖 Wi-Fi 控制链；断链或无识别服务时如实提示。
     *  v223.29.33：先挑识别器再拉起 —— 国内只有 Google 语音（网络不可达）时不再静默
     *  拉起，如实说明并主推输入法语音；有非 Google 识别器时显式 setPackage 防落回 Google。 */
    internal fun startVoiceInput() {
        if (ControlTarget.controlClient == null) {
            Toast.makeText(this, getString(R.string.ui_remote_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ui_remote_item_voice))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        }
        val handlers = packageManager.queryIntentActivities(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val nonGoogle = handlers.firstOrNull {
            !(it.activityInfo?.packageName ?: "").startsWith("com.google.android")
        }
        if (nonGoogle == null) {
            if (handlers.isEmpty()) {
                Toast.makeText(this, getString(R.string.ui_remote_voice_unavailable), Toast.LENGTH_SHORT).show()
                return
            }
            // 只有 Google 语音（国内网络不可达）：不再静默拉起 —— 如实说明，主推输入法语音
            ApDialog.builder(this)
                .setTitle(getString(R.string.ui_remote_item_voice))
                .setMessage(getString(R.string.ui_remote_voice_google_only))
                .setNegativeButton(getString(R.string.ui_remote_voice_google_open)) { _, _ ->
                    launchVoice(intent, pickPkg(handlers))
                }
                .setPositiveButton(getString(R.string.ui_common_cancel), null)
                .show()
            return
        }
        launchVoice(intent, nonGoogle.activityInfo.packageName)
    }

    /** 非 Google 缺省时退回第一个可用识别器的包名（显式 setPackage 防歧义解析落回 Google） */
    private fun pickPkg(handlers: List<android.content.pm.ResolveInfo>): String =
        handlers.firstOrNull()?.activityInfo?.packageName ?: ""

    private fun launchVoice(intent: Intent, pkg: String) {
        if (pkg.isNotEmpty()) intent.setPackage(pkg)
        runCatching {
            startActivityForResult(intent, REQ_VOICE_INPUT)
        }.onFailure {
            Toast.makeText(this, getString(R.string.ui_remote_voice_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE_INPUT || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (text.isEmpty()) return
        val c = ControlTarget.controlClient
        if (c == null) {
            Toast.makeText(this, getString(R.string.ui_remote_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        c.sendInputText(text, ApxFrame.INPUT_FLAG_COMMIT)
    }

    internal companion object {
        // 光标浮层开关偏好（0x32）：关掉的状态跨会话记住
        private const val PREF = "apx_remote"
        private const val KEY_CURSOR_OVERLAY = "cursor_overlay"
        // b15：语音识别 onActivityResult 请求码（仅本类内部使用）
        private const val REQ_VOICE_INPUT = 0x56

        // HID usage（page 0x07）—— 与「TV 遥控」快捷键套一致，被控端 HID_MAP 负责翻译
        const val U_UP = 0x52
        const val U_DOWN = 0x51
        const val U_LEFT = 0x50
        const val U_RIGHT = 0x4F
        const val U_ENTER = 0x28
        const val U_ESC = 0x29      // 遥控器「返回」
        const val U_HOME = 0x4A     // 遥控器「主页」
        const val U_MENU = 0x65     // 遥控器「菜单」（Application）
        const val U_POWER = 0x66    // 键盘 Power（软电源：锁屏 / 亮屏）

        // 触摸控制条（v205）
        const val TOUCH_GAIN = 1.5f   // 手指位移 → 光标位移的增益
        const val TAP_MS = 350L       // 单点判定：最长按压时长
        const val TAP_SLOP_DP = 18    // 单点判定：最大位移（dp）

        /**
         * 长按确定（成宇 2026-11-12 23:1x「遥控器触控板的长按确定滑动也没有」）：
         * 原触摸条只做「移动 + 单点确认」，长按住不动没有任何反馈。现补长按语义 ——
         * 按住超过 [HOLD_MS] 且没滑出单点位移阈值，就当作「确定键按下」：光标处
         * 按下（左键按下沿），继续滑动 = 按住拖拽（被控端 finishStroke 判拖拽/长按），
         * 抬起沿补左键抬起。被控端按下沿/抬起沿链路（TvControlServer.onMouse
         * bit0 → TvInjector.pressDown/pressUp）本就支持，控制端只需要把这条流发全。
         */
        const val HOLD_MS = 500L
    }
}

// ———————————————————————————————————————————— Compose 面板 ————————————————————————————————————————————

/** 按键的语义色档位：随行定义走，不再按文案反查（修复旧实现的中文字面量选色隐患） */
private enum class RemoteKeyTint { Normal, Power, Danger, Warn }

/** 一个遥控器按键：键面文字 + 语义色 + 动作（行定义自带色，调用链不再串文案） */
private class RemoteKey(
    val label: String,
    val tint: RemoteKeyTint = RemoteKeyTint.Normal,
    val action: () -> Unit,
)

@Composable
private fun RemotePanel(host: RemoteActivity) {
    val insets = rememberApInsets()
    val ctx = LocalContext.current
    // 方向由 ApAdaptive 统一判据驱动（v223.29.73，UI 审查发现 6）：清单声明了 configChanges
    // （旋转不重建 Activity），此前读 LocalConfiguration 的 orientation 有「回写滞后一拍」的
    // 已知问题（见 ApAdaptive.kt:72-83 注释），旋转瞬间会按旧方向排一帧 —— 改用
    // rememberApAdaptiveLayout（LocalWindowInfo 实测窗口尺寸，与布局测量同源）。
    val land = com.allperiph.ui.miuix.rememberApAdaptiveLayout().landscape
    val tick = host.resumeTick.value
    // 目标行：正在控制谁 / 有没有出口。读内存即时值，随 resumeTick（回前台）刷新。
    val targetText = remember(tick) {
        when {
            ControlTarget.isControlling() -> ctx.getString(
                R.string.ui_remote_target_controlling,
                ControlTarget.label,
                ControlTarget.type.uppercase(),
            )
            else -> {
                val ex = HidMouse.exit()
                if (ex == com.allperiph.core.Uplink.NONE) {
                    ctx.getString(R.string.ui_remote_target_no_exit)
                } else {
                    // 有线 / 蓝牙出口时方向键、确认、触摸条全都通，只是作用在「已配对的主机 / 本机」，
                    // 文案必须说清，否则用户会以为「没连设备 = 这一页没用」。
                    ctx.getString(R.string.ui_remote_target_fallback, com.allperiph.core.Uplink.label(ex))
                }
            }
        }
    }
    // 按键行清单（顺序即屏幕顺序；语义色随行定义，见 [RemoteKey]）
    val rows = listOf(
        listOf(
            RemoteKey(ctx.getString(R.string.ui_touchpad_label_power), RemoteKeyTint.Power) { HidKeys.tap(RemoteActivity.U_POWER) },
            RemoteKey(ctx.getString(R.string.ui_touchpad_label_poweroff), RemoteKeyTint.Danger) {
                host.confirmPower(0, ctx.getString(R.string.ui_touchpad_label_poweroff))
            },
            RemoteKey(ctx.getString(R.string.ui_touchpad_label_reboot), RemoteKeyTint.Warn) {
                host.confirmPower(1, ctx.getString(R.string.ui_touchpad_label_reboot))
            },
        ),
        listOf(
            RemoteKey(ctx.getString(R.string.ui_common_back)) { HidKeys.tap(RemoteActivity.U_ESC) },
            RemoteKey(ctx.getString(R.string.ui_remote_item_home)) { HidKeys.tap(RemoteActivity.U_HOME) },
            RemoteKey(ctx.getString(R.string.ui_remote_item_menu)) { HidKeys.tap(RemoteActivity.U_MENU) },
            RemoteKey(ctx.getString(R.string.ui_remote_item_voice)) { host.startVoiceInput() },
        ),
        listOf(
            RemoteKey(ctx.getString(R.string.ui_touchpad_label_volume)) { host.media(HotkeyController.BIT_VOLUME_DOWN) },
            RemoteKey(ctx.getString(R.string.ui_touchpad_media_mute)) { host.media(HotkeyController.BIT_MUTE) },
            RemoteKey(ctx.getString(R.string.ui_remote_item_volume)) { host.media(HotkeyController.BIT_VOLUME_UP) },
        ),
        listOf(
            RemoteKey(ctx.getString(R.string.ui_touchpad_media_prev)) { host.media(HotkeyController.BIT_PREV_TRACK) },
            RemoteKey(ctx.getString(R.string.ui_remote_item_play)) { host.media(HotkeyController.BIT_PLAY_PAUSE) },
            RemoteKey(ctx.getString(R.string.ui_touchpad_media_next)) { host.media(HotkeyController.BIT_NEXT_TRACK) },
        ),
    )
    val side = if (land) 18.dp else 14.dp
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = insets.left + side, end = insets.right + side)
            .padding(top = insets.top + (if (land) 6.dp else 10.dp), bottom = insets.bottom + (if (land) 6.dp else 8.dp)),
    ) {
        // —— 顶栏：标题 + 选择设备 / 光标浮层 / 关闭（对齐小米遥控的"设备名 + 右上角操作"）——
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ctx.getString(R.string.ui_remote_text_remote),
                fontSize = if (land) 17.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            HeadChip(label = ctx.getString(R.string.ui_remote_pick_device)) { host.pickTarget() }
            HeadChip(
                label = ctx.getString(
                    if (host.cursorOverlayOn.value) R.string.ui_remote_cursor_on else R.string.ui_remote_cursor_off,
                ),
            ) { host.toggleCursorOverlay() }
            HeadChip(label = ctx.getString(R.string.ui_help_close)) { host.finish() }
        }
        Text(
            text = targetText,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 2.dp),
        )
        if (land) {
            // 横屏：旋转 90° 的左右分栏（issue#168 第 6 条二追口径）——左拨盘，右列
            // 依次是电源行 + 三行圆钮 + 提示 + 触摸条（触摸条按屏高 1/4 放大，三追）。
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Dial(host = host, modifier = Modifier.fillMaxSize(), landIn = land)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    KeyRow(rows[0], plate = true, modifier = Modifier.fillMaxWidth().weight(0.78f))
                    rows.drop(1).forEachIndexed { i, row ->
                        KeyRow(
                            row,
                            plate = true,
                            modifier = Modifier.fillMaxWidth().weight(0.85f)
                                .padding(top = if (i == 0) 8.dp else 4.dp),
                        )
                    }
                    Text(
                        text = ctx.getString(R.string.ui_remote_text_reboot_poweroff_volume_home),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    )
                    TouchStrip(
                        host = host,
                        modifier = Modifier.fillMaxWidth()
                            .height(LocalConfiguration.current.screenHeightDp.dp / 4)
                            .padding(top = 6.dp),
                    )
                }
            }
        } else {
            // 竖屏：拨盘比功能行高一大截（旧口径 1.7 vs 0.85），剩给三行圆钮与触摸条；
            // 电源行不在竖屏出现（空间被 1/4 屏高触摸条挤占后的三行口径，见类注释）。
            Box(Modifier.fillMaxWidth().weight(1.7f), contentAlignment = Alignment.Center) {
                Dial(host = host, modifier = Modifier.fillMaxSize(), landIn = land)
            }
            rows.drop(1).forEachIndexed { i, row ->
                KeyRow(
                    row,
                    plate = false,
                    modifier = Modifier.fillMaxWidth().weight(0.85f)
                        .padding(top = if (i == 0) 8.dp else 4.dp),
                )
            }
            Text(
                text = ctx.getString(R.string.ui_remote_text_reboot_poweroff_volume_home),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
            TouchStrip(
                host = host,
                modifier = Modifier.fillMaxWidth()
                    .height(LocalConfiguration.current.screenHeightDp.dp / 4)
                    .padding(top = 6.dp),
            )
        }
    }
}

/**
 * 拨盘：**正圆**的方向环。旧实现的 [DialLayout.onMeasure 取 min] 在 Compose 里
 * 用 `BoxWithConstraints` + `size(min)` 达成同一约束 —— 普通容器会把"圆"拉成椭圆
 * （宽高不等），这里强制正方形兜底。
 */
@Composable
private fun Dial(
    host: RemoteActivity,
    modifier: Modifier = Modifier,
    // v223.29.73：方向由调用方传同一 ApAdaptive 判据（land），不再单独读滞后的 Configuration
    landIn: Boolean = false,
) {
    val view = LocalView.current
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(side)
                .background(colorResource(R.color.md_surface), CircleShape)
                .border(1.dp, colorResource(R.color.card_stroke), CircleShape),
        ) {
            // 四向箭头：贴着环内缘，背景透明（圆环本身就是"按键"的形状）；
            // 点击区固定 44/50dp（issue#168 第 6 条三追后的口径），够手指戳。
            val arrow = if (landIn) 44.dp else 50.dp
            Arrow(
                glyph = "▲",
                tint = MiuixTheme.colorScheme.onSurface,
                size = arrow,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            ) { HidKeys.tap(RemoteActivity.U_UP) }
            Arrow(
                glyph = "▼",
                tint = MiuixTheme.colorScheme.onSurface,
                size = arrow,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            ) { HidKeys.tap(RemoteActivity.U_DOWN) }
            Arrow(
                glyph = "◀",
                tint = MiuixTheme.colorScheme.onSurface,
                size = arrow,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            ) { HidKeys.tap(RemoteActivity.U_LEFT) }
            Arrow(
                glyph = "▶",
                tint = MiuixTheme.colorScheme.onSurface,
                size = arrow,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            ) { HidKeys.tap(RemoteActivity.U_RIGHT) }
            // 中心 OK：主色圆钮，尺寸按拨盘直径的 34%（下限 48dp，对应旧实现的 px 下限）
            val okSize = (side * 0.34f).coerceAtLeast(48.dp)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(okSize)
                    .background(MiuixTheme.colorScheme.primary, CircleShape)
                    .clickable {
                        Feedback.tap(view)
                        HidKeys.tap(RemoteActivity.U_ENTER)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "OK",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** 环上的一个方向键：透明底 + 大字号箭头（[size] 即点击区，≥44dp 满足最小触控目标）。 */
@Composable
private fun Arrow(
    glyph: String,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier
            .size(size)
            .clickable {
                Feedback.tap(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = 26.sp, color = tint)
    }
}

/** 按键行：每格等宽；竖屏是固定直径的正圆（旧 68dp 口径），横屏是铺满格子的圆角矩形。 */
@Composable
private fun KeyRow(row: List<RemoteKey>, plate: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        row.forEach { key ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (plate) {
                    // 横屏（issue#168 第 6 条二追）：行高交给权重分，固定直径会被裁 ——
                    // 圆角矩形铺满格子（留 3dp 呼吸边），高度跟行高走、文字必完整。
                    PlateKey(key = key, modifier = Modifier.fillMaxSize().padding(3.dp))
                } else {
                    CircleKey(key = key, size = 68.dp)
                }
            }
        }
    }
}

/** 竖屏圆钮：正圆底 + 文字（不用字符图标，缺字的老坑见类注释）。 */
@Composable
private fun CircleKey(key: RemoteKey, size: Dp) {
    val view = LocalView.current
    Box(
        Modifier
            .size(size)
            .background(colorResource(R.color.md_surface), CircleShape)
            .border(1.dp, colorResource(R.color.card_stroke), CircleShape)
            .clickable {
                Feedback.tap(view)
                key.action()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            fontSize = if (key.label.length > 2) 13.sp else 15.sp,
            color = keyColor(key.tint),
            textAlign = TextAlign.Center,
        )
    }
}

/** 横屏圆角矩形按钮：格子铺满、高度跟行高走（[CircleKey] 的固定直径在权重行里会被裁）。 */
@Composable
private fun PlateKey(key: RemoteKey, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .background(colorResource(R.color.md_surface), shape)
            .border(1.dp, colorResource(R.color.card_stroke), shape)
            .clickable {
                Feedback.tap(view)
                key.action()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            fontSize = if (key.label.length > 2) 13.sp else 15.sp,
            color = keyColor(key.tint),
            textAlign = TextAlign.Center,
        )
    }
}

/** 语义色 → 当前色值：电源走主题主色，关机红 / 重启橙取语义资源（明暗各一套）。 */
@Composable
private fun keyColor(tint: RemoteKeyTint): Color = when (tint) {
    RemoteKeyTint.Normal -> MiuixTheme.colorScheme.onSurface
    RemoteKeyTint.Power -> MiuixTheme.colorScheme.primary
    RemoteKeyTint.Danger -> colorResource(R.color.miuix_danger)
    RemoteKeyTint.Warn -> colorResource(R.color.state_warn)
}

/** 顶栏胶囊（选择设备 / 光标浮层 / 关闭）：描边圆片 + 主题主色文字。 */
@Composable
private fun HeadChip(label: String, onClick: () -> Unit) {
    val view = LocalView.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .padding(start = 8.dp)
            .background(colorResource(R.color.md_surface), shape)
            .border(1.dp, colorResource(R.color.card_stroke), shape)
            .clickable {
                Feedback.tap(view)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, maxLines = 1)
    }
}

/**
 * 触摸控制条 —— 手机控制手机 / TV 时用触摸控制被控端光标：
 *  · 单点（位移 <18dp 且 <350ms）= 确认：在**当前光标处**发一次左键点击（0x01 按下→抬起）；
 *  · 按住滑动 = 移动光标：相对位移帧（0x01，dx/dy），被控端浮层光标跟随；
 *  · 未连接受控设备：按下即提示，不发任何帧（与按键行同口径）。
 *
 * 与触控板页（GestureEngine）的关系：本条是**遥控器页内**的轻量实现，
 * 只做「移动 + 确认」两个动作，不带拖拽 / 滚动 / 右键 —— 遥控器场景用不上。
 * P1-4 的两轴独立亚像素余数口径原样保留。
 */
@Composable
private fun TouchStrip(host: RemoteActivity, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(colorResource(R.color.md_surface), shape)
            .border(1.dp, colorResource(R.color.card_stroke), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // 出口判据统一在 HidMouse / Uplink.resolve：无线有受控目标、有线/蓝牙靠本机
                    // HID，三条任一可用都能动光标 —— 只有全没有才提示（旧实现只看 ControlTarget，
                    // 插线 / 蓝牙时这里永远弹「还没连上设备」，触摸条等于废掉）。
                    if (!HidMouse.available()) {
                        Toast.makeText(ctx, ctx.getString(R.string.ui_remote_toast_no_exit), Toast.LENGTH_SHORT).show()
                    }
                    val downAt = android.os.SystemClock.uptimeMillis()
                    val start = down.position
                    var last = start
                    var fracX = 0f
                    var fracY = 0f
                    // 长按确定：按住不动超过 HOLD_MS 即「确定按下」——此后滑动是按住拖拽，
                    // 抬起沿补左键抬起（成宇 2026-11-12 23:1x「长按确定滑动也没有」）。
                    // 触发用 handler 定时器而不是事件循环里查表：原地按住时往往没有新的
                    // 指针事件，循环里的判断永远轮不到；定时器到点时复核「仍在单点位移
                    // 阈值内」才补按，滑出去过的手不算。抬起 / 换路时回收定时器。
                    var holdArmed = false
                    val armHold = Runnable {
                        if (holdArmed) return@Runnable
                        val still = kotlin.math.hypot(
                            (last.x - start.x).toDouble(),
                            (last.y - start.y).toDouble(),
                        ) < RemoteActivity.TAP_SLOP_DP.dp.toPx()
                        if (still && HidMouse.move(1, 0, 0)) {
                            holdArmed = true
                            Feedback.tap(view)
                        }
                    }
                    view.postDelayed(armHold, RemoteActivity.HOLD_MS)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        fracX += (ch.position.x - last.x) * RemoteActivity.TOUCH_GAIN
                        fracY += (ch.position.y - last.y) * RemoteActivity.TOUCH_GAIN
                        last = ch.position
                        val dx = fracX.toInt().coerceIn(-127, 127)
                        val dy = fracY.toInt().coerceIn(-127, 127)
                        if (dx != 0 || dy != 0) {
                            // 三条链路（无线 9511 / USB HID / 蓝牙 HID）由 HidMouse 统一择路；
                            // 长按补按后位移动比特 1 置位 = 按住拖拽流
                            if (HidMouse.move(if (holdArmed) 1 else 0, dx, dy)) {
                                fracX -= dx; fracY -= dy
                            } else {
                                fracX = 0f; fracY = 0f
                            }
                        }
                    }
                    // 抬起收尾：先撤长按定时器，再按三态收尾——
                    // 长按过 = 补左键抬起（结束拖拽/触发被控端长按判定）；
                    // 单点（位移与时长都在阈值内）= 确认——按下 → 40ms 后抬起；
                    // 其余（移动后松手）= 什么都不发。
                    view.removeCallbacks(armHold)
                    val dur = android.os.SystemClock.uptimeMillis() - downAt
                    val moved = kotlin.math.hypot(
                        (last.x - start.x).toDouble(),
                        (last.y - start.y).toDouble(),
                    )
                    if (holdArmed) {
                        if (HidMouse.move(0, 0, 0)) Feedback.tap(view)
                    } else if (moved < RemoteActivity.TAP_SLOP_DP.dp.toPx() && dur < RemoteActivity.TAP_MS) {
                        if (HidMouse.move(1, 0, 0)) {
                            host.postConfirmRelease()
                            Feedback.tap(view)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ctx.getString(R.string.ui_remote_touchpad_hint),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}
