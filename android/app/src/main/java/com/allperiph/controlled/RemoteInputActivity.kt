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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.util.Log

class RemoteInputActivity : Activity() {

    private var editText: EditText? = null
    /** 程序性改写输入框时置 true：TextWatcher 会把“删字”当成退格发往电脑，
     *  发送后清空输入框绝不能触发它（否则刚发出去的字会被逐个删掉）。 */
    private var suppressInputWatcher = false
    /** 增量发送失败的节流提示（2.5s 一条，别刷屏） */
    private var lastFailHintAt = 0L
    private var sourceDevice: String = ""
    private var hintText: String = ""

    // ———— 回程通道：请求经谁进来，回复就走谁（viaClient 见 companion） ————
    // 此前所有发送硬绑 ControlledService.server：当本页是被 0x25 经**客户端链路**
    //（本机正在控制的目标设备）唤起时，文字/编辑键会发给“正在控制本机的设备”，方向整个反了。

    private fun sendText(text: String, flags: Int): Boolean {
        if (viaClient) {
            val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false
            c.sendInputText(text, flags); return true
        }
        return ControlledService.server?.let { it.sendInputText(text, flags); true } ?: false
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
     * 迁 Compose（`ThemeSkin`/`ThemePref` 下线的最后一步）：外壳、标题行、编辑键排、底部按钮
     * 换成 Compose；**输入框与内嵌触控板用 `AndroidView` 互操作保留** —— 前者是
     * `EditText` + `TextWatcher` 逐字增量上屏 + 平台 IME（文字到对端的唯一通道），
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
                            ActionPill(getString(R.string.ui_common_cancel), neutral) { cancelAndFinish() }
                            Spacer(Modifier.width(12.dp))
                            ActionPill(getString(R.string.ui_common_send), accent) { sendCurrent() }
                        }
                    }
                }
            }
        }
    }

    /** 输入框本体（互操作保留）：逐字增量上屏、删字退格、失焦提示都还在这里 */
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
                    val text = s?.toString() ?: return
                    if (count > before && start + count <= text.length) {
                        // 逐字增量上屏：这是文字到对端的**唯一**通道 ——
                        // 发送按钮不再整段重发（COMMIT=整段再粘一遍，两种帧都注入就是
                        // 用户报的“发送后文字重复两次”）。所以失败必须当场提示，不能悄悄丢字。
                        // sendText 通道助手现在能真实反映发没发出去（客户端通道 ready=false
                        // 时同样返回 false，不再只判“被控服务在不在”）。
                        val ok = sendText(text.substring(start, start + count), ApxFrame.INPUT_FLAG_INCREMENTAL)
                        if (!ok) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastFailHintAt > 2500) {
                                lastFailHintAt = now
                                Toast.makeText(this@RemoteInputActivity,
                                    "没发出去 —— 连接断了？恢复后这段要重新打",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    } else if (count < before) {
                        sendText("", ApxFrame.INPUT_FLAG_BACKSPACE)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editText = input
        return input
    }

    /** 内嵌触控板（互操作保留）：滑动=移动光标、轻点=左键、长按 400ms 进拖拽 */
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
        touchpad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    tpDown = true; tpDrag = false; tpDownAt = android.os.SystemClock.uptimeMillis()
                    tpLastX = e.x; tpLastY = e.y
                    v.removeCallbacks(dragArm); v.postDelayed(dragArm, 400)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> if (tpDown) {
                    var dx = (e.x - tpLastX).toInt(); var dy = (e.y - tpLastY).toInt()
                    tpLastX = e.x; tpLastY = e.y
                    // 位移限幅 ±127（i8 帧格式），大幅移动分帧发
                    while (dx != 0 || dy != 0) {
                        val sx = dx.coerceIn(-127, 127); val sy = dy.coerceIn(-127, 127)
                        mouseFrame(if (tpDrag) 0x01 else 0x00, sx, sy)
                        dx -= sx; dy -= sy
                    }
                    true
                } else false
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val cancelled = e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    v.removeCallbacks(dragArm)
                    if (tpDown) {
                        val dur = android.os.SystemClock.uptimeMillis() - tpDownAt
                        val moved = kotlin.math.hypot((e.x - tpLastX).toDouble(), (e.y - tpLastY).toDouble()) < dp(12f)
                        if (!tpDrag && !cancelled && moved && dur < 300) {
                            mouseFrame(0x01, 0, 0); mouseFrame(0x00, 0, 0)   // 轻点 = 左键点击
                        } else {
                            mouseFrame(0x00, 0, 0)                            // 抬起/取消 = 松开左键
                        }
                    }
                    tpDown = false; tpDrag = false
                    true
                }
                else -> false
            }
        }
        return touchpad
    }

    /** 编辑键排的一个键 */
    @Composable
    private fun KeyButton(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_field)))
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                color = androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_on)),
                fontSize = 13.sp,
            )
        }
    }

    /** 底部按钮（取消 / 发送） */
    @Composable
    private fun ActionPill(label: String, bg: Int, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(bg))
                .clickable { onClick() }
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                color = androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_on)),
                fontSize = 14.sp,
            )
        }
    }

    /** 取消：按剩余字数逐个发退格再 finish（0x08 三端接收侧是空实现，光发它等于白点） */
    private fun cancelAndFinish() {
        val n = editText?.text?.length ?: 0
        repeat(n) { sendText("", ApxFrame.INPUT_FLAG_BACKSPACE) }
        // 0x08 仍照发：语义保留，将来三端若实现「整段撤销」，这一帧就是信号
        sendText("", ApxFrame.INPUT_FLAG_CANCEL)
        finish()
    }

    /**
     * 发送：只发 DONE 帧 + 清空输入框接着打下一句，**绝不发 COMMIT 整段帧**
     *（打字时 TextWatcher 已逐字上屏，再粘一遍就是用户报的“文字重复两次”）。
     */
    private fun sendCurrent() {
        val t = editText?.text?.toString() ?: ""
        if (t.isEmpty()) {
            Toast.makeText(this, getString(R.string.ui_remoteinput_need_text), Toast.LENGTH_SHORT).show()
            return
        }
        sendDone()
        suppressInputWatcher = true          // 清空动作绝不能被当成退格发给对端
        editText?.setText("")
        suppressInputWatcher = false
        editText?.requestFocus()             // 焦点回到输入框，键盘保持弹出
        Toast.makeText(this, getString(R.string.ui_remoteinput_sent), Toast.LENGTH_SHORT).show()
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