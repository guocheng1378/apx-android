package com.allperiph.ui.feature.send

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.localsend.LocalSendClient
import com.allperiph.localsend.LocalSendProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 互传发送页（Compose 重构线）—— 旧 `SendActivity`（132 行）的 Compose 版。
 *
 * 流程与旧版一致：列出 `LocalSendClient.activeDevices()` 里 60 秒内 announce 过的设备（2 秒刷新）
 * → 点设备 → 系统多选文件 → 后台三步推送（`LocalSendClient.send`，pre-upload / upload / cancel 语义都在里面）。
 *
 * 两处保留旧版的实现原因，别改掉：
 *  · **文件大小必须准**：协议里 Content-Length 要和实传字节一致，`OpenableColumns.SIZE` 拿不到
 *    （很多 ContentProvider 不给）时得先拷进 cache 量一遍，不能估；
 *  · **deviceType 用 smallestScreenWidthDp ≥ 600** 判平板，跟旧版同一个口径，
 *    否则同一台设备在两边看到的类型不一致。
 */
@Composable
fun SendScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MiuixTheme.colorScheme
    var devices by remember { mutableStateOf(LocalSendClient.activeDevices()) }
    var picked by remember { mutableStateOf<LocalSendClient.Discovered?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 与旧版同一个节奏：2 秒刷一次（announce 是 60 秒窗口，太快没意义、太慢点了会落空）
    LaunchedEffect(Unit) {
        while (true) {
            devices = LocalSendClient.activeDevices()
            delay(2_000)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = picked
        picked = null
        if (target == null || uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.IO) {
            val items = uris.mapNotNull { buildItem(context, it) }
            val msg = if (items.isEmpty()) {
                context.getString(R.string.ui_localsend_none)
            } else {
                LocalSendClient.send(target, selfInfo(context), items).message
            }
            // 回到主线程弹结果（外层 scope 是 IO，这里不能直接再 launch，否则还是 IO）
            withContext(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                busy = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SmallTitle(text = stringResource(R.string.shell_send_title))
        Text(
            text = stringResource(R.string.shell_send_pick),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.shell_send_empty),
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            for (device in devices) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.surfaceVariant)
                        .clickable(enabled = !busy) {
                            picked = device
                            picker.launch(arrayOf("*/*"))
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.alias, fontSize = 15.sp)
                        Text(
                            text = "${device.deviceType} · ${device.ip}",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/** 本机信息：deviceType 用 smallestScreenWidthDp ≥ 600 判平板（与旧版同口径） */
private fun selfInfo(context: Context) = LocalSendProtocol.deviceInfo(
    alias = android.os.Build.MODEL,
    deviceType = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone",
    download = true,
)

/** Uri → SendItem：先问 ContentProvider 要名字与大小，拿不到大小就拷一份量准 */
private fun buildItem(context: Context, uri: Uri): LocalSendClient.SendItem? {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
    var size = -1L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0) size = c.getLong(si)
            }
        }
    } catch (_: Throwable) {
    }
    if (size > 0) {
        return LocalSendClient.SendItem(name, size) {
            context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("open failed")
        }
    }
    // Content-Length 必须与实传字节一致：拷到 cache 量一遍
    val tmp = java.io.File(context.cacheDir, "ls_push_${System.nanoTime()}")
    return try {
        context.contentResolver.openInputStream(uri)!!.use { ins ->
            tmp.outputStream().use { outs -> ins.copyTo(outs) }
        }
        LocalSendClient.SendItem(name, tmp.length()) { tmp.inputStream() }
    } catch (_: Throwable) {
        tmp.delete()
        null
    }
}
