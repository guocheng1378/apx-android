package com.allperiph.ui.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.allperiph.ui.miuix.MiuixForm
import top.yukonga.miuix.kmp.basic.HorizontalDivider

/**
 * 设置页（Compose 重构线）—— 重新梳理过的信息架构。
 *
 * ```
 * ┌ 胶囊分类：通用 · 外观 · 遥控 · 关于 · 帮助 ────────────┐
 * │ 分组标题（SmallTitle）                                │
 * │ ┌ 卡片 ───────────────────────────────────────────┐ │
 * │ │ 选项行：开关行 / 下拉行 / 点进行 / 说明行          │ │
 * │ └─────────────────────────────────────────────────┘ │
 * │ 下一个分组 …                                          │
 * └───────────────────────────────────────────────────────┘
 * ```
 *
 * 与旧版的三处不同：
 *  1. **一级结构**：旧版是「首页一串分类行 → 点进二级页 → 返回」；现在分类是常驻的胶囊栏，
 *     换分类只换下面的列表，少一层跳转、也不会出现「不知道自己在哪一级」；
 *  2. **分组**：每块一张卡片 + 小标题，不是一整张长卡片里堆 20 行；
 *  3. **选项形态**：点进下一级用行尾箭头、就地取值用行尾文字，只有「立即发生动作」的才用按钮
 *     —— 旧版把「打开」「自检」做成胶囊，窄列里标题被挤到折行。
 */
@Composable
fun SettingsPage(
    context: Context,
    formOverride: MiuixForm?,
    onFormOverride: (MiuixForm?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 分类选中态要能用 rememberSaveable 存活（旋转 / 重建），枚举本身可序列化
    var category by rememberSaveable { mutableStateOf(SettingsCategory.GENERAL) }

    Column(modifier = modifier.fillMaxSize()) {
        CapsuleCategoryBar(selected = category, onSelect = { category = it })
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (category) {
                SettingsCategory.GENERAL -> GeneralSettings(
                    context = context,
                    formOverride = formOverride,
                    onFormOverride = onFormOverride,
                )
                SettingsCategory.LOOK -> LookSettings(context)
                SettingsCategory.REMOTE -> RemoteSettings(context)
                SettingsCategory.ABOUT -> AboutSettings(context)
                SettingsCategory.HELP -> HelpSettings()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
