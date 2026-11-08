package com.allperiph.controlled

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.util.Log
import com.allperiph.ui.miuix.applyEdgeToEdge

class RemoteInputActivity : ComponentActivity() {

    private var editText: EditText? = null
    /** 程序性改写输入框时置 true：TextWatcher 会把“删字”当成退格发往电脑，
     *  发送后清空输入框绝不能触发它（否则刚发出去的字会被逐个删掉）。 */
    private var suppressInputWatcher = false
    /** 增量发送失败的节流提示（2.5s 一条，别刷屏） */
    private var lastFailHintAt = 0L
    /** v223.29.33：与对端已对齐的文本镜像 —— 增量 diff 的基准。每帧只发对端真正缺的
     *  差量：旧实现按 (start,before,count) 直接取 substring，IME 组合期的整段 replace
     *  （拼音串→词）会把已发过的前缀再发一遍、或只发退格丢掉新增，是「打的字和电脑上
     *  照不齐 / 越打越乱」的根源（v223.29.31 的 0x04 对账只在「发送」拍自愈，中间态照样漂）。 */
    private var lastSent = ""
    private var sourceDevice: String = ""
    private var hintText: String = ""

    // ———— 回程通道：请求经谁进来，回复就走谁（viaClient 见 companion） ————
    // 此前所有发送硬绑 ControlledService.server：当本页是被 0x25 经**客户端链路**
    //（本机正在控制的目标设备）唤起时，文字/编辑键会发给“正在控制本机的设备”，方向整个反了。

    private fun sendText(text: String, flags: Int): Boolean {
        if (viaClient) {
            val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false
            // 真实结果：此前恒 return true，写失败静默 —— 对端永久少这段字
            return c.sendInputText(text, flags)
        }
        return ControlledService.server?.sendInputText(text, flags) ?: false
    }

    /** 失败帧积压（保序）：单向增量链路丢一帧对端就永久漂移 —— 发送失败的帧先排队，
     *  下一次发送前按序重推，通道一恢复就自动补齐（不再要用户「恢复后重新打」）。 */
    private val pendingFrames = ArrayDeque<Pair<String, Int>>()

    private fun sendTextReliable(text: String, flags: Int): Boolean {
        // v223.29.33：旧实现「重推积压失败」时直接 return false —— 当前帧没有入队，
        // 链路抖动的瞬间这帧就永久丢了（镜像与对端从此对不上）。现在无论卡在重推
        // 还是本次发送，当前帧都排到队尾：积压与新帧统一保序，恢复即补齐。
        var flushed = true
        while (pendingFrames.isNotEmpty()) {
            val (t, f) = pendingFrames.first()
            if (!sendText(t, f)) { flushed = false; break }
            pendingFrames.removeFirst()
        }
        if (flushed && sendText(text, flags)) return true
        pendingFrames.addLast(text to flags)
        return false
    }

    private fun sendDone(): Boolean {
        if (viaClient) {
            val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false
            c.sendInputDone(); return true
        }
        return ControlledService.server?.let { it.sendInputDone(); true } ?: false
    }

    private fun sendMouseFrame(buttons: Int, dx: Int, dy: Int): Boolean {
        if (viaClient) {
            val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false
            return c.mouse(buttons, dx, dy)
        }
        return ControlledService.server?.sendControl(
            byteArrayOf(0x01.toByte(), buttons.toByte(), dx.toByte(), dy.toByte(), 0)) ?: false
    }

    private fun sendSpecial(mod: Int, vk: Int): Boolean {
        if (!viaClient) {
            return ControlledService.server?.let { it.sendSpecialKey(mod, vk); true } ?: false
        }
        // 客户端通道：0x28 在手机/TV 被控端没有接收分支（release README 已知遗留），
        // 改走 0x03 键盘帧（HID usage）—— 手机 / TV / PC 三端都有现成接收处理；
        // mod 位图两侧语义一致（1=Ctrl 2=Shift 4=Alt）。
        val usage = VK_TO_USAGE[vk]
        if (usage == null) { Log.w("RemoteInput", "未知 VK：0x${vk.toString(16)}"); return false }
        val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false
        c.keyboard(usage, true, mod)
        c.keyboard(usage, false, 0)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        // 强调色要叠在「正在用的这个主题」上才算数（清单主题是新建的，父上下文叠加会丢）
        // 本机正在替对端输入：此时本机的输入框获焦**不能**再反向请求对端，
        // 否则两边互相 0x25 来回弹输入法（remoteInputMode 此前是个零赋值的死标记）
        com.allperiph.shared.accessibility.ApxAccessibilityService.remoteInputMode = true
        sourceDevice = intent.getStringExtra(EXTRA_SOURCE) ?: ""
        hintText = intent.getStringExtra(EXTRA_HINT) ?: ""
        setContentView(buildUi())
        editText?.requestFocus()
        editText?.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 编辑键排（文字修改一律走特殊键链路，发送框只负责新增文本） */
    private val editKeys = listOf(
        "⌫" to (0 to 0x08), "Del" to (0 to 0x2E),
        "←" to (0 to 0x25), "↑" to (0 to 0x26), "↓" to (0 to 0x28), "→" to (0 to 0x27),
        "Home" to (0 to 0x24), "End" to (0 to 0x23),
        "Tab" to (0 to 0x09), "Enter" to (0 to 0x0D),
        "全选" to (1 to 0x41), "复制" to (1 to 0x43), "粘贴" to (1 to 0x56), "剪切" to (1 to 0x58),
    )

    /**
     * 迁 Compose（`ThemePref` 下线的最后一步；外观总控已拆成 ApPalette / ApSkin）：外壳、标题行、编辑键排、底部按钮
     * 换成 Compose；**输入框与内嵌触控板用 `AndroidView` 互操作保留** —— 前者是
     * `EditText` + `TextWatcher` 增量 diff 上屏 + 平台 IME（文字到对端的唯一通道），
     * 后者是每帧起帧节流、±127 分帧、长按 400ms 进拖拽的原始触摸逻辑，
     * 搬进 Compose 属于拿「能不能打出字 / 光标动不动」换一致性。
     */
    private fun buildUi(): View {
        val bg = getColor(R.color.remote_panel_bg)
        val accent = getColor(R.color.remote_panel_accent)
        val dim = getColor(R.color.remote_panel_text_dim)
        val neutral = getColor(R.color.remote_panel_neutral)
        return ComposeView(this).apply {
            setContent {
                ApMiuixTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(bg))
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = getString(R.string.ui_remoteinput_title),
                                color = androidx.compose.ui.graphics.Color(accent),
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = getString(R.string.ui_remoteinput_from, sourceDevice),
                                color = androidx.compose.ui.graphics.Color(dim),
                                fontSize = 13.sp,
                            )
                        }
                        AndroidView(
                            factory = { buildInput() },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp, bottom = 8.dp),
                        ) {
                            for ((label, mk) in editKeys) {
                                KeyButton(label) { sendSpecial(mk.first, mk.second) }
                            }
                        }
                        AndroidView(
                            factory = { buildTouchpad() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(top = 4.dp, bottom = 10.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ActionPill("语音", neutral) { startVoiceInput() }
                            Spacer(Modifier.width(12.dp))
                            ActionPill(getString(R.string.ui_common_cancel), neutral) { cancelAndFinish() }
                            Spacer(Modifier.width(12.dp))
                            ActionPill(getString(R.string.ui_common_send), accent) { sendCurrent() }
                        }
                    }
                }
            }
        }
    }

    /** 输入框本体（互操作保留）：增量 diff 上屏、删字退格、失焦提示都还在这里 */
    private fun buildInput(): View {
        val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val input = EditText(this).apply {
            setTextColor(getColor(R.color.remote_panel_on))
            setHintTextColor(getColor(R.color.remote_panel_text_faint))
            hint = hintText.ifEmpty { getString(R.string.ui_remoteinput_hint) }
            setBackgroundColor(getColor(R.color.remote_panel_field))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f)); textSize = 18f; typeface = Typeface.DEFAULT
            isFocusable = true; isFocusableInTouchMode = true; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressInputWatcher) return
                    val cur = s?.toString() ?: return
                    if (cur == lastSent) return
                    // v223.29.33：与对端镜像 [lastSent] 做公共前缀/后缀 diff —— 每帧只发
                    // 对端真正缺的差量。旧实现按 (start,before,count) 直接取 substring：
                    // IME 组合期的整段 replace（拼音串→词）会重复发前缀或只发退格丢新增，
                    // 是「打的字和电脑上照不齐 / 越打越乱」的根源。发送按钮的 0x04 对账只在
                    // 发送拍自愈，中间态照样漂 —— 现在从源头保证两端一致。
                    var p = 0
                    val minLen = minOf(cur.length, lastSent.length)
                    while (p < minLen && cur[p] == lastSent[p]) p++
                    var suf = 0
                    while (suf < minLen - p &&
                        cur[cur.length - 1 - suf] == lastSent[lastSent.length - 1 - suf]) suf++
                    val oldMid = lastSent.substring(p, lastSent.length - suf)
                    val newMid = cur.substring(p, cur.length - suf)
                    var ok = true
                    if (oldMid.isNotEmpty()) {
                        // 对端每帧退格删一个**码点**（PC 侧 g_injectedLen 按 utf8Chars 计数）
                        val n = oldMid.codePointCount(0, oldMid.length)
                        repeat(n) {
                            if (!sendTextReliable("", ApxFrame.INPUT_FLAG_BACKSPACE)) ok = false
                        }
                    }
                    if (newMid.isNotEmpty() &&
                        !sendTextReliable(newMid, ApxFrame.INPUT_FLAG_INCREMENTAL)) ok = false
                    lastSent = cur
                    if (!ok) {
                        // 失败必提示不能悄悄丢字（帧已入队，恢复后自动补发）
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastFailHintAt > 2500) {
                            lastFailHintAt = now
                            Toast.makeText(this@RemoteInputActivity,
                                "没发出去 —— 连接断了？恢复后会自动补发",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editText = input
        return input
    }

    /** 内嵌触控板（互操作保留）：滑动=光标、轻点=左键、长按 550ms 左键拖拽；
     *  #3 接通用逻辑：双指点按=右键、双指长按 550ms 右键拖拽（与 touchpad.kt 同口径）。 */
    private fun buildTouchpad(): View {
        val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        var lastTouchLogAt = 0L
        fun mouseFrame(buttons: Int, dx: Int, dy: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            val ok = sendMouseFrame(buttons, dx, dy)
            // 节流 500ms：现场诊断"触控板到底发没发帧 / 链路通不通"
            if (now - lastTouchLogAt > 500) {
                lastTouchLogAt = now
                if (ok) Log.i("RemoteInput", "触控板已发鼠标帧 buttons=$buttons dx=$dx dy=$dy")
                else Log.w("RemoteInput", "触控板发送失败（链路未就绪？）")
            }
        }
        var tpDown = false; var tpDrag = false
        var tpLastX = 0f; var tpLastY = 0f
        var tpDownAt = 0L
        val touchpad = android.widget.FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.remote_panel_field))
            addView(TextView(this@RemoteInputActivity).apply {
                text = getString(R.string.ui_remoteinput_touchpad)
                setTextColor(getColor(R.color.remote_panel_text_faint)); textSize = 12f
                gravity = Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            minimumHeight = dp(150f)
        }
        val dragArm = Runnable {
            if (tpDown && !tpDrag) { tpDrag = true; mouseFrame(0x01, 0, 0) }
        }
        // #3（用户 2026-11-08「触摸板没用通用触摸板的逻辑」）：与通用 touchpad.kt 同口径 ——
        // 双指落下 550ms 锁定右键拖拽（rightDragTask）；期间随手指拖，帧按钮位 0x02。
        var tpSecond = false; var tpSecondAt = 0L; var tpRightDrag = false
        val rightDragArm = Runnable {
            if (tpSecond && !tpRightDrag) { tpRightDrag = true; mouseFrame(0x02, 0, 0) }
        }
        touchpad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    tpDown = true; tpDrag = false; tpDownAt = android.os.SystemClock.uptimeMillis()
                    tpLastX = e.x; tpLastY = e.y
                    v.removeCallbacks(dragArm); v.postDelayed(dragArm, 550)   // #3 对齐通用 550ms
                    true
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    // #3 第二指落下：双指长按 550ms = 右键拖拽（通用 rightDragTask 同口径）
                    if (e.pointerCount == 2) {
                        tpSecond = true; tpSecondAt = android.os.SystemClock.uptimeMillis()
                        v.removeCallbacks(dragArm); tpDrag = false
                        v.postDelayed(rightDragArm, 550)
                        true
                    } else false
                android.view.MotionEvent.ACTION_POINTER_UP ->
                    if (tpSecond) {
                        tpSecond = false; v.removeCallbacks(rightDragArm)
                        val dur = android.os.SystemClock.uptimeMillis() - tpSecondAt
                        if (!tpRightDrag) {
                            if (dur < 300) {   // 双指点按 = 右键单击（短按未拖成）
                                mouseFrame(0x02, 0, 0); mouseFrame(0x00, 0, 0)
                                com.allperiph.ui.Feedback.haptic(v)
                            }
                        } else {
                            mouseFrame(0x00, 0, 0)   // 右键拖拽结束：松开右键
                            tpRightDrag = false
                        }
                        tpLastX = e.x; tpLastY = e.y   // 单指继续：基点归位防跳变
                        true
                    } else false
                android.view.MotionEvent.ACTION_MOVE -> if (tpDown) {
                    var dx = (e.x - tpLastX).toInt(); var dy = (e.y - tpLastY).toInt()
                    tpLastX = e.x; tpLastY = e.y
                    // 位移限幅 ±127（i8 帧格式），大幅移动分帧发
                    while (dx != 0 || dy != 0) {
                        val sx = dx.coerceIn(-127, 127); val sy = dy.coerceIn(-127, 127)
                        mouseFrame(when { tpRightDrag -> 0x02; tpDrag -> 0x01; else -> 0x00 }, sx, sy)
                        dx -= sx; dy -= sy
                    }
                    true
                } else false
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val cancelled = e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    v.removeCallbacks(dragArm); v.removeCallbacks(rightDragArm); tpSecond = false
                    if (tpDown) {
                        val dur = android.os.SystemClock.uptimeMillis() - tpDownAt
                        val moved = kotlin.math.hypot((e.x - tpLastX).toDouble(), (e.y - tpLastY).toDouble()) < dp(12f)
                        if (!tpDrag && !cancelled && moved && dur < 300) {
                            mouseFrame(0x01, 0, 0); mouseFrame(0x00, 0, 0)   // 轻点 = 左键点击
                            com.allperiph.ui.Feedback.haptic(v)               // #5 轻点给震感
                        } else {
                            mouseFrame(0x00, 0, 0)                            // 抬起/取消 = 松开左键
                        }
                    }
                    tpDown = false; tpDrag = false; tpRightDrag = false
                    true
                }
                else -> false
            }
        }
        return touchpad
    }

    /** 编辑键排的一个键（#6 按住 400ms 起每 55ms 连发、抬手吞尾；#5 按下即震） */
    @Composable
    private fun KeyButton(label: String, onClick: () -> Unit) {
        var repeatFired by remember { mutableStateOf(false) }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        LaunchedEffect(pressed) {
            if (pressed) {
                kotlinx.coroutines.delay(400)
                while (true) {
                    repeatFired = true
                    onClick()
                    kotlinx.coroutines.delay(55)
                }
            }
        }
        val host = androidx.compose.ui.platform.LocalView.current
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_field)))
                .clickable(
                    interactionSource = interaction,
                    indication = null,   // 键盘页 KeyBox 同款（视觉自绘/按下震补偿；platform.LocalIndication 本编译环境解析不到）
                ) {
                    if (repeatFired) { repeatFired = false }
                    else { com.allperiph.ui.Feedback.tap(host); onClick() }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                color = androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_on)),
                fontSize = 13.sp,
            )
        }
    }

    /** 底部按钮（取消 / 发送）（#5 按下即震） */
    @Composable
    private fun ActionPill(label: String, bg: Int, onClick: () -> Unit) {
        val host = androidx.compose.ui.platform.LocalView.current
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(bg))
                .clickable { com.allperiph.ui.Feedback.tap(host); onClick() }
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                color = androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_on)),
                fontSize = 14.sp,
            )
        }
    }

    /** 取消：按**对端镜像**的码点数逐个退格再 finish（0x08 三端接收侧是空实现，光发它等于白点） */
    private fun cancelAndFinish() {
        // 删的是对端真正有的字（镜像 = 对端状态）；镜像同步清零
        val n = lastSent.codePointCount(0, lastSent.length)
        repeat(n) { sendText("", ApxFrame.INPUT_FLAG_BACKSPACE) }
        // 0x08 仍照发：语义保留，将来三端若实现「整段撤销」，这一帧就是信号
        sendText("", ApxFrame.INPUT_FLAG_CANCEL)
        lastSent = ""
        finish()
    }

    /**
     * 发送：0x04 全量对账（整段替换，PC 端退格清场重打）+ DONE 帧 + 清空输入框接着打下一句。
     * 打字时 TextWatcher 已按 diff 上屏，对账只是把「可能漂了的中间态」在这一拍拉齐。
     */
    private fun sendCurrent() {
        val t = editText?.text?.toString() ?: ""
        if (t.isEmpty()) {
            Toast.makeText(this, getString(R.string.ui_remoteinput_need_text), Toast.LENGTH_SHORT).show()
            return
        }
        // 全量对账（0x04=整段替换，PC 端退格清场重打）：增量丢帧在这一拍归零自愈。
        // 对账发不出去就**不清空输入框** —— 字留在框里可重试，而不是凭空消失。
        if (!sendTextReliable(t, ApxFrame.INPUT_FLAG_COMMIT)) {
            Toast.makeText(this, "没发出去 —— 连接断了？这段还在框里，恢复后再点发送",
                Toast.LENGTH_LONG).show()
            return
        }
        sendDone()
        suppressInputWatcher = true          // 清空动作绝不能被当成退格发给对端
        editText?.setText("")
        suppressInputWatcher = false
        editText?.requestFocus()             // 焦点回到输入框，键盘保持弹出
        lastSent = ""                        // 镜像归零：对端已落定上一句，下一句从零对齐
        Toast.makeText(this, getString(R.string.ui_remoteinput_sent), Toast.LENGTH_SHORT).show()
    }

    /** #4 语音输入：系统语音识别 → 文字 append 进输入框（自动走 TextWatcher 增量上屏/对账链）。
     *  v223.29.33：**先挑识别器再拉起** —— 国内 ROM 只有 Google 语音（网络不可达），旧实现
     *  静默 startActivityForResult 必然落到它。现在优先非 Google 的识别活动（显式 setPackage
     *  防落回 Google）；只有 Google 时如实说明并主推输入法语音（输入法口述的文字同样进
     *  输入框、自动上屏到电脑），用户仍可显式选择打开。 */
    private fun startVoiceInput() {
        // 字面量与 SpeechRecognizer 源码逐字一致（ACTION / LANGUAGE_MODEL /
        // LANGUAGE_MODEL_FREE_FORM / LANGUAGE_PREFERENCE / LANGUAGE）——
        // 本编译环境解析不到该类静态字段，公开协议常量直接用字面值，零符号依赖。
        val intent = Intent("android.speech.action.RECOGNIZE_SPEECH").apply {
            putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
            putExtra("android.speech.extra.LANGUAGE_PREFERENCE", "zh-CN")
            putExtra("android.speech.extra.LANGUAGE", "zh-CN")
        }
        val handlers = packageManager.queryIntentActivities(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val nonGoogle = handlers.firstOrNull {
            !(it.activityInfo?.packageName ?: "").startsWith("com.google.android")
        }
        val pick = nonGoogle ?: handlers.firstOrNull()
        if (pick == null) {
            Toast.makeText(this, "系统语音识别不可用 —— 用输入法上的语音键说也行",
                Toast.LENGTH_LONG).show()
            return
        }
        if (nonGoogle == null) {
            // 只有 Google 语音：国内网络不可达 —— 如实提示，不静默拉起
            com.allperiph.ui.ApDialog.builder(this)
                .setTitle("语音输入")
                .setMessage("这台手机的系统语音识别只有 Google 语音，国内网络下连不上。\n\n" +
                    "推荐直接点输入法键盘上的语音键口述 —— 文字会进输入框并自动发到电脑。\n\n" +
                    "仍要打开 Google 语音也可以（有网络代理时可用）。")
                .setNegativeButton("仍打开") { _, _ -> launchVoice(intent, pick.activityInfo.packageName) }
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        launchVoice(intent, pick.activityInfo.packageName)
    }

    private fun launchVoice(intent: Intent, pkg: String) {
        intent.setPackage(pkg)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 726)
        } catch (e: Exception) {
            Toast.makeText(this, "系统语音识别不可用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // 本机不再替对端输入：解除防环标记（不解除的话，之后本机输入框获焦不会再请求对端）
        com.allperiph.shared.accessibility.ApxAccessibilityService.remoteInputMode = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window?.decorView?.windowToken, 0)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "source_device"
        const val EXTRA_HINT = "hint"

        /**
         * 回程通道：true = 请求经本机 9511 **客户端**链路进来（本机正在控制的目标设备，
         * TvControllerClient 收到 0x25 兜底启动时置位）；false = 经本机**被控服务**
         * （正在控制本机的设备）。文字/编辑键/鼠标/DONE 按此分流，否则回复会发错方向。
         */
        @Volatile
        var viaClient = false
            private set

        /** Windows VK → HID usage（编辑键排用到的子集；经客户端通道时翻成 0x03 键盘帧，
         *  三端都有现成接收；0x28 特殊键仅 PC 实现，手机/TV 被控端会静默丢弃） */
        private val VK_TO_USAGE = mapOf(
            0x08 to 0x2A, 0x2E to 0x4C,                       // Backspace / Delete
            0x25 to 0x4F, 0x26 to 0x50, 0x27 to 0x51, 0x28 to 0x52,  // ← ↑ → ↓
            0x24 to 0x4A, 0x23 to 0x4D,                       // Home / End
            0x09 to 0x2B, 0x0D to 0x28,                       // Tab / Enter
            0x41 to 0x04, 0x42 to 0x05, 0x43 to 0x06, 0x44 to 0x07, 0x45 to 0x08,
            0x46 to 0x09, 0x47 to 0x0A, 0x48 to 0x0B, 0x49 to 0x0C, 0x4A to 0x0D,
            0x4B to 0x0E, 0x4C to 0x0F, 0x4D to 0x10, 0x4E to 0x11, 0x4F to 0x12,
            0x50 to 0x13, 0x51 to 0x14, 0x52 to 0x15, 0x53 to 0x16, 0x54 to 0x17,
            0x55 to 0x18, 0x56 to 0x19, 0x57 to 0x1A, 0x58 to 0x1B, 0x59 to 0x1C,
            0x5A to 0x1D,
        )

        fun start(context: Context, sourceDevice: String, hint: String) =
            start(context, sourceDevice, hint, false)

        @JvmStatic
        fun start(context: Context, sourceDevice: String, hint: String, fromClient: Boolean) {
            viaClient = fromClient
            context.startActivity(Intent(context, RemoteInputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SOURCE, sourceDevice); putExtra(EXTRA_HINT, hint)
            })
        }
    }
}
