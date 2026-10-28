package com.allperiph.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import com.allperiph.ui.miuix.CapsuleRow

/**
 * 设置页的**结构件**（Compose 重构线）。
 *
 * ## 排版口径（这一版重新梳理过的信息架构）
 *  · **流程**：进设置先看到一排**胶囊分类**（通用 / 外观 / 遥控 / 关于 / 帮助），点一个就在
 *    原地换掉下面的列表 —— 不再像旧版那样「首页是一串分类行、点进去再退出来」两级跳；
 *  · **分组**：每个分类内部按语义分块，块标题用 `SmallTitle`，块体是一张卡片；
 *  · **列表**：卡片里是一行行**选项**（开关行 / 下拉行 / 点进行 / 取值行 / 说明行），
 *    同一块的选项行不重复画分隔线 —— 卡片本身的圆角与留白已经把它们分开了；
 *  · **胶囊**：只有分类切换用胶囊按钮。选项行里「点进下一级」用行尾箭头、「就地取值」用
 *    行尾文字 —— 旧版把「打开」「自检」也做成胶囊，在窄列里要吃掉 60dp，标题只能折行。
 *
 * 为什么把结构件与内容分开：[SettingsPage] 只管骨架，五个分类各自一个文件，
 * 加一项设置 = 在该分类的列表里加一行，不动骨架。
 */

/** 设置页的五个分类 */
enum class SettingsCategory(val titleRes: Int) {
    GENERAL(R.string.ui_main_cat_general),
    LOOK(R.string.ui_main_cat_look),
    REMOTE(R.string.ui_main_cat_remote),
    ABOUT(R.string.ui_main_cat_about),
    HELP(R.string.ui_main_cat_help),
}

/**
 * 胶囊分类栏：横向可滚，选中态实心主色。
 *
 * 选中态用「主色实心 + onPrimary 文字」而不是下划线或小圆点 —— 胶囊本身就是按钮，
 * 实心是最不容易误读的选中反馈；五个分类在手机宽度下正好一屏（多语言变长时可滚）。
 * 具体画法在 [com.allperiph.ui.miuix.CapsuleRow]，键盘页的面板切换用的是同一份。
 */
@Composable
fun CapsuleCategoryBar(selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit) {
    val all = SettingsCategory.entries
    CapsuleRow(
        labelRes = all.map { it.titleRes },
        selectedIndex = all.indexOf(selected),
        onSelect = { onSelect(all[it]) },
    )
}

/**
 * 一个分组：小标题 + 一张卡片。
 *
 * @param titleRes 分组标题；null = 无标题（紧跟在上一块后面的补充块）
 */
@Composable
fun SettingsGroup(titleRes: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (titleRes != null) SmallTitle(text = stringResource(titleRes))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** 说明行：整段文字（帮助分类的长文、诊断提示都用它） */
@Composable
fun InfoRow(text: String, small: Boolean = false) {
    Text(
        text = text,
        fontSize = if (small) 12.sp else 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// ==================== 设置项用到的系统动作 ====================

/**
 * 是否已加入电池优化白名单（true = 已加入，不再被后台冻结）。
 *
 * 注意语义：`EnvChecks.Env.batteryOptimized` 的 true 意思是**还没加入**，两者相反 ——
 * 这里直接用 PowerManager 的原始语义，调用处一眼能读对。
 */
fun isInBatteryWhitelist(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * 申请加入电池白名单。
 *
 * 与旧 MainActivity.requestBatteryWhitelist() 同一套：先直接申请，失败退回系统白名单
 * 列表页（各家 ROM 对这个 Intent 的处理不一样，直接崩掉不行）。
 */
fun requestBatteryWhitelist(context: android.content.Context) {
    val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(android.net.Uri.parse("package:${'$'}{context.packageName}"))
    runCatching {
        context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** 小节标题行（卡片内部再分小节时用） */
@Composable
fun SubHeader(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}
