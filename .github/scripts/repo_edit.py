#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 O-1（悬浮球旋转重算 + 动效）：FloatingBallService.kt 8 处。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


P = "android/app/src/main/java/com/allperiph/ui/FloatingBallService.kt"

EDITS = [
    (P, [
        # 1 字段区：旋转重算状态 + 配置变化回调 + 重算函数
        (T("    private val dockSnapPx get() = dp(48)"),
         T("    private val dockSnapPx get() = dp(48)",
           "",
           "    /** 旋转重算用：上次落位时的屏幕尺寸（等比换算 x/y 到新屏） */",
           "    private var lastScreenW = 0",
           "    private var lastScreenH = 0",
           "",
           "    /** 贴边滑入动画；触摸按下即取消，位置以手指为准 */",
           "    private var dockAnim: android.animation.ValueAnimator? = null",
           "",
           "    /**",
           "     * 屏幕配置变化（旋转 / 折叠 / 分屏）→ 重新落位。",
           "     *",
           "     * 旧版没有任何旋转监听：lp.x/y 是绝对像素，竖屏贴右边存的 x 在横屏更宽的屏上",
           "     * 直接落在屏幕中间，贴边态也丢（成宇 2026-11-05 报的「贴边后横屏跑中间」）。",
           "     */",
           "    private val reflowCb = object : android.content.ComponentCallbacks {",
           "        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = reflowForScreenChange()",
           "        override fun onLowMemory() {}",
           "    }",
           "",
           "    private fun reflowForScreenChange() {",
           "        val v = ball ?: return",
           "        if (!ballAttached) return",
           "        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return",
           "        val dm = resources.displayMetrics",
           "        val (left, top) = parentOffset(v)",
           "        dockAnim?.cancel()",
           "        dockAnim = null",
           "        closeMenu()",
           "        if (lastScreenW > 0 && lastScreenH > 0 &&",
           "            (lastScreenW != dm.widthPixels || lastScreenH != dm.heightPixels)",
           "        ) {",
           "            // 比例换算保住相对位置：贴边的球换算后仍在边缘附近，再由 applyDockShape 精确吸边",
           "            lp.x = (lp.x.toFloat() / lastScreenW * dm.widthPixels).toInt()",
           "                .coerceIn(0, (dm.widthPixels - left - lp.width).coerceAtLeast(0))",
           "            lp.y = (lp.y.toFloat() / lastScreenH * dm.heightPixels).toInt()",
           "                .coerceIn(0, (dm.heightPixels - top - lp.height).coerceAtLeast(0))",
           "            publishBallPosition(v)",
           "        }",
           "        lastScreenW = dm.widthPixels",
           "        lastScreenH = dm.heightPixels",
           "        if (docked) applyDockShape(v, dockLeft)",
           "    }", 1),
        # 2 onCreate：记录初值 + 注册旋转回调
        (T("        isRunning = true", "    }"),
         T("        lastScreenW = resources.displayMetrics.widthPixels",
           "        lastScreenH = resources.displayMetrics.heightPixels",
           "        // v223.29.11：旋转后重新落位（旧版无旋转监听，贴边的球转横屏会漂到屏幕中间）",
           "        runCatching { registerComponentCallbacks(reflowCb) }",
           "            .onFailure { Log.w(TAG, \"rotation callback register failed: ${it.message}\") }",
           "        isRunning = true",
           "    }", 1),
        # 3 onDestroy：注销回调
        (T('        Log.i(TAG, "onDestroy")', "        live = null"),
         T('        Log.i(TAG, "onDestroy")',
           "        runCatching { unregisterComponentCallbacks(reflowCb) }",
           "        dockAnim?.cancel()",
           "        live = null", 1),
        # 4 触摸 DOWN：停滑入 + 按压脉冲
        (T("                    downAt = System.currentTimeMillis()",
           "                    moved = false",
           "                }"),
         T("                    downAt = System.currentTimeMillis()",
           "                    moved = false",
           "                    // v223.29.11：抓球即停掉贴边滑入 + 按压脉冲（落下轻微缩小）",
           "                    dockAnim?.cancel()",
           "                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()",
           "                }", 1),
        # 5 触摸 UP：还原缩放
        (T("                    val held = System.currentTimeMillis() - downAt",
           "                    when {"),
         T("                    val held = System.currentTimeMillis() - downAt",
           "                    // 抬手把按压脉冲的缩放还原",
           "                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()",
           "                    when {", 1),
        # 6 settleAfterDrag：贴边改为滑入动画再收条
        (T("            docked = true",
           "            dockLeft = nearLeft",
           "            // 贴边 → 收成竖条（内部自己 updateViewLayout，含尺寸与背景切换）",
           "            applyDockShape(v, nearLeft)",
           "            v.alpha = DOCK_ALPHA"),
         T("            docked = true",
           "            dockLeft = nearLeft",
           "            // 贴边 → 先滑向边缘再收条（v223.29.11 动效；旧版瞬间跳变）",
           "            val edgeX = if (nearLeft) -left else dm.widthPixels - left - v.width",
           "            val fromX = lp.x",
           "            dockAnim?.cancel()",
           "            dockAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {",
           "                duration = 160",
           "                addUpdateListener { a ->",
           "                    lp.x = (fromX + (edgeX - fromX) * a.animatedFraction).toInt()",
           "                    runCatching { wm.updateViewLayout(v, lp) }",
           "                }",
           "                addListener(object : android.animation.AnimatorListenerAdapter() {",
           "                    private var canceled = false",
           "                    override fun onAnimationCancel(a: android.animation.Animator) { canceled = true }",
           "                    override fun onAnimationEnd(a: android.animation.Animator) {",
           "                        // 按下取消 / 中途改停靠侧都不再收条",
           "                        if (canceled || !docked || dockLeft != nearLeft) return",
           "                        applyDockShape(v, nearLeft)",
           "                        v.alpha = DOCK_ALPHA",
           "                    }",
           "                })",
           "                start()",
           "            }", 1),
        # 7 undock：变回圆球回弹
        (T("               else (dm.widthPixels - dp(BALL_DP) * 2).coerceAtLeast(0)",
           "        publishBallPosition(v)"),
         T("               else (dm.widthPixels - dp(BALL_DP) * 2).coerceAtLeast(0)",
           "        publishBallPosition(v)",
           "        // v223.29.11 动效：变回圆球时从压缩态回弹",
           "        v.scaleX = 0.6f",
           "        v.scaleY = 0.6f",
           "        v.animate().scaleX(1f).scaleY(1f).setDuration(220)",
           "            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()", 1),
        # 8 attachFan：扇形逐项浮现
        (T("        if (added) raiseBall()", "        return added"),
         T("        if (added) raiseBall()",
           "        // v223.29.11 动效：扇形逐项浮现（30ms 一档），替代整圈瞬间出现",
           "        fanItems.forEachIndexed { i, (_, cell) ->",
           "            cell.alpha = 0f",
           "            cell.scaleX = 0.7f",
           "            cell.scaleY = 0.7f",
           "            cell.animate().alpha(1f).scaleX(1f).scaleY(1f)",
           "                .setStartDelay(i * 30L).setDuration(160)",
           "                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))",
           "                .start()",
           "        }",
           "        return added", 1),
    ]),
]


def main() -> int:
    loaded = {}
    errors = []
    for path, entries in EDITS:
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as e:
            errors.append(f"{path}: open failed: {e}")
            continue
        loaded[path] = (text, entries)
        for i, (old, _new, expected) in enumerate(entries, 1):
            n = text.count(old)
            if n != expected:
                errors.append(f"{path} edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:300]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for path, (text, entries) in loaded.items():
        for old, new, _expected in entries:
            text = text.replace(old, new)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"OK {path}: {len(entries)} edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
