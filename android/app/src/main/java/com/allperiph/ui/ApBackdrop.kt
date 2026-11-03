package com.allperiph.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * 全局背景（v1.31）：预设底色 / 自定义背景图的选择与读写。
 *
 * 只负责**存什么**；怎么铺到视图上是 [Backdrop] 的事（那里做采样解码与优先级判断）。
 * 优先级：**自定义图片 > 预设底色 > 布局自带的资源底色**（[Bg.color] = 0 的 `follow` 档）。
 */
object ApBackdrop {

    /**
     * 背景偏好的**变更计数**（成宇 2026-11-04「背景图选择后没反映，要重启」）。
     *
     * 之前 `rememberApBackdrop` 里读偏好用的是不带 key 的 `remember{}` —— 只在首次组合
     * 读一次；设置页改完偏好，主壳那一层**根本不会重新取图**，只好靠 `onRecreate()`
     * 把整个 Activity 重启一遍才看得见（体感就是「要重启」）。
     *
     * 这里给一个 Compose 可观察的版本号：任何一次写入都 ++，`rememberApBackdrop`
     * 以它为 key 重新读偏好与重新解码 —— 选图 / 换底色 / 开关动态**当场生效**，
     * 不需要重启，也不再需要 `onRecreate()`。
     */
    private val revState = mutableIntStateOf(0)

    /** 只读给 Compose 用；写入只能走下面的 set*（那里会 [bump]） */
    internal val rev: Int get() = revState.intValue

    private fun bump() { revState.intValue += 1 }

    /** 背景预设；[Bg.color] = 0 表示「跟随主题」（用布局自带的资源底色） */
    data class Bg(val id: String, val name: String, val color: Int)

    val BG_PRESETS: List<Bg> = listOf(
        Bg("follow", "跟随主题", 0),
        Bg("cloud", "云白", 0xFFF2F3F5.toInt()),
        Bg("warm", "暖沙", 0xFFF6F0E6.toInt()),
        Bg("mist", "雾青", 0xFFEAF1F4.toInt()),
        Bg("graphite", "石墨", 0xFF17181A.toInt()),
        Bg("deep", "深空", 0xFF0C0F14.toInt()),
    )

    fun bgId(ctx: Context): String =
        ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.KEY_BG, "follow").orEmpty()

    fun setBg(ctx: Context, id: String) {
        ApAppearancePrefs.of(ctx).edit().putString(ApAppearancePrefs.KEY_BG, id).apply()
        bump()
    }

    /**
 * 「动态背景」（2026-11-03）：把已选背景做**极缓慢**的放大 + 平移，让静态图/纯色底
 * 看起来在动。默认关闭 —— 它每帧都要重绘整块背景，纯色底在老机器上是实打实的开销，
 * 所以由用户显式打开（设置页「主题与外观 → 背景」里的开关）。
 *
 * 关掉后立刻恢复静止（[Backdrop.apply] 会把缩放与位移复位），不会留在半路上。
 */
fun bgAnim(ctx: Context): Boolean =
    ApAppearancePrefs.of(ctx).getBoolean(ApAppearancePrefs.KEY_BG_ANIM, false)

fun setBgAnim(ctx: Context, on: Boolean) {
    ApAppearancePrefs.of(ctx).edit().putBoolean(ApAppearancePrefs.KEY_BG_ANIM, on).apply()
    bump()
}

/** 自定义背景图（相册选来的 URI）；设了图就优先于纯色底 */
    fun bgImage(ctx: Context): String? =
        ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.KEY_BG_IMAGE, null)

    fun setBgImage(ctx: Context, uri: String?) {
        ApAppearancePrefs.of(ctx).edit().apply {
            if (uri.isNullOrBlank()) remove(ApAppearancePrefs.KEY_BG_IMAGE)
            else putString(ApAppearancePrefs.KEY_BG_IMAGE, uri)
        }.apply()
        // 换了图：解码缓存里那张立刻作废，否则同 URI 重选会拿到旧位图
        BackdropDecode.invalidate()
        bump()
    }
}
