package com.allperiph.tv.ui

import android.graphics.PointF

/**
 * 网络输入事件中枢：手机连入后，[com.allperiph.tv.net.TcpControlServer] 解析出
 * 鼠标 / 键盘 / 触摸 / 多媒体帧并调用这里的桥接方法；UI（[MainActivity]）注册
 * [Listener] 把事件落到界面（光标移动、卡片选中、文本输入等）。
 *
 * 所有桥接方法都应在**主线程**被调用（server 在分发前已切主线程），
 * 监听实现内可直接操作 View。
 */
object TvInputDispatcher {

    /** 由 MainActivity 在 onResume 注册、onPause 注销 */
    @Volatile
    var listener: Listener? = null

    interface Listener {
        /** 光标移动。absolute=false 时 (x,y) 为相对增量；absolute=true 时 (x,y)∈[0,1] 为归一化绝对坐标 */
        fun onCursorMove(x: Float, y: Float, absolute: Boolean)

        /** 确认/点击（鼠标左键按下边沿、或触摸 down） */
        fun onCursorClick()

        /** 已映射到 Android KeyEvent 的键（DPAD / 回车 / 删除 / 媒体键等）；down=按下边沿 */
        fun onKey(keyCode: Int, down: Boolean)

        /** 可见字符输入（键盘字母/数字） */
        fun onText(ch: Char)

        /** 链路状态变化 */
        fun onPeer(connected: Boolean, peer: String)
    }

    fun cursorMove(x: Float, y: Float, absolute: Boolean) =
        listener?.onCursorMove(x, y, absolute)

    fun cursorClick() = listener?.onCursorClick()

    fun key(keyCode: Int, down: Boolean) = listener?.onKey(keyCode, down)

    fun text(ch: Char) = listener?.onText(ch)

    fun peer(connected: Boolean, peer: String) = listener?.onPeer(connected, peer)
}
