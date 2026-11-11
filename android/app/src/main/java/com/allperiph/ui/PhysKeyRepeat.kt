package com.allperiph.ui

import android.os.Handler
import android.os.Looper
import com.allperiph.hid.HidKeys

/**
 * 实体键盘长按连发（issue#168 三追第 5 条）。
 *
 * 旧实现把连发完全托付给两条「他者」：
 *   - USB / 蓝牙出口：指望电脑系统对着「一直按着」的报告自己重复（多数场景成立）；
 *   - 无线 9511 出口：指望安卓把系统自动重复事件送上门，每来一帧补一对上下沿。
 * 实测至少一条会断：退后台走无障碍通路时重复帧未必送达、厂商 ROM 的实体键盘
 * 重复设置各玩各的、对端把毫秒级的上下沿批量合并——表现就是「虚拟键帽能连发、
 * 实体键盘长按没反应」（用户 2026-11-11 反馈）。
 *
 * 现在与屏上键帽连发（KeyboardPage #6）同一套**自驱**节奏：按下即 hold，
 * 400ms 无抬起起拍，每 55ms 一拍「先清再发」（每拍都是真实的 按下→抬起 边沿），
 * ACTION_UP 停。USB / 蓝牙出口下多发的同内容报告不构成新边沿，对端原生重复
 * 语义不受影响；无线出口下由此完全不再依赖系统重复事件。
 *
 * 与真实键盘一致：连发只跟「最后按下的那颗键」走（后按的键接管连发），
 * 抬手只放抬起的这颗（HidKeys.releaseHold 按 usage 记账，两键交叠互不顶掉）。
 */
internal object PhysKeyRepeat {

    private val handler = Handler(Looper.getMainLooper())
    private const val FIRST_DELAY_MS = 400L
    private const val BEAT_MS = 55L

    /** 连发中的键（null = 空闲）；每拍重发它 */
    private var combo: HidKeys.Combo? = null

    /** 在排的拍子（null = 未起拍） */
    private var beat: Runnable? = null

    /** 首次按下：按住不放 + 起 400ms 连发定时器 */
    fun start(c: HidKeys.Combo) {
        // 全审查条 4：换键接管连发时只停拍 + 只放上一颗连发键 —— 旧实现走
        // stop(-1) 全释放（releaseNow），把用户还按着的其它键一并误放掉
        //（如 A 键还按着、B 键接管连发，A 应保持按下）。
        beat?.let { handler.removeCallbacks(it) }
        beat = null
        val prev = combo
        combo = c
        if (prev != null) HidKeys.releaseHold(prev.usage) else HidKeys.releaseNow()
        HidKeys.hold(c)
        val r = object : Runnable {
            override fun run() {
                val cur = combo ?: return
                // 先清再发：每拍都是一次真实的 按下→抬起（与键帽连发同语义，
                // 否则对端看不到新边沿，长按只出第一个字符）
                HidKeys.releaseNow()
                HidKeys.hold(cur)
                handler.postDelayed(this, BEAT_MS)
            }
        }
        beat = r
        handler.postDelayed(r, FIRST_DELAY_MS)
    }

    /**
     * 抬手（usage >= 0）：停拍并只放这颗键；usage = -1 表示整链复位（全释放）。
     * 系统自动重复帧到达时**不要**调本方法——重复帧在桥里只消费不处理，
     * 节奏统一由这里驱动。
     */
    fun stop(usage: Int) {
        beat?.let { handler.removeCallbacks(it) }
        beat = null
        combo = null
        if (usage >= 0) HidKeys.releaseHold(usage) else HidKeys.releaseNow()
    }

    /** 链路中断/退后台的整组复位（与 releaseStuckKeys / resetKeys 同点调用） */
    fun cancel() = stop(-1)
}
