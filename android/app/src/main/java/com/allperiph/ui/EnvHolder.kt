package com.allperiph.ui

/**
 * 环境探测结果（root / 链路速度 / UDC / 电池白名单 / 通知权限）的**唯一存放点**。
 *
 * ## 为什么要有它
 * 旧实现里这份数据只活在 MainActivity 的 `lastEnv` 字段里，状态页 Fragment 得靠宿主接口
 * 才能拿到 —— 于是「状态页」没法脱离 Activity 渲染。Compose 化的状态页要能在任何宿主里
 * 出现（现在的新外壳 ApShellActivity 之后），数据就必须有一个与 Activity 无关的落脚点。
 *
 * ApShellActivity 仍然负责**发起探测**（探测要 root、要 IO，放后台线程更合适），
 * 拿到结果写进这里；谁要显示就自己读 —— 读到的永远是同一份，不会出现两个版本。
 */
object EnvHolder {

    /** 最近一次探测结果；null = 还没探测完（此时界面不置灰，避免刚进页面闪一下） */
    @Volatile
    var env: EnvChecks.Env? = null
}
