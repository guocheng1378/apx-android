#!/usr/bin/env python3
"""repo-edit：批 U-6（悬浮球双开关 + 设置功能自定义纳管，成宇 2026-11-07）。

需求原文：「悬浮球菜单应该加上物理键盘全局转发和外接触控板直读的切换开关，
方便不用的时候关闭功能。最好是设置里面常用的功能都能自定义到悬浮球菜单。」

实现（锚点全部取自 master 权威全文 BallConfig / FloatingBallService / strings.xml）：
  · BallConfig 新增 kb_forward / pointer_read 两个 id → 进 ALL（设置页「悬浮球功能」
    勾选框遍历 ALL，新项自动进自定义列表）+ DEFAULT + 老用户 v5 补挂一次；
  · 球菜单点一下就地翻转、不收菜单；状态高亮复用连接模式主色实底；
  · 写点与设置页同一份 prefs（GlobalKeyForwarder / RootPointerBridge 自己的出口）；
  · 诚实闸门（issue #52 教训）：无障碍未启用 / 无 root 无指针设备时如实回退 + 提示，
    不显示假的「已开启」；
  · 图标 ic_apx_kb_fwd / ic_apx_pointer 已先行提交（26eba64e / 571c2b15），
    资源与引用同批落地，不重蹈批 U-5 引用悬空的红。
"""
import sys

BALL = "android/app/src/main/java/com/allperiph/ui/BallConfig.kt"
SVC = "android/app/src/main/java/com/allperiph/ui/FloatingBallService.kt"
STR = "android/app/src/main/res/values/strings.xml"

EDITS = [
    # ———————— BallConfig.kt ————————
    {
        "id": "U6-1 新增两 id",
        "file": BALL,
        "kind": "replace",
        "anchor": "    const val QUIT = \"quit\"",
        "new": (
            "    const val QUIT = \"quit\"\n"
            "\n"
            "    /**\n"
            "     * 物理键盘全局转发（无障碍，无需 root）—— 成宇 2026-11-07「悬浮球菜单加上\n"
            "     * 切换开关，方便不用的时候关闭」。开关类快捷项：点一下就地翻转，不开面板。\n"
            "     */\n"
            "    const val KB_FORWARD = \"kb_forward\"\n"
            "\n"
            "    /** 外接触控板 root 直读 —— 同上；写点与设置页同一份 prefs（RootPointerBridge.prefs） */\n"
            "    const val POINTER_READ = \"pointer_read\""
        ),
    },
    {
        "id": "U6-2 ALL 收编两开关",
        "file": BALL,
        "kind": "replace",
        "anchor": "        MODE_WIFI, MODE_BT, MODE_USB, CONSOLE, QUIT,",
        "new": "        MODE_WIFI, MODE_BT, MODE_USB, CONSOLE, KB_FORWARD, POINTER_READ, QUIT,",
    },
    {
        "id": "U6-3 DEFAULT 带上两开关",
        "file": BALL,
        "kind": "replace",
        "anchor": "    val DEFAULT: List<String> = listOf(TOUCHPAD, KEYBOARD, DEVICES, CLIPBOARD, FILES, QUIT)",
        "new": "    val DEFAULT: List<String> = listOf(TOUCHPAD, KEYBOARD, DEVICES, CLIPBOARD, FILES, KB_FORWARD, POINTER_READ, QUIT)",
    },
    {
        "id": "U6-4 labelRes",
        "file": BALL,
        "kind": "replace",
        "anchor": "        QUIT -> R.string.ui_ball_fn_quit",
        "new": (
            "        KB_FORWARD -> R.string.ui_ball_fn_kb_forward\n"
            "        POINTER_READ -> R.string.ui_main_label_root_pointer\n"
            "        QUIT -> R.string.ui_ball_fn_quit"
        ),
    },
    {
        "id": "U6-5 iconRes（资源已先行落地）",
        "file": BALL,
        "kind": "replace",
        "anchor": "        QUIT -> R.drawable.ic_apx_power",
        "new": (
            "        KB_FORWARD -> R.drawable.ic_apx_kb_fwd\n"
            "        POINTER_READ -> R.drawable.ic_apx_pointer\n"
            "        QUIT -> R.drawable.ic_apx_power"
        ),
    },
    {
        "id": "U6-6 isToggle 判定",
        "file": BALL,
        "kind": "replace",
        "anchor": "    /** 连接模式类快捷项对应的出口标识（[AgentController.TRANSPORTS] 同口径）；非模式类返回 null */",
        "new": (
            "    /** 开关类快捷项（球上点一下翻转状态、不开面板）—— 与 [transportOf] 互斥 */\n"
            "    fun isToggle(id: String): Boolean = id == KB_FORWARD || id == POINTER_READ\n"
            "\n"
            "    /** 连接模式类快捷项对应的出口标识（[AgentController.TRANSPORTS] 同口径）；非模式类返回 null */"
        ),
    },
    {
        "id": "U6-7 v5 补挂标记",
        "file": BALL,
        "kind": "replace",
        "anchor": "    private const val KEY_MIGRATED_V4 = \"migrated_v4\"",
        "new": (
            "    private const val KEY_MIGRATED_V4 = \"migrated_v4\"\n"
            "\n"
            "    /** 双开关项（全局转发 / root 直读）加入菜单后的补挂标记（同样只补一次） */\n"
            "    private const val KEY_MIGRATED_V5 = \"migrated_v5\""
        ),
    },
    {
        "id": "U6-8 v5 补挂逻辑（插在 v4 注释前）",
        "file": BALL,
        "kind": "replace",
        "anchor": "        // 摄像头项：同样只补一次 —— 老用户已存过勾选清单的话，不补就永远看不到这一项",
        "new": (
            "        // 双开关项：同样只补一次 —— 老用户已存过勾选清单的话，不补就永远看不到这两项\n"
            "        if (!p.getBoolean(KEY_MIGRATED_V5, false)) {\n"
            "            val cur = p.getString(KEY_ITEMS, null)\n"
            "            if (cur != null) {\n"
            "                val kept = cur.split(\",\").map { it.trim() }\n"
            "                val add = listOf(KB_FORWARD, POINTER_READ).filter { it !in kept }\n"
            "                if (add.isNotEmpty()) {\n"
            "                    p.edit().putString(KEY_ITEMS, (kept + add).joinToString(\",\")).apply()\n"
            "                }\n"
            "            }\n"
            "            p.edit().putBoolean(KEY_MIGRATED_V5, true).apply()\n"
            "        }\n"
            "\n"
            "        // 摄像头项：同样只补一次 —— 老用户已存过勾选清单的话，不补就永远看不到这一项"
        ),
    },
    # ———————— FloatingBallService.kt ————————
    {
        "id": "U6-9 import RootPointerBridge",
        "file": SVC,
        "kind": "replace",
        "anchor": "import com.allperiph.touchpad.TouchpadModule",
        "new": (
            "import com.allperiph.touchpad.RootPointerBridge\n"
            "import com.allperiph.touchpad.TouchpadModule"
        ),
    },
    {
        "id": "U6-10 applyFanState 纳入开关态",
        "file": SVC,
        "kind": "replace",
        "anchor": "        val on = kind != null && AgentController.isTransportEnabled(this, kind)",
        "new": (
            "        val on = (kind != null && AgentController.isTransportEnabled(this, kind)) ||\n"
            "            (BallConfig.isToggle(id) && fanToggleOn(id))"
        ),
    },
    {
        "id": "U6-11 onFanItem 分发到 onFanToggle",
        "file": SVC,
        "kind": "replace",
        "anchor": "            else -> BallConfig.transportOf(id)?.let { onMenuTransport(it) }",
        "new": (
            "            BallConfig.KB_FORWARD, BallConfig.POINTER_READ -> onFanToggle(id)\n"
            "            else -> BallConfig.transportOf(id)?.let { onMenuTransport(it) }"
        ),
    },
    {
        "id": "U6-12 三个新函数（插在 onMenuTransport 注释前）",
        "file": SVC,
        "kind": "replace",
        "anchor": "    /**\n     * 切换连接模式。与状态页 `StatusFragment.onTransportToggle` 同一套语义：",
        "new": (
            "    /** 双开关项的当前态：与设置页同一读点（各自的 prefs 出口），两处状态不会打架 */\n"
            "    private fun fanToggleOn(id: String): Boolean = when (id) {\n"
            "        BallConfig.KB_FORWARD -> GlobalKeyForwarder.isEnabled(GlobalKeyForwarder.prefs(this))\n"
            "        BallConfig.POINTER_READ -> RootPointerBridge.isEnabled(RootPointerBridge.prefs(this))\n"
            "        else -> false\n"
            "    }\n"
            "\n"
            "    /**\n"
            "     * 系统无障碍里本应用的转发服务是否已启用。\n"
            "     *\n"
            "     * 开关落了盘但无障碍服务没挂 = 按键根本不会送进 onKeyEvent —— 球上若显示「已开启」\n"
            "     * 就是假的（issue #52「界面状态必须与实际一致」的同款坑）。按类名 contains 判定，\n"
            "     * 不写死完整组件名，包名变化时也不至于静默误判。\n"
            "     */\n"
            "    private fun a11yForwardOn(): Boolean {\n"
            "        val s = runCatching {\n"
            "            Settings.Secure.getString(\n"
            "                contentResolver,\n"
            "                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,\n"
            "            )\n"
            "        }.getOrNull()\n"
            "        return s?.contains(\"ApxAccessibilityService\") == true\n"
            "    }\n"
            "\n"
            "    /**\n"
            "     * 双开关项（成宇 2026-11-07「悬浮球菜单加上物理键盘全局转发 / 外接触控板直读的\n"
            "     * 切换开关，方便不用的时候关闭」）。\n"
            "     *\n"
            "     * 语义与连接模式项一致：就地翻转、Toast 报态、**不收菜单** —— 两个开关相邻，\n"
            "     * 用户可能连着切。写点与设置页完全同一条 prefs；开不起来时（无障碍没启用 /\n"
            "     * 没 root、没指针设备）**如实回退并提示**，绝不让球上显示假的「已开启」。\n"
            "     */\n"
            "    private fun onFanToggle(id: String) {\n"
            "        val label = getString(BallConfig.labelRes(id))\n"
            "        val next = !fanToggleOn(id)\n"
            "        when (id) {\n"
            "            BallConfig.KB_FORWARD -> {\n"
            "                GlobalKeyForwarder.setEnabled(GlobalKeyForwarder.prefs(this), next)\n"
            "                if (next && !a11yForwardOn()) {\n"
            "                    GlobalKeyForwarder.setEnabled(GlobalKeyForwarder.prefs(this), false)\n"
            "                    Toast.makeText(this, getString(R.string.ui_ball_toggle_kb_a11y), Toast.LENGTH_SHORT).show()\n"
            "                    renderFanStates()\n"
            "                    return\n"
            "                }\n"
            "            }\n"
            "            BallConfig.POINTER_READ -> {\n"
            "                RootPointerBridge.setEnabled(this, RootPointerBridge.prefs(this), next)\n"
            "                if (next && !RootPointerBridge.active) {\n"
            "                    // 拿不到 root / 没接指针设备：start() 自己降级了，这里如实回退，不显示假开启\n"
            "                    RootPointerBridge.setEnabled(this, RootPointerBridge.prefs(this), false)\n"
            "                    Toast.makeText(this, getString(R.string.ui_ball_toggle_pointer_fail), Toast.LENGTH_SHORT).show()\n"
            "                    renderFanStates()\n"
            "                    return\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "        Toast.makeText(\n"
            "            this,\n"
            "            getString(if (next) R.string.ui_ball_menu_mode_on else R.string.ui_ball_menu_mode_off, label),\n"
            "            Toast.LENGTH_SHORT,\n"
            "        ).show()\n"
            "        renderFanStates()\n"
            "    }\n"
            "\n"
            "    /**\n     * 切换连接模式。与状态页 `StatusFragment.onTransportToggle` 同一套语义："
        ),
    },
    # ———————— strings.xml ————————
    {
        "id": "U6-13 三条新文案",
        "file": STR,
        "kind": "replace",
        "anchor": "    <string name=\"ui_main_label_root_pointer\">外接触控板直读（需 root）</string>",
        "new": (
            "    <string name=\"ui_main_label_root_pointer\">外接触控板直读（需 root）</string>\n"
            "    <string name=\"ui_ball_fn_kb_forward\">物理键盘全局转发</string>\n"
            "    <string name=\"ui_ball_toggle_kb_a11y\">全局转发需先在系统「无障碍」里启用本应用，已保持关闭</string>\n"
            "    <string name=\"ui_ball_toggle_pointer_fail\">直读未生效（缺 root 或未接指针设备），已保持关闭</string>"
        ),
    },
]


def main() -> int:
    files = {}
    for e in EDITS:
        files.setdefault(e["file"], []).append(e)

    pending = {}
    for path, edits in files.items():
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as ex:
            print(f"open failed {path}: {ex}")
            return 1

        for e in edits:
            if e["kind"] == "replace":
                if e["new"] in text:
                    print(f"already applied, skip: {e['id']}")
                    continue
                n = text.count(e["anchor"])
                if n != 1:
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:200]}")
                    return 1
                text = text.replace(e["anchor"], e["new"], 1)
            else:
                print(f"VALIDATION FAILED: unknown kind {e['kind']}")
                return 1
        pending[path] = text

    if not pending:
        print("no changes")
        return 0
    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        except OSError as ex:
            print(f"write failed {path}: {ex}")
            return 1
        changed.append(path)
    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
