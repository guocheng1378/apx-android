package com.allperiph.ui.miuix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch

/**
 * Miuix **组件总览**（开发页，miuix-ui 分支）—— 回答「框架能不能对接所有组件」。
 *
 * 把 [top.yukonga.miuix.kmp] 的常用组件各放一个实例、全部走 [ApMiuixTheme]
 * （配色 = res/values/colors.xml 的 md_* 令牌 + ThemeSkin 皮肤）——
 * 一页看全：文本 / 卡片 / 按钮 / 开关 / 选择 / 滑杆 / 输入 / 偏好行 / 下拉 / 对话框。
 * 将来逐页迁移时，组件的正确用法以本页为准（对着 miuix v0.5.0 源码核过签名）。
 *
 * 入口：设置页「关于」卡（MiuixAboutCard）的「组件总览」按钮。
 * 纯演示，不碰任何业务状态。
 */
class MiuixShowcaseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // activity-compose 的 setContent 扩展：ComponentActivity 宿主 + ComposeView 一条路
        setContent {
            ApMiuixTheme {
                Showcase()
            }
        }
    }

    @Composable
    private fun Showcase() {
        var switchOn by remember { mutableStateOf(true) }
        var checked by remember { mutableStateOf(false) }
        var slider by remember { mutableFloatStateOf(0.4f) }
        var dropdown by remember { mutableStateOf(1) }
        var showDialog by remember { mutableStateOf(false) }
        val textState = rememberTextFieldState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallTitle(text = "Miuix 组件总览")
            Text(
                text = "配色来自 md_* 令牌（设置里的明暗与主题皮肤）；" +
                    "组件签名对 miuix v0.5.0 源码核对。",
                fontSize = 12.sp,
            )

            // —— 卡片 ——
            SmallTitle(text = "卡片与文本")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "MiuixCard", fontSize = 17.sp)
                    Text(text = "卡片内容：圆角、内边距、配色都走主题令牌。", fontSize = 13.sp)
                }
            }
            HorizontalDivider()   // 0.5.0 的名字是 HorizontalDivider（不是新版文档的 Divider）

            // —— 按钮 ——
            SmallTitle(text = "按钮")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { }, modifier = Modifier.weight(1f)) {
                    Text(text = "主按钮", fontSize = 15.sp)
                }
                TextButton(
                    text = "次按钮",
                    onClick = { },
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                text = "弹出 SuperDialog",
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )

            // —— 开关 / 选择 ——
            SmallTitle(text = "开关与选择")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(text = "Switch 开关", fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(checked = switchOn, onCheckedChange = { switchOn = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(text = "Checkbox 复选", fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                    }
                }
            }

            // —— 滑杆 ——
            SmallTitle(text = "滑杆")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Slider：${(slider * 100).toInt()}%", fontSize = 14.sp)
                    Slider(
                        progress = slider,
                        onProgressChange = { slider = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            // —— 输入 ——
            SmallTitle(text = "输入")
            TextField(
                state = textState,
                label = "TextField（state 驱动）",
                modifier = Modifier.fillMaxWidth(),
            )

            // —— 偏好行（设置页的主力组件）——
            SmallTitle(text = "偏好行（设置页主力）")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SuperSwitch(
                        title = "SuperSwitch",
                        summary = "带摘要的开关行（对比 Views 侧的 Switch 行）",
                        checked = switchOn,
                        onCheckedChange = { switchOn = it },
                    )
                    SuperArrow(
                        title = "SuperArrow",
                        summary = "箭头行（进二级页）",
                        onClick = { },
                    )
                    SuperDropdown(
                        title = "SuperDropdown",
                        summary = "下拉选择行",
                        items = listOf("选项一", "选项二", "选项三"),
                        selectedIndex = dropdown,
                        onSelectedIndexChange = { dropdown = it },
                    )
                }
            }

            // —— 对话框（声明式：常驻树里，show 驱动）——
            SuperDialog(
                show = showDialog,
                title = "SuperDialog",
                summary = "声明式弹窗：show 驱动，不是 showDialog() 命令式",
                onDismissRequest = { showDialog = false },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(text = "取消", onClick = { showDialog = false }, modifier = Modifier.weight(1f))
                    Button(onClick = { showDialog = false }, modifier = Modifier.weight(1f)) {
                        Text(text = "确定", fontSize = 15.sp)
                    }
                }
            }

            Text(
                text = "其余组件（TopAppBar / Scaffold / 图标 / 玻璃材质）随逐页迁移接入。",
                fontSize = 12.sp,
            )
        }
    }
}
