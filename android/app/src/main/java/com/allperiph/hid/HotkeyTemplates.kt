package com.allperiph.hid

/**
 * 快捷键模板（v1.27）：按「使用场景」预置一组组合键，一键铺到触控板页的快捷键条上。
 *
 * 覆盖 10 类高频场景：办公 / AI 对话 / 编程 / 剪辑 / 演示 / 设计 / 浏览器 /
 * 聊天 / 终端 / 表格。usage code 均为 USB HID Keyboard Page (0x07) 标准值，
 * PC 端免驱识别。
 *
 * 每套模板带一个 [Template.color] 主题色：设置页列表里做色条，套用后整条快捷键
 * 也换成同色系 —— 一眼能看出"现在用的是哪一套"，同时避免十几个 chip 一片灰。
 *
 * 模板只是"起点"：套用后仍可在快捷键条上长按逐条改（改动后即视为自定义，颜色回落）。
 */
object HotkeyTemplates {

    data class Template(
        val key: String,
        val name: String,
        val desc: String,
        val color: Int,
        val combos: List<HidKeys.Combo>,
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

    val ALL: List<Template> = listOf(
        Template(
            key = "office",
            name = "办公通用",
            desc = "复制 / 粘贴 / 撤销 / 保存 / 查找",
            color = 0xFF3482FF.toInt(),
            combos = listOf(
                c("复制", HidKeys.MOD_CTRL, 0x06),
                c("粘贴", HidKeys.MOD_CTRL, 0x19),
                c("剪切", HidKeys.MOD_CTRL, 0x1B),
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("重做", HidKeys.MOD_CTRL, 0x1C),
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("全选", HidKeys.MOD_CTRL, 0x04),
                c("查找", HidKeys.MOD_CTRL, 0x09),
            ),
        ),
        Template(
            key = "ai",
            name = "AI 对话",
            desc = "发送 / 换行 / 复制回答 / 重开对话 / 停止生成",
            color = 0xFF7C5CFF.toInt(),
            combos = listOf(
                c("发送", 0, KEY_ENTER),
                c("换行", HidKeys.MOD_SHIFT, KEY_ENTER),
                c("复制回答", HidKeys.MOD_CTRL, 0x06),
                c("重新生成", HidKeys.MOD_CTRL, 0x15),          // Ctrl+R
                c("新对话", HidKeys.MOD_CTRL, 0x11),            // Ctrl+N
                c("停止", 0, KEY_ESC),
                c("清空输入", HidKeys.MOD_CTRL, 0x04),           // Ctrl+A → 再按退格清空
            ),
        ),
        Template(
            key = "code",
            name = "编程开发",
            desc = "运行 / 调试 / 终端 / 注释 / 格式化 / 补全",
            color = 0xFF00A870.toInt(),
            combos = listOf(
                c("运行", 0, 0x3E),                             // F5
                c("调试", HidKeys.MOD_CTRL, 0x3D),               // Ctrl+F4（先断点再 F5 亦可）
                c("终端", HidKeys.MOD_CTRL, 0x35),               // Ctrl+`
                c("注释", HidKeys.MOD_CTRL, 0x38),               // Ctrl+/
                c("格式化", HidKeys.MOD_CTRL or HidKeys.MOD_ALT, 0x0F), // Ctrl+Alt+L
                c("查找", HidKeys.MOD_CTRL, 0x09),
                c("补全", HidKeys.MOD_CTRL, KEY_SPACE),
                c("跳转定义", 0, 0x42),                          // F9（VS 系默认）
            ),
        ),
        Template(
            key = "video",
            name = "视频剪辑",
            desc = "播放 / 切割 / 撤销 / 保存 / 导出",
            color = 0xFFFF7A00.toInt(),
            combos = listOf(
                c("播放", 0, KEY_SPACE),
                c("切割", HidKeys.MOD_CTRL, 0x14),               // Ctrl+K
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("保存", HidKeys.MOD_CTRL, 0x16),
                c("导出", HidKeys.MOD_CTRL, 0x10),               // Ctrl+M
                c("入点", 0, 0x0C),                              // I
                c("出点", 0, 0x12),                              // O
                c("整段选择", HidKeys.MOD_CTRL, 0x04),
            ),
        ),
        Template(
            key = "slide",
            name = "演示放映",
            desc = "开始放映 / 翻页 / 黑屏 / 结束",
            color = 0xFFE0457B.toInt(),
            combos = listOf(
                c("放映", 0, 0x3E),                              // F5
                c("下一页", 0, KEY_RIGHT),
                c("上一页", 0, KEY_LEFT),
                c("黑屏", 0, 0x05),                              // B
                c("白屏", 0, 0x1A),                              // W
                c("激光笔", HidKeys.MOD_CTRL, 0x0F),             // Ctrl+L
                c("结束", 0, KEY_ESC),
            ),
        ),
        Template(
            key = "design",
            name = "设计制图",
            desc = "新建图层 / 画笔 / 移动 / 缩放 / 复制图层 / 导出",
            color = 0xFF12B0C8.toInt(),
            combos = listOf(
                c("新建图层", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x11), // Ctrl+Shift+N
                c("复制图层", HidKeys.MOD_CTRL, 0x0D),                       // Ctrl+J
                c("画笔", 0, 0x05),                                          // B
                c("移动", 0, 0x19),                                          // V
                c("缩放", 0, 0x1D),                                          // Z
                c("撤销", HidKeys.MOD_CTRL, 0x1D),
                c("导出", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x08),      // Ctrl+Shift+E
            ),
        ),
        Template(
            key = "browser",
            name = "浏览网页",
            desc = "新标签 / 关标签 / 刷新 / 地址栏 / 前进后退 / 控制台",
            color = 0xFF3B6FF5.toInt(),
            combos = listOf(
                c("新标签", HidKeys.MOD_CTRL, 0x17),             // Ctrl+T
                c("关标签", HidKeys.MOD_CTRL, 0x1A),             // Ctrl+W
                c("刷新", HidKeys.MOD_CTRL, 0x15),               // Ctrl+R
                c("地址栏", HidKeys.MOD_CTRL, 0x0F),             // Ctrl+L
                c("后退", HidKeys.MOD_ALT, KEY_LEFT),
                c("前进", HidKeys.MOD_ALT, KEY_RIGHT),
                c("控制台", 0, 0x45),                            // F12
            ),
        ),
        Template(
            key = "chat",
            name = "聊天社交",
            desc = "发送 / 换行 / 截图 / 搜索 / 关闭 / 回到底部",
            color = 0xFF00B96B.toInt(),
            combos = listOf(
                c("发送", 0, KEY_ENTER),
                c("换行", HidKeys.MOD_SHIFT, KEY_ENTER),
                c("截屏", HidKeys.MOD_ALT, 0x04),                // Alt+A
                c("搜索", HidKeys.MOD_CTRL, 0x09),               // Ctrl+F
                c("关闭", HidKeys.MOD_CTRL, 0x1A),               // Ctrl+W
                c("回到底部", HidKeys.MOD_CTRL, KEY_DOWN),
            ),
        ),
        Template(
            key = "term",
            name = "终端运维",
            desc = "中断 / 退出 / 清屏 / 补全 / 历史 / 上下条",
            color = 0xFF5B6470.toInt(),
            combos = listOf(
                c("中断", HidKeys.MOD_CTRL, 0x06),               // Ctrl+C
                c("退出", HidKeys.MOD_CTRL, 0x07),               // Ctrl+D
                c("清屏", HidKeys.MOD_CTRL, 0x0F),               // Ctrl+L
                c("补全", 0, KEY_TAB),
                c("历史", HidKeys.MOD_CTRL, 0x15),               // Ctrl+R
                c("上一条", 0, KEY_UP),
                c("下一条", 0, KEY_DOWN),
            ),
        ),
        Template(
            key = "sheet",
            name = "表格数据",
            desc = "编辑单元格 / 筛选 / 求和 / 插行 / 跳边缘 / 换行",
            color = 0xFF1E9E5A.toInt(),
            combos = listOf(
                c("编辑单元格", 0, 0x3B),                        // F2
                c("筛选", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x0F), // Ctrl+Shift+L
                c("自动求和", HidKeys.MOD_ALT, 0x2E),            // Alt+=
                c("插入行", HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x2E),
                c("跳到边缘", HidKeys.MOD_CTRL, KEY_RIGHT),
                c("单元格内换行", HidKeys.MOD_ALT, KEY_ENTER),
                c("保存", HidKeys.MOD_CTRL, 0x16),
            ),
        ),
        Template(
            key = "tv",
            name = "TV 遥控",
            desc = "方向 / OK / 返回 / 主页（TV 与 PC 通用）",
            color = 0xFFE0457B.toInt(),
            combos = listOf(
                c("上", 0, KEY_UP),
                c("下", 0, KEY_DOWN),
                c("左", 0, KEY_LEFT),
                c("右", 0, KEY_RIGHT),
                c("OK", 0, KEY_ENTER),
                c("返回", 0, KEY_ESC),
                c("主页", 0, 0x4A),
            ),
        ),
    )

    fun byKey(key: String?): Template? = ALL.firstOrNull { it.key == key }

    /**
     * 模板色在当前主题下的可用值（v1.31）。
     * 色值是硬编码 ARGB，深色背景上墨绿 `#1E9E5A` / 石墨 `#5B6470` 这类偏暗的
     * 做标题和芯片文字会糊在背景里 —— 深色下统一向白提亮并抬最低亮度。
     */
    fun tint(ctx: android.content.Context, color: Int): Int {
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!night) return color
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        fun lift(v: Int): Int = (v + (255 - v) * 0.45f).toInt().coerceIn(150, 255)
        return (0xFF shl 24) or (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
    }
}
