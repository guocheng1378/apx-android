package com.allperiph.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 「照片」媒体权限申请页（ROADMAP §5.2 ①，2026-11-12）。
 *
 * 悬浮球菜单里的「最新照片卡片」在 Service（overlay）里构建，而运行时权限只能由
 * Activity 发起申请 —— 与相机采集页（CameraPushActivity）走 Activity Result API 是同一
 * 先例（override onRequestPermissionsResult 在 ComponentActivity 下签名编译不过，PR #162）。
 *
 * 这里刻意做薄：透明主题只承载系统权限弹窗，进来就申请、拿到结果（无论授予与否）
 * 即 finish。授予与否由 [BallLatestPhoto.hasMediaPerm] 在下次用卡片时重新判 ——
 * 被拒绝过的话系统弹「仅本次允许 / 不允许」，用户可以从系统设置改，本页不二翻引导。
 */
class PhotoPermActivity : ComponentActivity() {

    /** 必须在 onCreate 前注册（属性初始化器在构造期执行，CameraPushActivity 同款注释） */
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Log.i("PhotoPerm", "READ_MEDIA_IMAGES 结果：granted=$it")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perm.launch(Manifest.permission.READ_MEDIA_IMAGES)
    }

    companion object {
        /** 悬浮球卡片点击/拖出时拉起（Service 场景需 NEW_TASK 标志） */
        fun intent(c: Context): Intent =
            Intent(c, PhotoPermActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
