package com.allperiph.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.allperiph.R
import com.allperiph.hid.HidKeys

/**
 * 自定义键盘布局编辑器（v1.33）。
 *
 * 用户可手动排整张键盘：
 * · 行可上下移 / 删 / 新增；
 * · 行内键**点一下即删**，"+ 键"按钮按"先选分组、再选键"两段选键添加
 *   （复用 [HidKeys.KEY_GROUPS]，避免未知 label 写进布局后发不出键）。
 *
 * 保存写到 [KeyPref.setCustomRows]；键盘页"自定义"页签切换时即重建。
 */
object CustomKeyboardDialog {

    fun show(act: Activity, onDone: () -> Unit) {
        val rows = KeyPref.customRows(act)?.map { it.toMutableList() }?.toMutableList()
            ?: defaultRows()
        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("自定义键盘布局")
            .setView(buildView(act, rows))
            .setPositiveButton("保存") { _, _ ->
                KeyPref.setCustomRows(act, rows.map { it.toList() })
                onDone()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清空") { _, _ ->
                KeyPref.setCustomRows(act, null)
                onDone()
            }
            .show()
    }

    private fun defaultRows() = mutableListOf(
        mutableListOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="),
        mutableListOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        mutableListOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        mutableListOf("Z", "X", "C", "V", "B", "N", "M"),
        mutableListOf("Esc", "Tab", "Space", "Enter", "⌫", "Del"),
    )

    private fun dp(v: View, x: Int) = (v.context.resources.displayMetrics.density * x).toInt()

    private fun buildView(act: Activity, rows: MutableList<MutableList<String>>): View {
        val root = ScrollView(act)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16))
        }
        fun rebuild() {
            col.removeAllViews()
            rows.forEachIndexed { i, row ->
                val rc = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(this, 4), 0, dp(this, 4))
                }
                // 行操作：上移 / 下移 / 删行
                rc.addView(smallBtn(act, "↑") {
                    if (i > 0) {
                        val tmp = rows.removeAt(i)
                        rows.add(i - 1, tmp)
                        rebuild()
                    }
                })
                rc.addView(smallBtn(act, "↓") {
                    if (i < rows.size - 1) {
                        val tmp = rows.removeAt(i)
                        rows.add(i + 1, tmp)
                        rebuild()
                    }
                })
                rc.addView(smallBtn(act, "✕") {
                    rows.removeAt(i)
                    rebuild()
                })
                // 行内键（点一下即删）
                row.toList().forEach { label ->
                    rc.addView(TextView(act).apply {
                        text = label
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(act.getColor(R.color.md_on_surface))
                        setBackgroundColor(act.getColor(R.color.md_surface))
                        setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7))
                        setOnClickListener {
                            rows[i].remove(label)
                            rebuild()
                        }
                    })
                }
                // 加键
                rc.addView(smallBtn(act, "+ 键") {
                    pickKey(act) { label -> rows[i].add(label); rebuild() }
                })
                col.addView(rc)
            }
            // 末尾：新增空行
            col.addView(smallBtn(act, "+ 新行") {
                rows.add(mutableListOf())
                rebuild()
            })
        }
        rebuild()
        root.addView(col)
        return root
    }

    private fun smallBtn(act: Activity, text: String, onClick: () -> Unit) =
        Button(act).apply {
            this.text = text
            textSize = 11f
            minHeight = 0
            minWidth = 0
            setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4))
            setOnClickListener { onClick() }
        }

    /** 两段选键：先选分组，再选该组下的键 */
    private fun pickKey(act: Activity, onPick: (String) -> Unit) {
        val groups = HidKeys.KEY_GROUPS
        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("选分组")
            .setItems(groups.map { it.first }.toTypedArray()) { d1, gi ->
                d1.dismiss()
                AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
                    .setTitle(groups[gi].first)
                    .setItems(groups[gi].second.map { it.first }.toTypedArray()) { _, ki ->
                        onPick(groups[gi].second[ki].first)
                    }
                    .show()
            }
            .show()
    }
}
