#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器（无参执行；EDITS 自包含；两阶段：先全量校验命中次数，全过才写盘）。

批 4（2026-11-06）：手机侧采集触发通道 + ROADMAP 同步（M7-c 收口）。
  · web_panel.cpp — camera.toggle 同时经 WirelessSession::sendModuleToggle("camera", on)
    下发 0x10 模块开关（现有通用通道，无协议变更）
  · ControlledService — onModuleToggle 认 "camera" → EventBus 发 CameraPushRequestEvent
  · AgentForegroundService — on=true 拉起 CameraPushActivity（前台直接弹、后台靠通知
    「摄像头」动作兔底）；off 由推流页自行订阅 finish
  · CameraPushActivity — 订阅关闭事件 + 更新类头启动说明
  · check_entrypoints — 删除 CameraPushActivity 豁免（入口已接，按约删）
  · strings.xml — notify_action_camera；Events.kt — 事件定义
  · check_protocol — EXPECT_STREAMS 补 camera=6（frame.h/ApxFrame/PROTOCOL 三处已对齐，锁不变量）
  · ROADMAP §2.3 摄像头行 — ❌未开始 → ✅代码完成真机未实测
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

# (相对路径, [(old, new, 期望命中次数), ...]) —— 文本用三引号逐字节对齐源文件
EDITS = [
    ("docs/ROADMAP.md", [
        ('''| **摄像头**（手机 → PC） | 待做：Camera2 JPEG → `MediaOut.camera()`（`streamId=6`）；PC 侧预览 | ❌ **未开始** |''',
         '''| **摄像头**（手机 → PC） | 手机 `camera/CameraPushActivity.kt`（CameraX → JPEG → `MediaOut.camera()`，`streamId=6`）→ PC `media/media_session.cpp` 收流 → `media/camera_view.cpp`（GDI+ 解码 → 预览窗 + 虚拟摄像头）；面板 `camera.toggle` 经 0x10「camera」拉起/关闭手机采集页（M7-b + M7-c，2026-11-06） | ✅ 代码完成，**真机未实测**（清单见 [spike §4](./spikes/m7-media-inbound-spike.md)） |''', 1),
    ]),
    ("scripts/check_entrypoints.py", [
        ('''ACTIVITY_EXEMPT = {
    "ControlShortcutActivity": "有 intent-filter（快捷方式 / 磁贴），系统可直接拉起",
    "CameraPushActivity":
        "M7-b 摄像头推流的中间批（2026-11-05，PR #162）：应用内入口按计划"
        "在 M7-c 由 PC 面板下发拉起（docs/spikes/m7-media-inbound-spike.md §3），"
        "本批先经 adb 验证链路（am start -n com.allperiph/.camera.CameraPushActivity）；"
        "M7-c 接入口后删除本豁免",
}''',
         '''ACTIVITY_EXEMPT = {
    "ControlShortcutActivity": "有 intent-filter（快捷方式 / 磁贴），系统可直接拉起",
}
# CameraPushActivity 豁免已按约删除（M7-c 批4，2026-11-06）：入口 = PC 面板
# camera.toggle → 0x10「camera」→ AgentForegroundService 以
# `Intent(this, CameraPushActivity::class.java)` 拉起（判据①的代码内类引用成立），
# 通知「摄像头」动作与 adb am start 兔底。''', 1),
    ]),
    ("scripts/check_protocol.py", [
        ('''    "telemetry": 4,
    "mic": 5,
}''',
         '''    "telemetry": 4,
    "mic": 5,
    "camera": 6,   # M7-b/c：frame.h kStreamCamera 与 ApxFrame.STREAM_CAMERA 两端镜像（PROTOCOL §3.4 已列）
}''', 1),
    ]),
    ("android/app/src/main/java/com/allperiph/core/Events.kt", [
        ('''/** PC 请求打开副屏页（控制通道 0x05，由面板开启副屏推流时下发） */
class ScreenOpenRequestEvent''',
         '''/** PC 请求打开副屏页（控制通道 0x05，由面板开启副屏推流时下发） */
class ScreenOpenRequestEvent

/**
 * PC 面板摄像头开关（0x10 模块开关 id="camera"，M7-c 批4，2026-11-06）。
 * on=true：AgentForegroundService 拉起推流页开始采集；
 * on=false：推流页收到同一事件自行 finish（未开页时无人消费，无事发生）。
 */
class CameraPushRequestEvent(val on: Boolean)''', 1),
    ]),
    ("android/app/src/main/java/com/allperiph/controlled/ControlledService.kt", [
        ('''import com.allperiph.core.ScreenOpenRequestEvent''',
         '''import com.allperiph.core.CameraPushRequestEvent
import com.allperiph.core.ScreenOpenRequestEvent''', 1),
        ('''        server?.onModuleToggle = { id, on ->
            if (id == "wireless") {
                if (on) server?.resumeAccept() else server?.suspendAccept()
            }
        }''',
         '''        server?.onModuleToggle = { id, on ->
            if (id == "wireless") {
                if (on) server?.resumeAccept() else server?.suspendAccept()
            } else if (id == "camera") {
                // M7-c 批4：面板 camera.toggle → 开/关推流页。开由 AgentForegroundService
                // 执行 startActivity（后台被拦时通知按钮兔底），关由推流页订阅同一事件
                // 自行 finish。回调已在主线程（ControlServer 侧 mainHandler.post），发事件安全。
                EventBus.post(CameraPushRequestEvent(on))
            }
        }''', 1),
    ]),
    ("android/app/src/main/java/com/allperiph/ui/AgentForegroundService.kt", [
        ('''import com.allperiph.core.AgentStateEvent
import com.allperiph.shared.event.EventBus''',
         '''import com.allperiph.core.AgentStateEvent
import com.allperiph.core.CameraPushRequestEvent
import com.allperiph.shared.event.EventBus''', 1),
        ('''        // 副屏一键直达：PC 开副屏时若 app 在后台被系统拦了弹页，通知这个动作兔底
        val screenPi = PendingIntent.getActivity(
            this, 3,
            Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )''',
         '''        // 副屏一键直达：PC 开副屏时若 app 在后台被系统拦了弹页，通知这个动作兔底
        val screenPi = PendingIntent.getActivity(
            this, 3,
            Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 摄像头一键直达（M7-c 批4）：与副屏同款兔底 —— PC 开预览时后台被拦，靠它拉起
        val cameraPi = PendingIntent.getActivity(
            this, 4,
            Intent(this, com.allperiph.camera.CameraPushActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )''', 1),
        ('''            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_peripheral,
                    getString(R.string.notify_action_screen),
                    screenPi,
                ).build()
            )''',
         '''            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_peripheral,
                    getString(R.string.notify_action_screen),
                    screenPi,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_peripheral,
                    getString(R.string.notify_action_camera),
                    cameraPi,
                ).build()
            )''', 1),
        ('''        disposables += EventBus.on<ScreenOpenRequestEvent>(mainHandler) { onPcOpenScreen() }''',
         '''        disposables += EventBus.on<ScreenOpenRequestEvent>(mainHandler) { onPcOpenScreen() }
        disposables += EventBus.on<CameraPushRequestEvent>(mainHandler) { onPcCameraToggle(it.on) }''', 1),
        ('''    private fun onPcOpenScreen() {
        runCatching {
            startActivity(
                Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w(TAG, "后台打开副屏被系统拦截：${it.message}（通知「副屏」按钮可用）")
        }
        updateNotification()
    }''',
         '''    private fun onPcOpenScreen() {
        runCatching {
            startActivity(
                Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w(TAG, "后台打开副屏被系统拦截：${it.message}（通知「副屏」按钮可用）")
        }
        updateNotification()
    }

    /**
     * PC 面板开了摄像头预览（0x10「camera」on，M7-c 批4）：拉起推流页开始采集。
     * off 不在此处理 —— 同一事件由推流页自己订阅后 finish（没开页 = 无需关）。
     * 与副屏同口径：后台被拦记日志，通知「摄像头」按钮兔底。
     */
    private fun onPcCameraToggle(on: Boolean) {
        if (!on) return
        runCatching {
            startActivity(
                Intent(this, com.allperiph.camera.CameraPushActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w(TAG, "后台打开摄像头推流页被系统拦截：${it.message}（通知「摄像头」按钮可用）")
        }
        updateNotification()
    }''', 1),
    ]),
    ("android/app/src/main/java/com/allperiph/camera/CameraPushActivity.kt", [
        (''' * 启动（当前无应用内入口，M7-c 面板接）：
 *   adb shell am start -n com.allperiph/.camera.CameraPushActivity''',
         ''' * 启动（M7-c 批4 起有面板入口）：PC 面板「摄像头」开关 → 0x10「camera」→
 *   AgentForegroundService 拉起本页；通知「摄像头」动作 / adb 兔底：
 *   adb shell am start -n com.allperiph/.camera.CameraPushActivity''', 1),
        ('''import android.os.Bundle''',
         '''import android.os.Bundle
import android.os.Handler
import android.os.Looper''', 1),
        ('''import com.allperiph.core.MediaOut
import java.io.ByteArrayOutputStream''',
         '''import com.allperiph.core.CameraPushRequestEvent
import com.allperiph.core.MediaOut
import com.allperiph.shared.event.EventBus
import java.io.ByteArrayOutputStream''', 1),
        ('''    private var provider: ProcessCameraProvider? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()''',
         '''    private var provider: ProcessCameraProvider? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    // M7-c 批4：PC 关摄像头（0x10「camera」off）→ 订阅同一事件关本页停采集；
    // 开路径由 AgentForegroundService startActivity 拉起（它只处理 on=true）
    private var closeDisposable: EventBus.Disposable? = null''', 1),
        ('''        note = "等待摄像头权限…"
        render()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {''',
         '''        closeDisposable = EventBus.on<CameraPushRequestEvent>(Handler(Looper.getMainLooper())) {
            if (!it.on) {
                note = "PC 已关闭摄像头采集"
                render()
                finish()
            }
        }
        note = "等待摄像头权限…"
        render()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {''', 1),
        ('''    override fun onDestroy() {
        super.onDestroy()
        provider?.unbindAll()
        analyzerExecutor.shutdown()
    }''',
         '''    override fun onDestroy() {
        super.onDestroy()
        closeDisposable?.dispose()
        provider?.unbindAll()
        analyzerExecutor.shutdown()
    }''', 1),
    ]),
    ("android/app/src/main/res/values/strings.xml", [
        ('''    <string name="notify_action_screen">副屏</string>''',
         '''    <string name="notify_action_screen">副屏</string>
    <string name="notify_action_camera">摄像头</string>''', 1),
    ]),
    ("pc/host/src/ui/web_panel.cpp", [
        ('''    if (name == "camera.toggle") {
        auto& cv = cameraView();
        cv.setPreview(!cv.status().previewOpen);
        return postResponse(p, id, okResp());
    }''',
         '''    if (name == "camera.toggle") {
        auto& cv = cameraView();
        cv.setPreview(!cv.status().previewOpen);
        const bool on = cv.status().previewOpen;
        // M7-c 批4：预览开关同步下发手机采集 —— 0x10 模块开关「camera」，
        // 手机端 ControlledService → 前台服务拉起/关闭 CameraPushActivity。
        // 未连受控端 sendModuleToggle 返回 false：不改面板口径，
        // 收没收到帧以 buildState 的 cameraFrames 计数为准（0 即没通）。
        if (b.session) (void)b.session->sendModuleToggle("camera", on);
        return postResponse(p, id, okResp());
    }''', 1),
    ]),
]


def main():
    plan = []
    failed = False
    for rel, reps in EDITS:
        full = os.path.join(ROOT, rel)
        if not os.path.exists(full):
            print("MISSING_FILE " + rel)
            failed = True
            continue
        with open(full, "r", encoding="utf-8") as f:
            text = f.read()
        new = text
        for old, repl, want in reps:
            got = new.count(old)
            if got != want:
                print("ANCHOR_FAIL " + rel + " count=" + str(got) + " want=" + str(want))
                print("  old[0:80]=" + old[:80].replace("\n", "\\n"))
                failed = True
            else:
                new = new.replace(old, repl)
        if new != text:
            plan.append((rel, text, new))
    if failed:
        print("VALIDATE_FAILED: 有锚点未命中，不写盘。")
        return 1
    if not plan:
        print("NO_CHANGES: 所有编辑的期望文本均已存在。")
        return 0
    for rel, text, new in plan:
        full = os.path.join(ROOT, rel)
        with open(full, "w", encoding="utf-8") as f:
            f.write(new)
        print("WROTE " + rel)
        diff = "".join(difflib.unified_diff(
            text.splitlines(keepends=True), new.splitlines(keepends=True),
            fromfile="a/" + rel, tofile="b/" + rel, n=3))
        print(diff)
    print("OK: wrote " + str(len(plan)) + " file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
