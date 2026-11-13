package com.allperiph.controlled

import android.content.Context
import com.allperiph.shared.input.RootInput
import com.allperiph.shared.util.Log
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 被控端输入法自动换装（2026-11-14「键盘不能用」批）：
 *
 * 「全能外设」输入法（[com.allperiph.shared.ime.ApxImeService]）是个**零高度的空壳**
 * —— 它只负责当远程打字的通道，没有键盘界面。手动把系统输入法切成它的老引导
 * 有个要命的副作用：被控机自己点输入框，弹不出任何键盘，用户看到的就是
 * 「这台手机的键盘坏了」。而且每次断连都要人肉切回原输入法，忘一次就懵一次。
 *
 * 所以改成**会话期换装**：对端连入时自动切到我们的通道输入法、断开时自动换回
 * 原来的输入法 —— 被控机平时自己用，键盘一切照旧；只有远程会话开着的那几分钟
 * 是「无声键盘」，打字全走远端。
 *
 * 前提：需要 root（`ime enable` / `settings put secure` 都是系统级写操作）；
 * 没 root 时静默跳过，维持旧口径（手动在系统设置里切）。原输入法 id 存偏好，
 * 服务被杀重启后仍能找回来，不会把用户的键盘弄丢。
 */
object ImeSwitcher {

    private const val TAG = "ImeSwitcher"
    private const val PREF = "apx_controlled"
    private const val KEY_SAVED_IME = "saved_ime"

    /** 本应用通道输入法的完整 id（ime/settings 命令的 default_input_method 口径） */
    private fun ownImeId(ctx: Context): String =
        "${ctx.packageName}/com.allperiph.shared.ime.ApxImeService"

    /**
     * 换装/还原统一串行后台执行：读输入法要开一次性 su 进程（可到秒级），
     * 决不能堵在连接握手的回调线程上；单线程池保证「先切后还原」的顺序不乱。
     */
    private val queue = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "apx-ime-switch")
    }

    /**
     * 读当前默认输入法。[RootInput] 的常驻 shell 是 fire-and-forget（输出没人消费），
     * 回读得另开一次性 `su -c` —— 每次会话只有连入/断开各一次，这个开销付得起。
     * 超时/异常/无 root 一律返回 null（调用方按「不知道原输入法」降级处理）。
     */
    private fun currentDefault(): String? {
        if (!RootInput.available) return null
        return runCatching {
            val p = ProcessBuilder("su", "-c", "settings get secure default_input_method")
                .redirectErrorStream(false).start()
            val out = InputStreamReader(p.inputStream, Charsets.UTF_8).readText().trim()
            p.waitFor(3, TimeUnit.SECONDS)
            out.takeIf { it.isNotEmpty() && it != "null" }
        }.onFailure { Log.w(TAG, "读当前输入法失败：${it.message}") }.getOrNull()
    }

    /** 远程会话开始：记住原输入法 → 启用并切到通道输入法；没 root / 已是本通道则不动 */
    fun activate(ctx: Context) { queue.execute { activateInner(ctx) } }

    private fun activateInner(ctx: Context) {
        val cur = currentDefault() ?: return
        val own = ownImeId(ctx)
        if (cur == own) return   // 已经是我们的通道（上次会话残留）→ 别覆盖已存的还原点
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SAVED_IME, cur).apply()
        // 先 enable 再设默认：没 enable 过的输入法 settings put 不生效
        RootInput.run("ime enable $own")
        RootInput.run("settings put secure default_input_method $own")
        Log.i(TAG, "远程会话开始：输入法切到通道（原=$cur）")
    }

    /** 远程会话结束：换回会话前的原输入法；没存到还原点（没 root/读取失败）则不动 */
    fun restore(ctx: Context) { queue.execute { restoreInner(ctx) } }

    private fun restoreInner(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_SAVED_IME, null) ?: return
        if (RootInput.available) {
            RootInput.run("settings put secure default_input_method $saved")
            Log.i(TAG, "远程会话结束：输入法还原为 $saved")
        }
        // 无论是否还原成功都清掉还原点：下次会话重新记，避免把旧值反复写回
        sp.edit().remove(KEY_SAVED_IME).apply()
    }
}
