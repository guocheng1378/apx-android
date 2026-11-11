package com.allperiph.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.allperiph.R
import java.io.File

/**
 * 悬浮球球面图标的选择器（issue#168 第 9 条，2026-11-11）。
 *
 * 内置图标六选一（触控板 / 键盘 / 手柄 / 副屏 / 相机 / 文件）+ **相册图片皮肤**
 * （第 9 条剩余项，本批补上）：相册取图 → 中心裁方 → 下采样落私有目录，球面与
 * 设置页预览都读这份副本。皮肤不做单色 tint —— 原图是什么样球面就是什么样，
 * 「球面单色 tint 适配」对照片没意义（那是给单色矢量图标压色的）。
 *
 * 存储沿用 [FloatingBallService] 的偏好文件（`apx_floating_ball`，字符串与服务端
 * `PREFS` 常量同源）：球本身就是这个文件在读，不为一个键另开碎片文件。
 * 图标名只存资源名（如 `ic_apx_keyboard`），不存资源 ID —— 混淆/资源重排后 ID 会漂，
 * 资源名是稳定的；落 `res()` 时查表换回 ID，查不到回落触控板（与布局默认 src 同源）。
 */
object BallIcon {

    /** 与 FloatingBallService.PREFS 同一份文件（见类注释，避免偏好文件碎片化） */
    private const val PREFS = "apx_floating_ball"
    private const val KEY_ICON = "ball_icon"

    /** 可选的内置图标（资源名），顺序即设置页展示顺序，触控板打头沿用默认值 */
    val CHOICES = listOf(
        "ic_apx_touchpad",
        "ic_apx_keyboard",
        "ic_apx_gamepad",
        "ic_apx_screen",
        "ic_apx_camera",
        "ic_apx_files",
    )

    /** 相册皮肤的伪资源名：选中它时球面用 [photoFile] 的副本，不查资源表 */
    const val CUSTOM = "__custom__"

    /** 皮肤副本（中心裁方后）边长：球 48dp、@3x 144px，192 留点缩放余量足够清晰 */
    private const val SKIN_PX = 192

    /** 当前选中项（资源名或 [CUSTOM]）；存的是 CUSTOM 但副本丢了 → 回落触控板 */
    fun picked(c: Context): String {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ICON, CHOICES[0])
        return when {
            s == CUSTOM -> if (hasPhoto(c)) CUSTOM else CHOICES[0]
            s != null && CHOICES.contains(s) -> s
            else -> CHOICES[0]
        }
    }

    /** 皮肤副本的落点：设置页取图写、球面读，同一份私有文件 */
    fun photoFile(c: Context): File = File(c.filesDir, "ball_skin.png")

    fun hasPhoto(c: Context): Boolean = photoFile(c).length() > 0

    /** 资源名 → drawable ID 的白名单查表：不用 getIdentifier（错名静默返回 0 难排查） */
    fun res(name: String): Int = when (name) {
        "ic_apx_keyboard" -> R.drawable.ic_apx_keyboard
        "ic_apx_gamepad" -> R.drawable.ic_apx_gamepad
        "ic_apx_screen" -> R.drawable.ic_apx_screen
        "ic_apx_camera" -> R.drawable.ic_apx_camera
        "ic_apx_files" -> R.drawable.ic_apx_files
        else -> R.drawable.ic_apx_touchpad   // 默认项同时兜底未知名（含 CHOICES[0] 与历史脏数据）
    }

    /** 当前选中图标的 drawable ID（给球面 ImageView 直接 setImageResource 用；CUSTOM 时勿用） */
    fun drawable(c: Context): Int = res(picked(c))

    /**
     * 当前球面位图：选中相册皮肤且副本可解码时返回，否则 null
     * （调用方回落内置图标 —— 副本损坏不该把球画空）。
     */
    fun bitmap(c: Context): Bitmap? = runCatching {
        val f = photoFile(c)
        if (f.length() == 0L) return@runCatching null
        BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    /** 存下选择。只落盘，不碰球 —— 即时生效走 FloatingBallService.applyIcon，两步分开好测试 */
    fun set(c: Context, name: String) {
        require(name == CUSTOM || CHOICES.contains(name)) { "未知悬浮球图标：$name" }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ICON, name).apply()
    }

    /**
     * 相册取图落盘：中心裁方 + 下采样到 [SKIN_PX]，写进私有目录并选中 [CUSTOM]。
     * 立即拷贝副本而不是存 URI —— 相册授权不持久化，重启后原 URI 就读不到了。
     *
     * 解码三级退化（issue#168 2026-11-11 六追：用户选云图/HEIF 报「读取图片失败」）：
     * ① API 28+ 优先走 ImageDecoder（支持 HEIF/AVIF/动图首帧，云图/受限图宽容度最高）；
     * ② 退回 BitmapFactory 两遍解码（读尺寸定 inSampleSize 后再真解）；
     * ③ 再退 ContentResolver.loadThumbnail 直接取缩略图（元数据库有缓存，最后兜底）。
     *
     * @return true = 拷贝成功（已选中相册皮肤）；false = 三级都失败（保持原选择）
     */
    fun setPhoto(c: Context, uri: Uri): Boolean = runCatching {
        val resolver = c.contentResolver
        // ① ImageDecoder（API 28+）：对 HEIF/AVIF/云图最宽容
        //    （显式声明 Bitmap?：链尾是平台类型，隐式声明会挡住后续智能转换，CI 编译红过一回）
        val srcOrNull: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(resolver, uri),
                ) { decoder, info, _ ->
                    // 原图直解（动辄 12MP）只为出一张 192px 方图：解码时按比例下采样
                    // 到边长不小于 SKIN_PX 的最小整倍数，再走中心裁方，内存与时间都省
                    val factor = maxOf(1, minOf(info.size.width, info.size.height) / (SKIN_PX * 2))
                    decoder.setTargetSampleSize(factor)
                }
            }.getOrNull()
        } else null
            // ② BitmapFactory 两遍解码：第一遍只读尺寸定 inSampleSize，第二遍按比例下采样
            ?: run {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: return false
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SKIN_PX) sample *= 2
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(
                        it, null, BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }
            // ③ 缩略图兜底：云图/受限图在元数据库里有现成缓存，直接取
            ?: resolver.loadThumbnail(uri, android.util.Size(SKIN_PX, SKIN_PX), null)
        val src = srcOrNull ?: return false
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side,
        )
        val scaled = if (side > SKIN_PX) {
            Bitmap.createScaledBitmap(square, SKIN_PX, SKIN_PX, true).also { if (it !== square) square.recycle() }
        } else {
            square
        }
        photoFile(c).outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (scaled !== src) scaled.recycle()
        if (src !== scaled && !src.isRecycled) src.recycle()
        set(c, CUSTOM)
        true
    }.getOrDefault(false)
}
