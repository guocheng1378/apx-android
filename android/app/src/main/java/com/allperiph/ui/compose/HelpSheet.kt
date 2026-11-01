package com.allperiph.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.allperiph.R
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 完整说明（帮助与说明）。
 *
 * **为什么会有这个文件**：界面层迁 Compose 时，旧设置页里「帮助与说明」那一层整块丢了 ——
 * `strings.xml` 里 `ui_help_*` 十几个键的文案都还在，代码里却一处引用都没有，
 * 用户看到的是「主页什么说明都没有」。本轮把它补回：主页给「三步上手」摘要 + 本页入口，
 * 本页按「三步上手 / 项目说明 / 链路与延迟 / 手势与操作 / 功能说明 / 常见问题 / 开关与日志」
 * 七段排开，**文案全部复用既有 strings**，不新造一套口径。
 */
@Composable
fun ApHelpSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ApMiuixTheme {
        Dialog(onDismissRequest = onDismiss) {
            Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(text = ctx.getString(R.string.ui_main_cat_help), fontSize = 18.sp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                    ) {
                        HelpBlock(ctx.getString(R.string.ui_help_title_howto), ctx.getString(R.string.ui_help_howto))
                        HelpBlock(ctx.getString(R.string.ui_help_title_project), ctx.getString(R.string.ui_help_project))
                        HelpBlock(ctx.getString(R.string.ui_help_title_link), ctx.getString(R.string.ui_help_link))
                        HelpBlock(ctx.getString(R.string.ui_help_title_gesture), ctx.getString(R.string.ui_help_gesture))
                        HelpBlock(
                            ctx.getString(R.string.ui_help_title_features),
                            listOf(
                                ctx.getString(R.string.ui_help_cursor_lock),
                                ctx.getString(R.string.ui_help_gesture_shield),
                                ctx.getString(R.string.ui_help_localsend),
                            ).joinToString("\n"),
                        )
                        HelpBlock(ctx.getString(R.string.ui_help_title_faq), ctx.getString(R.string.ui_help_faq))
                        HelpBlock(ctx.getString(R.string.ui_help_title_switch), ctx.getString(R.string.ui_help_export_log))
                    }
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(text = ctx.getString(R.string.ui_help_close), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(text = title, fontSize = 15.sp)
        Text(
            text = body,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
