package com.allperiph.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.allperiph.shared.util.Log

/**
 * 开机 / 应用更新后自动拉起被控服务。
 *
 * 电视与盒子常年插电挂在墙上，视为**常驻家电**：开机即自启，不要求用户此前点过
 * 「开始接受控制」——否则用户每次断电重开都要翻出遥控器点一下 App 才能被手机发现。
 *
 * 唯一无法触发的情况是系统对「已被强停（force-stop）的应用」不投递广播，这是 Android
 * 的限制；用户手动打开一次 App 即恢复（[MainActivity] 启动时也会拉起服务）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act != Intent.ACTION_BOOT_COMPLETED && act != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // start() 内部已把 enabled 记为 true、清掉 user_stopped，并 try-catch 拉起服务，
        // 所以主界面开关状态与实际运行状态保持一致。
        TvServerService.start(context)
        Log.i("开机自启：已拉起 TV 被控服务")
    }
}