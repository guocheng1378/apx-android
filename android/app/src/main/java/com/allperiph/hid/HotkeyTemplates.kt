package com.allperiph.hid

import com.allperiph.R

/**
 * 快捷键模板（v1.27）：按「使用场景」预置一组组合键，一键铺到触控板页的快捷键条上。
 *
 * 覆盖 10 类高频场景：办公 / AI 对话 / 编程 / 剪辑 / 演示 / 设计 / 浏览器 /
 * 聊天 / 终端 / 表格。usage code 均为 USB HID Keyboard Page (0x07) 标准值，
 * PC 端免驱识别。
 *
 * 每套模板带一个主题色：设置页列表里做色条，套用后整条快捷键
 * 也换成同色系 —— 一眼能看出"现在用的是哪一套"，同时避免十几个 chip 一片灰。
 * 内置模板的颜色是**颜色资源**（`R.color.hotkey_tpl_*`，见 res/values/colors.xml），
 * 深浅色两套取值由 values / values-night 决定，取色走 [resolveColor]。
 *
 * 模板只是"起点"：套用后仍可在快捷键条上长按逐条改（改动后即视为自定义，颜色回落）。
 */
object HotkeyTemplates {

    data class Template(
        val key: String,
        val name: String,
        val desc: String,
        /** 自定义模板的色值（ARGB 字面量，随模板一起持久化）；内置模板为 0，用 [colorRes] */
        val color: Int,
        val combos: List<HidKeys.Combo>,
        /**
         * 内置模板的颜色**资源 id**（`R.color.hotkey_tpl_*`）—— 走项目既有的颜色令牌，
         * values-night 覆盖同一份资源，深色底不必再靠代码提亮。
         * 0 = 没有对应资源（自定义模板），此时用 [color]。
         */
        val colorRes: Int = 0,
    )

    // 常用 usage 速记（见 HidKeys.KEY_CHOICES）
    private const val KEY_ENTER = 0x28
    private const val KEY_ESC = 0x29
    private const val KEY_TAB = 0x2B
    private const val KEY_SPACE = 0x2C
    private const val KEY_LEFT = 0x50
    private const val KEY_RIGHT = 0x4F
    private const val KEY_UP = 0x52
    private const val KEY_DOWN = 0x51

    private fun c(label: String, mod: Int, usage: Int) = HidKeys.Combo(label, mod, usage)

    // —————————————————————————— 模板清单（2026-11-12 扩到 12 枚/套） ——————————————————————————
    // 用户原话「模版按钮都太少了，每个模版扩充到 12 个」「默认模版也优化下布局两个字最好，
    // 12 个正好 4 排」：每套 12 枚、两字标签为主，按触控板竖屏网格的 3 列口径恰好 4 排铺满；
    // usage 全部取自 [HidKeys.KEY_CHOICES] 覆盖的键位（字母 / 数字 / 功能键 / 方向 / 编辑键），
    // 不会出现「模板里选了但编辑器下拉里没有」的错位。存量存档不动 —— 套用模板才落新键位。

    val ALL: List<Template> = listOf(
        Template(
            key = "office",
            name = "办公通用",
            desc = "复制 / 粘贴 / 撤销 / 保存 / 查找 / 替换 / 切窗",
            color = 0,
            colorRes = R.color.hotkey_tpl_office,
            combos = listOf(
                c("复制", HidKeys.MOD_CTRL, 0x06),
                c("粘贴", HidKeys.MOD_CTRL, 0x19),
                c("剪切", HidKeys.MOD_CTRL, 0x1B),
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("重做", HidKeys.MOD_CTRL, 0x1C),
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("全选", HidKeys.MOD_CTRL, 0x04),
                c("查找", HidKeys.MOD_CTRL, 0x09),
                c("替换", HidKeys.MOD_CTRL, 0x0B),
                c("关闭", HidKeys.MOD_CTRL, 0x1A),
                c("切窗", HidKeys.MOD_ALT, 0x2B),
                c("刷新", 0, 0x3E),                             // F5
            ),
        ),
        Template(
            key = "ai",
            name = "AI 对话",
            desc = "发送 / 换行 / 停止 / 新对话 / 复制 / 截屏 / 搜索",
            color = 0,
            colorRes = R.color.hotkey_tpl_ai,
            combos = listOf(
                c("发送", 0, KEY_ENTER),
                c("换行", HidKeys.MOD_SHIFT, KEY_ENTER),
                c("停止", 0, KEY_ESC),
                c("新对话", HidKeys.MOD_CTRL, 0x11),            // Ctrl+N
                c("重新生成", HidKeys.MOD_CTRL, 0x15),          // Ctrl+R
                c("复制", HidKeys.MOD_CTRL, 0x06),
                c("粘贴", HidKeys.MOD_CTRL, 0x19),
                c("清空", HidKeys.MOD_CTRL, 0x04),              // Ctrl+A（再按退格清空输入）
                c("截屏", HidKeys.MOD_WIN or HidKeys.MOD_SHIFT, 0x16), // Win+Shift+S
                c("搜索", HidKeys.MOD_CTRL, 0x09),
                c("切窗", HidKeys.MOD_ALT, 0x2B),
                c("关闭", HidKeys.MOD_CTRL, 0x1A),
            ),
        ),
        Template(
            key = "code",
            name = "编程开发",
            desc = "运行 / 调试 / 终端 / 注释 / 格式化 / 补全 / 跳转",
            color = 0,
            colorRes = R.color.hotkey_tpl_code,
            combos = listOf(
                c("运行", 0, 0x3E),                             // F5
                c("调试", HidKeys.MOD_CTRL, 0x3D),               // Ctrl+F4
                c("终端", HidKeys.MOD_CTRL, 0x35),               // Ctrl+`
                c("注释", HidKeys.MOD_CTRL, 0x38),               // Ctrl+/
                c("格式化", HidKeys.MOD_CTRL or HidKeys.MOD_ALT, 0x0F), // Ctrl+Alt+L
                c("查找", HidKeys.MOD_CTRL, 0x09),
                c("补全", HidKeys.MOD_CTRL, KEY_SPACE),
                c("跳转", 0, 0x42),                             // F9（VS 系跳转定义）
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("关闭", HidKeys.MOD_CTRL, 0x1A),
                c("切窗", HidKeys.MOD_ALT, 0x2B),
            ),
        ),
        Template(
            key = "video",
            name = "视频剪辑",
            desc = "播放 / 切割 / 撤销 / 导出 / 入点 / 出点 / 快进快退",
            color = 0,
            colorRes = R.color.hotkey_tpl_video,
            combos = listOf(
                c("播放", 0, KEY_SPACE),
                c("切割", HidKeys.MOD_CTRL, 0x14),               // Ctrl+K
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("导出", HidKeys.MOD_CTRL, 0x10),               // Ctrl+M
                c("入点", 0, 0x0C),                             // I
                c("出点", 0, 0x12),                             // O
                c("全选", HidKeys.MOD_CTRL, 0x04),
                c("快进", 0, KEY_RIGHT),
                c("快退", 0, KEY_LEFT),
                c("放大", 0, 0x2E),                             // =（时间线放大）
                c("缩小", 0, 0x2D),                             // -（时间线缩小）
            ),
        ),
        Template(
            key = "slide",
            name = "演示放映",
            desc = "放映 / 翻页 / 黑白屏 / 首末页 / 激光笔 / 结束",
            color = 0,
            colorRes = R.color.hotkey_tpl_slide,
            combos = listOf(
                c("放映", 0, 0x3E),                             // F5 从头放映
                c("本页", HidKeys.MOD_SHIFT, 0x3E),              // Shift+F5 从当前页
                c("上页", 0, KEY_LEFT),
                c("下页", 0, KEY_RIGHT),
                c("黑屏", 0, 0x05),                             // B
                c("白屏", 0, 0x1A),                             // W
                c("首页", 0, 0x4A),                             // Home
                c("末页", 0, 0x4D),                             // End
                c("激光笔", HidKeys.MOD_CTRL, 0x0F),             // Ctrl+L
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("结束", 0, KEY_ESC),
            ),
        ),
        Template(
            key = "design",
            name = "设计制图",
            desc = "图层 / 画笔 / 取色 / 文字 / 填充 / 复制 / 导出",
            color = 0,
            colorRes = R.color.hotkey_tpl_design,
            combos = listOf(
                c("图层", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x11), // Ctrl+Shift+N 新建图层
                c("复制", HidKeys.MOD_CTRL, 0x0D),              // Ctrl+J 复制图层
                c("画笔", 0, 0x05),                             // B
                c("移动", 0, 0x19),                             // V
                c("缩放", 0, 0x1D),                             // Z
                c("取色", 0, 0x0C),                             // I
                c("文字", 0, 0x1A),                             // T
                c("填充", HidKeys.MOD_ALT, 0x2A),                // Alt+Backspace 前景填充
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("导出", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x08), // Ctrl+Shift+E
                c("新建", HidKeys.MOD_CTRL, 0x11),              // Ctrl+N
            ),
        ),
        Template(
            key = "browser",
            name = "浏览网页",
            desc = "新标签 / 关标签 / 刷新 / 地址 / 前进后退 / 控制台",
            color = 0,
            colorRes = R.color.hotkey_tpl_browser,
            combos = listOf(
                c("新标签", HidKeys.MOD_CTRL, 0x17),             // Ctrl+T
                c("关标签", HidKeys.MOD_CTRL, 0x1A),             // Ctrl+W
                c("刷新", HidKeys.MOD_CTRL, 0x15),               // Ctrl+R
                c("地址", HidKeys.MOD_CTRL, 0x0F),               // Ctrl+L
                c("后退", HidKeys.MOD_ALT, KEY_LEFT),
                c("前进", HidKeys.MOD_ALT, KEY_RIGHT),
                c("控制台", 0, 0x45),                            // F12
                c("收藏", HidKeys.MOD_CTRL, 0x07),               // Ctrl+D
                c("历史", HidKeys.MOD_CTRL, 0x0B),               // Ctrl+H
                c("搜索", HidKeys.MOD_CTRL, 0x09),               // Ctrl+F
                c("无痕", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x11), // Ctrl+Shift+N
                c("撤销", HidKeys.MOD_CTRL, 0x1D),              // 误关标签找回
            ),
        ),
        Template(
            key = "chat",
            name = "聊天社交",
            desc = "发送 / 换行 / 截屏 / 搜索 / 新聊 / 回到底部",
            color = 0,
            colorRes = R.color.hotkey_tpl_chat,
            combos = listOf(
                c("发送", 0, KEY_ENTER),
                c("换行", HidKeys.MOD_SHIFT, KEY_ENTER),
                c("截屏", HidKeys.MOD_ALT, 0x04),                // Alt+A
                c("搜索", HidKeys.MOD_CTRL, 0x09),               // Ctrl+F
                c("关闭", HidKeys.MOD_CTRL, 0x1A),               // Ctrl+W
                c("回底", HidKeys.MOD_CTRL, KEY_DOWN),
                c("粘贴", HidKeys.MOD_CTRL, 0x19),
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("新聊", HidKeys.MOD_CTRL, 0x11),              // Ctrl+N
                c("切窗", HidKeys.MOD_ALT, 0x2B),
                c("锁屏", HidKeys.MOD_WIN, 0x0F),               // Win+L
                c("复制", HidKeys.MOD_CTRL, 0x06),
            ),
        ),
        Template(
            key = "term",
            name = "终端运维",
            desc = "中断 / 退出 / 清屏 / 补全 / 历史 / 行首行尾",
            color = 0,
            colorRes = R.color.hotkey_tpl_term,
            combos = listOf(
                c("中断", HidKeys.MOD_CTRL, 0x06),               // Ctrl+C
                c("退出", HidKeys.MOD_CTRL, 0x07),               // Ctrl+D
                c("清屏", HidKeys.MOD_CTRL, 0x0F),               // Ctrl+L
                c("补全", 0, KEY_TAB),
                c("历史", HidKeys.MOD_CTRL, 0x15),               // Ctrl+R
                c("上一条", 0, KEY_UP),
                c("下一条", 0, KEY_DOWN),
                c("行首", HidKeys.MOD_CTRL, 0x04),               // Ctrl+A
                c("行尾", HidKeys.MOD_CTRL, 0x08),               // Ctrl+E
                c("挂起", HidKeys.MOD_CTRL, 0x1D),               // Ctrl+Z
                c("查找", HidKeys.MOD_CTRL, 0x09),               // Ctrl+F
                c("新窗", HidKeys.MOD_CTRL, 0x11),              // Ctrl+N
            ),
        ),
        Template(
            key = "sheet",
            name = "表格数据",
            desc = "编辑 / 筛选 / 求和 / 插行 / 换行 / 加粗斜体",
            color = 0,
            colorRes = R.color.hotkey_tpl_sheet,
            combos = listOf(
                c("编辑", 0, 0x3B),                             // F2
                c("筛选", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x0F), // Ctrl+Shift+L
                c("求和", HidKeys.MOD_ALT, 0x2E),                // Alt+=
                c("插行", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x2E),
                c("跳边", HidKeys.MOD_CTRL, KEY_RIGHT),
                c("换行", HidKeys.MOD_ALT, KEY_ENTER),
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("加粗", HidKeys.MOD_CTRL, 0x05),               // Ctrl+B
                c("斜体", HidKeys.MOD_CTRL, 0x0C),               // Ctrl+I
                c("下划", HidKeys.MOD_CTRL, 0x18),               // Ctrl+U
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("删行", HidKeys.MOD_CTRL, 0x2D),               // Ctrl+- 删除整行/行
            ),
        ),
        Template(
            key = "tv",
            name = "TV 遥控",
            desc = "方向 / OK / 返回 / 主页 / 菜单 / 电源（TV 与 PC 通用）",
            color = 0,
            colorRes = R.color.hotkey_tpl_tv,
            combos = listOf(
                c("上", 0, KEY_UP),
                c("下", 0, KEY_DOWN),
                c("左", 0, KEY_LEFT),
                c("右", 0, KEY_RIGHT),
                c("OK", 0, KEY_ENTER),
                c("返回", 0, KEY_ESC),
                c("主页", 0, 0x4A),
                // ★ 电源/关机：usage 0x66 = 键盘 Power，被控端 HID_MAP 映射成 KEYCODE_POWER，
                //   evdev / root 通道下就是一颗真电源键（待机 / 唤醒），与实体遥控一致。
                c("电源", 0, 0x66),
                // 0x65 = 键盘 Application，被控端映射 KEYCODE_MENU(82) —— 电视上就是「菜单」键。
                c("菜单", 0, 0x65),
                c("频道+", 0, 0x4B),                            // PageUp：多数 TV 应用当「上一频道」
                c("频道-", 0, 0x4E),                            // PageDown：当「下一频道」
                c("暂停", 0, KEY_SPACE),
            ),
        ),
    )

    fun byKey(key: String?): Template? = ALL.firstOrNull { it.key == key }

    /**
     * 模板色取值：**内置模板走颜色资源**（`R.color.hotkey_tpl_*`，随主题/深浅色解析），
     * 自定义模板用随模板持久化的 ARGB 字面量。
     *
     * 取色只有这一条路径 —— 直接读 [Template.color] 会拿到内置模板的 0。
     */
    fun resolveColor(ctx: android.content.Context, t: Template): Int =
        if (t.colorRes != 0) ctx.getColor(t.colorRes) else t.color

    /**
     * 模板色在当前主题下的可用值（v1.31）。
     *
     * 只提亮「深色背景下读不清」的那些色（自定义模板挑的暗色，墨绿 `#1E9E5A` /
     * 石墨 `#5B6470` 这类）；鲜艳色保持原样 —— 此前是**无差别**向白提 45%，
     * 整排色条在深色下被漂白成一片。
     * 内置模板的两档暗色另有 values-night 覆盖（`hotkey_tpl_term` / `hotkey_tpl_sheet`），
     * 解析出来已经不暗，这里不会再被动到。
     */
    fun tint(ctx: android.content.Context, color: Int): Int {
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!night) return color
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // 最亮通道够高 = 这色在深底上本来就读得清，保持原样
        if (maxOf(r, g, b) >= 170) return color
        fun lift(v: Int): Int = (v + (255 - v) * 0.45f).toInt().coerceIn(150, 255)
        return (0xFF shl 24) or (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
    }
}
