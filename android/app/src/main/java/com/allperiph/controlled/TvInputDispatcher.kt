package com.allperiph.controlled

/**
 * 网络输入事件中枢：对方手机连入后，[TvControlServer] 解析出鼠标 / 键盘 / 触摸 / 多媒体
 * 帧并调用这里的桥接方法；被控模式无演示 UI，[Listener] 默认不注册（仅系统注入走 [TvInjector]）。
 *
 * 所有桥接方法都在主线程被调用（server 在分发前已切主线程）。
 */
object TvInputDispatcher {

    /** 由被控 UI（如有）在 onResume 注册、onPause 注销；不注册则为空操 */
    @Volatile
    var listener: Listener? = null

    interface Listener {
        fun onCursorMove(x: Float, y: Float, absolute: Boolean)
        fun onCursorClick()
        fun onKey(keyCode: Int, down: Boolean)
        fun onText(ch: Char)
        fun onGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {}
        fun onPeer(connected: Boolean, peer: String)
    }

    fun cursorMove(x: Float, y: Float, absolute: Boolean) =
        listener?.onCursorMove(x, y, absolute)

    fun cursorClick() = listener?.onCursorClick()

    fun key(keyCode: Int, down: Boolean) = listener?.onKey(keyCode, down)

    fun text(ch: Char) = listener?.onText(ch)

    fun onGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) =
        listener?.onGamepad(buttons, x, y, rx, ry)

    fun peer(connected: Boolean, peer: String) = listener?.onPeer(connected, peer)
}
