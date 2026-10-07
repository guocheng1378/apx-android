package com.allperiph.ui

import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.allperiph.R

/**
 * 键盘页 Fragment：封装 [KeyboardPanels] 三套键位面板。
 *
 * 从 [MainActivity] 拆出。键盘页只在横屏呈现，不占底栏位。
 */
class KeyboardFragment : Fragment() {

    private lateinit var keyboardPanel: KeyboardPanels
    private lateinit var keyboardBox: LinearLayout

    companion object {
        const val TAG = "KeyboardFragment"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val act = activity ?: return
        keyboardPanel = KeyboardPanels(act)
        keyboardBox = act.findViewById(R.id.keyboardBox)
        keyboardBox.addView(
            keyboardPanel.build(), LinearLayout.LayoutParams(-1, -1)
        )
    }

    /** 获取 KeyboardPanels 实例（供 Activity 调用键盘相关方法） */
    fun getKeyboardPanel(): KeyboardPanels = keyboardPanel
}