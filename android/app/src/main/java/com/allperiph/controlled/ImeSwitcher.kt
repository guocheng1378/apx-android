package com.allperiph.controlled

import android.content.Context
import com.allperiph.shared.input.RootInput
import com.allperiph.shared.util.Log
import com.allperiph.wireless.ControlTarget
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
     * 一次性 root 回读：执行 [cmd] 取回完整输出。读回必须脱离 fire-and-forget 的
     * 常驻 shell（输出没人消费），另开 su 进程 —— 每次会话只有连入/断开各几次。
     * 防卡死双保险（2026-11-14 审查）：① su 挂起时 readText 会**永远阻塞**（waitFor
     * 排在 read 之后等于没设），起 watchdog 线程到点 destroy 进程、读端随流关闭脱困；
     * ② stderr 必须有消费线程，否则 su 写满管道会反过来堵死子进程。
     * 超时/异常/无 root 一律返回 null。
     */
    private fun suRead(cmd: String): String? {
        if (!RootInput.available) return null
        return runCatching {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(false).start()
            Thread({
                runCatching {
                    p.errorStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) Log.w(TAG, "su stderr：$line")
                    }
                }
            }, "apx-ime-stderr").apply { isDaemon = true }.start()
            Thread({
                runCatching { Thread.sleep(3_000); p.destroy() }
            }, "apx-ime-watchdog").apply { isDaemon = true }.start()
            val out = InputStreamReader(p.inputStream, Charsets.UTF_8).readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            runCatching { p.destroy() }
            out
        }.onFailure { Log.w(TAG, "root 回读失败（$cmd）：${it.message}") }.getOrNull()
    }

    /** 读当前默认输入法；空/`null`/失败返回 null（调用方按「不知道原输入法」降级处理） */
    private fun currentDefault(): String? =
        suRead("settings get secure default_input_method")
            ?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * 通道输入法是否已被系统注册（在 `ime list -a` 的完整清单里）。
     * 没注册就**绝不能**碰 default_input_method —— 真机实锤（用户 HyperOS 被控机，
     * v95）：`ime enable` 报 Unknown input method，系统清单里没有它；此时照旧硬写
     * settings 只会把系统输入法指到一个不存在的 IME 上，本机键盘状态全乱。
     */
    private fun ownImeRegistered(own: String): Boolean {
        val list = suRead("ime list -a -s") ?: return true   // 读不到时按「注册了」处理，避免误伤
        return list.split(Regex("[\\r\\n\\s]+")).contains(own).also { ok ->
            if (!ok) Log.w(TAG, "通道输入法未注册（ime list 不含 $own），本次不换装 —— 系统没扫到它，重启一次被控机通常恢复")
        }
    }

    /**
     * 当前**已启用**输入法的完整 id 清单（`ime list -s` 只列 enabled 的）。
     * 自愈/还原兜底用：还原点丢了或指向已卸载的输入法时，从这份清单里挑替身。
     */
    private fun listEnabledImes(): List<String> =
        suRead("ime list -s")?.split(Regex("[\\r\\n\\s]+"))?.filter { it.contains('/') } ?: emptyList()

    /** 远程会话开始：记住原输入法 → 启用并切到通道输入法；没 root / 已是本通道则不动 */
    fun activate(ctx: Context) { queue.execute { activateInner(ctx) } }

    private fun activateInner(ctx: Context) {
        // root shell 是异步启动的（探测最多 ~3s）：对端抢在就绪前连入时不能静默跳过，
        // 等它就绪再换装 —— 跳过=这个会话远程打字全灭且毫无提示
        for (attempt in 0 until 8) {
            if (RootInput.available) break
            Thread.sleep(500)
        }
        val own = ownImeId(ctx)
        if (!ownImeRegistered(own)) {
            // v95 曾在 enable 失败后照写 settings（预检修复前的版本）：默认输入法可能
            // 已被指到这个不存在的 IME —— 趁这次会话把它指回还原点，救回本机键盘。
            // 2026-11-14 补：还原点为空（用户清过应用数据/重装，偏好没了而 settings
            // 还指着不存在的通道）时，从系统已启用输入法里挑一个非本通道的兜底——
            // 只认还原点的话，这台机器的键盘就永远救不回来。
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val saved = sp.getString(KEY_SAVED_IME, null)
            val cur = currentDefault()
            if (cur == own) {
                // 还原点优先，但**必须还在已启用清单里**（被卸载/禁用的还原点指回去等于
                // 重演 v95）——不在时与还原路径同口径改用现存替身。
                val enabled = listEnabledImes()
                val fallback = saved?.takeIf { it != own && (enabled.isEmpty() || enabled.contains(it)) }
                    ?: enabled.firstOrNull { it != own }
                if (fallback != null) {
                    RootInput.run("settings put secure default_input_method $fallback")
                    sp.edit().remove(KEY_SAVED_IME).apply()
                    Log.i(TAG, "默认输入法被指到未注册的通道（v95 遗留），已还原为 $fallback" +
                        (if (saved.isNullOrBlank()) "（还原点为空，从已启用输入法兜底挑选）" else ""))
                } else {
                    Log.w(TAG, "默认输入法指向未注册通道，但已启用输入法里找不到非本通道的候选，无法自愈")
                }
            }
            return
        }
        // 双向互控防护（2026-11-14）：本机自己正控制着别人时，绝不能再换装自己的
        // 输入法 —— 两台手机互控时双方都会走到 activate，把两台的键盘一起切进
        // 通道（通道还是零高度空壳），用户手里两台「键盘全坏了」。上面的未注册
        // 自愈分支保留执行：那是修 v95 遗留损害，不受本防护影响。
        if (ControlTarget.isControlling()) {
            Log.i(TAG, "本机正在控制其他设备（双向互控），跳过输入法换装")
            return
        }
        val cur = currentDefault() ?: return
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
        // root 通道不在（su 被杀/未授权）：**保留还原点**下次再试 —— 现在清掉等于把
        // 用户键盘永远留在无声通道上（本机的键盘就真没了）。发送成功才清。
        // 2026-11-14 补：还原目标已不在已启用清单（被卸载/被禁用）时改用现存替身，
        // 别把 default_input_method 指到又一个不存在的 IME 上重演 v95 的病。
        val enabled = if (RootInput.available) listEnabledImes() else emptyList()
        val target = if (enabled.isEmpty() || enabled.contains(saved)) saved
            else enabled.firstOrNull { it != ownImeId(ctx) } ?: return
        if (RootInput.available && RootInput.run("settings put secure default_input_method $target")) {
            Log.i(TAG, "远程会话结束：输入法还原为 $target" +
                (if (target != saved) "（原还原点 $saved 已不在已启用清单，改用现存替身）" else ""))
            sp.edit().remove(KEY_SAVED_IME).apply()
        }
    }
}
