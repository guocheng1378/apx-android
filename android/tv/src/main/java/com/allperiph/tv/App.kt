package com.allperiph.tv

import android.app.Application
import com.allperiph.shared.inject.KeepAlive

/**
 * TV 应用入口：在进程启动早期把平台特定的被控服务控制器注入共享 [KeepAlive]。
 *
 * 必须在这里做——保活闹钟的杀手锏就是进程被杀后由系统重新拉起一个全新进程来投递
 * 闹钟广播；新进程里 Application.onCreate 是 BroadcastReceiver.onReceive 之前唯一
 * 跑得到的平台代码，若不在此注入 controller，[KeepAlive.onAlarm] 会拿到 null 而无法
 * 拉起 TvServerService，保活闭环就断了。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // TvServerService companion 实现了 ServiceController；交给共享 KeepAlive 使用。
        KeepAlive.controller = TvServerService
    }
}
