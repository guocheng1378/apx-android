package com.allperiph.shared.proto

/**
 * APX1 帧构造（`shared/include/apx/frame.h` 的 Kotlin 侧实现，docs/PROTOCOL.md §3）。
 *
 * **v1.8：从手机端 `com.allperiph.core.ApxFrame` 和 TV 端 `com.allperiph.tv.core.ApxFrame`
 * 提取到共享模块**，消除两端协议帧代码的重复维护问题。
 *
 * 帧结构（**16 字节固定头**，多字节字段显式小端，不依赖主机字节序）：
 * ```
 * off  size  field
 *  0    4    magic 'A' 'P' 'X' '1'
 *  4    1    streamId    0=video 1=audio 2=touch 3=ctrl 4=telemetry
 *  5    1    flags       bit0=keyframe bit1=last_fragment bit2=dropable
 *  6    2    headerExtWords
 *  8    4    payloadLen  = body 字节数 + 4（尾部 u32 CRC32）
 * 12    4    seq
 * 16   ...  body
 *      4    CRC32(body)，IEEE 802.3 反射多项式 0xEDB88320
 * ```
 *
 * 本文件只负责**发送方向**的组帧；接收解析在 9511 控制面（手机侧 TvControllerClient /
 * TV 侧 ControlServer / `pc/host/src/wireless/ctrl9511.cpp`），两侧 CRC 必须逐字节一致。
 */
object ApxFrame {
    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4

    /**
     * streamId 取值（与 `shared/include/apx/frame.h` 的 kStream* 一一对应）。
     * v1.11 起**带方向语义**：下行 = PC → 手机，上行 = 手机 → PC。
     * 音频之所以分成两个号：「音箱」（PC 声 → 手机扬声器）与「麦克风»
     * （手机录音 → PC）方向相反，共用 STREAM_AUDIO 会互相污染。
     */
    const val STREAM_VIDEO = 0      // 下行：副屏画面
    const val STREAM_AUDIO = 1      // 下行：音箱（PC 系统声，PCM s16le/48k/立体声）
    const val STREAM_TOUCH = 2      // 预留
    const val STREAM_CONTROL = 3    // 双向：控制面（鼠标/键盘/多媒体/心跳）
    const val STREAM_TELEMETRY = 4  // 预留
    const val STREAM_MIC = 5        // 上行：麦克风（手机录音，PCM s16le/48k/立体声）
    const val STREAM_CAMERA = 6     // 上行：摄像头（手机 → PC，JPEG 单帧不分片，M7-b）

    /** flags 位（与 frame.h 的 kFlag* 对应） */
    const val FLAG_KEY_FRAME = 1 shl 0
    const val FLAG_LAST_FRAGMENT = 1 shl 1
    const val FLAG_DROPABLE = 1 shl 2

    /** 单帧载荷上限（含 CRC），与 kMaxFramePayload 一致；超出的帧直接判为非法 */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    // ———————————————————— 远程输入帧类型（docs/REMOTE-INPUT.md）———————————————————
    /**
     * 远程输入帧子类型（payload[0]）。
     * 帧本身走 STREAM_CONTROL 通道，子类型在 payload 首字节区分。
     * 注意：0x20=剪贴板, 0x21=反向剪贴板, 0x22=电源动作，故远程输入从 0x25 开始。
     */
    const val INPUT_REQUEST = 0x25   // A→B：请求 B 设备输入
    const val INPUT_TEXT = 0x26      // B→A：实时输入文本
    const val INPUT_DONE = 0x27      // B→A：输入完成

    /** INPUT_TEXT flags */
    const val INPUT_FLAG_INCREMENTAL = 0x01  // 增量字符（默认）
    const val INPUT_FLAG_BACKSPACE = 0x02    // 退格
    const val INPUT_FLAG_COMMIT = 0x04       // 完整文本（粘贴整段）
    const val INPUT_FLAG_CANCEL = 0x08       // 用户取消

    // ———————————— 9511 控制面能力协商（2026-11-11，借鉴 NectarLink 能力表）————————————
    /**
     * 连接建立后**两端各发一帧** [CMD_CAPS_SYNC]（body = 自身能力位图 u64 小端 8 字节），
     * 接收方存下对端位图；发送能力帧前先核对**对端**位图，缺位的一端不发送对应帧族，
     * 旧版收到不认识的帧号安静忽略（协议前向兼容的唯一手段）。
     * 与 PC 侧镜像（`pc/host/include/apxpc/wireless/ctrl9511.hpp`）的位号必须一致，
     * 由 `scripts/check_protocol.py` 与 docs/PROTOCOL.md §3.3 三处对拍。
     */
    const val CMD_CAPS_SYNC = 0x33

    /** 能力位 bit0：通知同步 —— 手机→PC 推送（0x40）与消除（0x42）通知 */
    const val CAP_NOTIFY_SYNC = 1L shl 0

    /** 能力位 bit1：通知回复 —— PC→手机 经 RemoteInput 代发回复（0x41，预留未实现） */
    const val CAP_NOTIFY_REPLY = 1L shl 1

    /** 能力位 bit2：查找手机 —— PC→手机 响铃（0x38，静音也响） */
    const val CAP_FIND_PHONE = 1L shl 2

    /**
     * 能力位 bit3：手机文件浏览 —— PC/控制端浏览本机目录并取走文件（0x44–0x47）。
     */
    const val CAP_PHONE_FILES = 1L shl 3

    /**
     * 能力位 bit4：相册投影 —— PC 浏览本机相册（0x48–0x4B）：分页取条目（0x48/0x49）、
     * 新照片监听开关（0x4A）、缩略图按张取（0x4B，本体经 9512）。原图走 0x46（无损）。
     */
    const val CAP_ALBUM = 1L shl 4

    // ———————————— 9511 通知同步（2026-11-11，能力位 bit0，借鉴 NectarLink）————————————
    /**
     * 手机 → PC：推送一条通知。发送前必须核对**对端**能力位图含 [CAP_NOTIFY_SYNC]
     * （旧版对端安静不发，帧进了黑洞也是白推）。载荷布局见 [packNotifyPush]。
     * 接收方：PC `ctrl9511.cpp` 服务端 dispatch（手机控 PC）与客户端 reader（PC 控手机），
     * 两处都把通知转成托盘气泡（`TrayIcon::notify`）。
     */
    const val CMD_NOTIFY_PUSH = 0x40

    /** 手机 → PC：消除一条通知（用户在手机上划掉/点开时回发，PC 端气泡随即过时）。 */
    const val CMD_NOTIFY_DISMISS = 0x42

    // ———————————— 9511 查找手机（2026-11-11，能力位 bit2）————————————
    /**
     * PC → 手机：查找手机。发送方是 PC（托盘菜单/面板按钮），Kotlin 侧只收不发 ——
     * 常量放这里是为了与 CMD_NOTIFY_* 一致的「协议命令号唯一真源」口径
     * （`scripts/check_protocol.py` 扫描此文件的 CMD_* 当作发送侧登记）。
     * 载荷 `[0x38][action]`：action 1=响铃（STREAM_ALARM 最大音量 + 亮屏，30s 自动停）、
     * 0=停。接收方：手机 `ControlServer` / `TvControlServer` / `TvControllerClient` 三条
     * 控制面收帧路径都落 `com.allperiph.findphone.FindPhoneRinger`（app 模块，见该文件）。
     */
    const val CMD_FIND_PHONE = 0x38

    // ———————————— 9511 手机文件浏览（2026-11-11，能力位 bit3）————————————
    /**
     * PC / 控制端浏览手机文件并取走（「文件管理」方向三）。收发两端：
     *  · 发送方 PC（web 面板 → ctrl9511.cpp sendCmd）；
     *  · 接收方手机三条控制链路（ControlServer / TvControlServer / TvControllerClient），
     *    实现落 app 模块 `PhoneFiles`（与 0x38 的 FindPhoneRinger 同一套注入口径）；
     *  · 应答 0x45/0x47 由手机发出、PC 控制端 reader / 受控服务端 dispatch 两处收。
     * 能力门控：PC 发送前核对对端位图含 [CAP_PHONE_FILES]（旧版手机安静忽略兜底）。
     */
    const val CMD_PHONE_FILE_LIST = 0x44

    /** 手机 → 控制端：目录应答（见 [packPhoneFileListReply]）。 */
    const val CMD_PHONE_FILE_LIST_REPLY = 0x45

    /** PC / 控制端 → 手机：取走一个文件（手机经 9512 推回；文件到达即成功，无独立成功回执）。 */
    const val CMD_PHONE_FILE_PULL = 0x46

    /** 手机 → 控制端：取文件失败回执（见 [packPhoneFileFail]）。 */
    const val CMD_PHONE_FILE_FAIL = 0x47

    /** 应答/回执错误码（与 PC 端 ctrl9511.cpp 解析、web 面板文案三处对齐） */
    const val PHONEFILE_ERR_NONE = 0          // 成功
    const val PHONEFILE_ERR_NO_PERM = 1       // 手机没给「所有文件访问」权限
    const val PHONEFILE_ERR_NOT_FOUND = 2     // 目录/文件不存在
    const val PHONEFILE_ERR_READ = 3          // 目录读取失败（IO）
    const val PHONEFILE_ERR_SEND_FAILED = 4   // 9512 推送失败（网络断/文件中途消失）

    // ———————————— 9511 相册投影（2026-11-12，能力位 bit4）————————————
    /**
     * PC → 手机：取一页相册条目。收发两端与 0x44–0x47 同一套口径：
     *  · 发送方 PC（web 面板 → ctrl9511.cpp sendCmd）；
     *  · 接收方手机三条控制链路（ControlServer / TvControlServer / TvControllerClient），
     *    实现落 app 模块 `PhoneAlbum`（与 PhoneFiles 同一套注入口径）。
     * 能力门控：PC 发送前核对对端位图含 [CAP_ALBUM]。
     */
    const val CMD_ALBUM_PAGE = 0x48

    /** 手机 → PC：一页相册条目应答（见 [packAlbumPageReply]）。 */
    const val CMD_ALBUM_PAGE_REPLY = 0x49

    /**
     * PC → 手机：新照片监听开关（相册页打开时开、关掉时关）。
     * 开着时手机端 ContentObserver 发现新照片 → 经既有 0x40 通知推送（复用通知同步链路，
     * 不新开帧族）。载荷 `[0x4A][on u8]`：1=开 0=关。
     */
    const val CMD_ALBUM_WATCH = 0x4A

    /**
     * PC → 手机：取一张缩略图。载荷 `[0x4B][id u32 LE]`（MediaStore `_ID`）。
     * 本体（JPEG）经 9512 推回，文件名 `albumthumb_<id>.jpg`（PC 端虚拟主机映射可直接当
     * <img> 地址）；失败发 0x47（path = "album:thumb:<id>"，与取文件失败同一回执帧）。
     */
    const val CMD_ALBUM_THUMB = 0x4B

    /** 相册单页条目数：60 张 ≈ 每条 ~250B 路径 + 名字，一页帧 < 40KB，远小于 4MB 帧上限 */
    const val ALBUM_PAGE_SIZE = 60

    /** 缩略图边长（px，正方形）：网格单元 2x density 足够清晰，单张 JPEG 几十 KB */
    const val ALBUM_THUMB_PX = 256

    /**
     * 一条相册条目。[path] 是原文件绝对路径（MediaStore `DATA`）—— PC 端「看原图 / 拖拽导出」
     * 复用既有 0x46（文件本体原样过 9512，无损），所以路径拿不到（部分机型 DATA 为空）的条目
     * 要从列表里剔除，否则点了取不到。
     */
    data class AlbumEntry(val id: Long, val path: String, val name: String, val added: Long, val size: Long)

    /** 组 0x48 相册分页请求：`[0x48][page u32 LE]`（从 0 起，按 DATE_ADDED 倒序）。 */
    fun packAlbumPageReq(page: Int): ByteArray {
        val body = ByteArray(5)
        body[0] = CMD_ALBUM_PAGE.toByte()
        var v = page
        for (i in 0 until 4) {
            body[1 + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return body
    }

    /** 组 0x4A 监听开关：`[0x4A][on u8]`（1=开 0=关）。 */
    fun packAlbumWatchReq(on: Boolean): ByteArray =
        byteArrayOf(CMD_ALBUM_WATCH.toByte(), if (on) 1 else 0)

    /** 组 0x4B 缩略图请求：`[0x4B][id u32 LE]`。 */
    fun packAlbumThumbReq(id: Long): ByteArray {
        val body = ByteArray(5)
        body[0] = CMD_ALBUM_THUMB.toByte()
        var v = id.toInt()
        for (i in 0 until 4) {
            body[1 + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return body
    }

    /**
     * 组 0x49 相册分页应答：`[0x49][err u8][page u32 LE][count u16]`
     * + count × `[id u32 LE][added u32 LE][size u64 LE][pathLen u16][path][nameLen u16][name]`。
     * added 为秒（MediaStore `DATE_ADDED` 口径），PC 端按它折算本地日期分组。
     */
    fun packAlbumPageReply(err: Int, page: Int, entries: List<AlbumEntry>): ByteArray {
        val shown = if (err == PHONEFILE_ERR_NONE) entries.take(ALBUM_PAGE_SIZE) else emptyList()
        val head = ByteArray(1 + 1 + 4 + 2)
        var off = 0
        head[off++] = CMD_ALBUM_PAGE_REPLY.toByte()
        head[off++] = err.toByte()
        var v = page
        for (i in 0 until 4) {
            head[off++] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        head[off++] = (shown.size and 0xFF).toByte()
        head[off] = ((shown.size ushr 8) and 0xFF).toByte()
        val parts = ArrayList<ByteArray>(shown.size + 1)
        parts.add(head)
        for (e in shown) {
            val p = utf8Clamp(e.path, PHONEFILE_PATH_MAX)
            val n = utf8Clamp(e.name, PHONEFILE_NAME_MAX)
            val rec = ByteArray(4 + 4 + 8 + 2 + p.size + 2 + n.size)
            var o = 0
            var id = e.id.toInt()
            for (i in 0 until 4) {
                rec[o++] = (id and 0xFF).toByte()
                id = id ushr 8
            }
            var ad = e.added.toInt()
            for (i in 0 until 4) {
                rec[o++] = (ad and 0xFF).toByte()
                ad = ad ushr 8
            }
            var sz = e.size
            for (i in 0 until 8) {
                rec[o++] = (sz and 0xFF).toByte()
                sz = sz ushr 8
            }
            rec[o++] = (p.size and 0xFF).toByte()
            rec[o++] = ((p.size ushr 8) and 0xFF).toByte()
            p.copyInto(rec, o); o += p.size
            rec[o++] = (n.size and 0xFF).toByte()
            rec[o] = ((n.size ushr 8) and 0xFF).toByte()
            n.copyInto(rec, o + 1)
            parts.add(rec)
        }
        return parts.reduce { a, b -> a + b }
    }

    /** 目录应答的单目录条目上限：防超大目录把 4MB 帧塞爆（名字 512B 上限，200 条 ≈ 110KB） */
    const val PHONEFILE_MAX_ENTRIES = 200

    /** 单条名字上限（UTF-8 字节），与 u16 长度段相称 */
    const val PHONEFILE_NAME_MAX = 512

    /** 目录应答的路径上限（与请求同段） */
    const val PHONEFILE_PATH_MAX = 1024

    /** 一条目录条目：isDir=true 时 size 无意义（恒 0） */
    data class PhoneFileEntry(val name: String, val isDir: Boolean, val size: Long)

    /** 组 0x44 浏览请求：`[0x44][pathLen u16 LE][path]`；path 空 = 手机回报根入口列表。 */
    fun packPhoneFileListReq(path: String): ByteArray {
        val p = utf8Clamp(path, PHONEFILE_PATH_MAX)
        val body = ByteArray(1 + 2 + p.size)
        body[0] = CMD_PHONE_FILE_LIST.toByte()
        body[1] = (p.size and 0xFF).toByte(); body[2] = ((p.size ushr 8) and 0xFF).toByte()
        p.copyInto(body, 3)
        return body
    }

    /** 组 0x46 取文件请求：`[0x46][pathLen u16 LE][path]`（绝对路径，只收普通文件）。 */
    fun packPhoneFilePullReq(path: String): ByteArray {
        val p = utf8Clamp(path, PHONEFILE_PATH_MAX)
        val body = ByteArray(1 + 2 + p.size)
        body[0] = CMD_PHONE_FILE_PULL.toByte()
        body[1] = (p.size and 0xFF).toByte(); body[2] = ((p.size ushr 8) and 0xFF).toByte()
        p.copyInto(body, 3)
        return body
    }

    /**
     * 组 0x45 目录应答：`[0x45][err u8][pathLen u16][path][count u16]`
     * + count × `[nameLen u16][name][dir u8][size u64 LE]`（均小端；名字条目按调用方顺序）。
     * err != 0 时 count 恒 0、无条目段（调用方用 path 回显是哪个目录失败）。
     */
    fun packPhoneFileListReply(err: Int, path: String, entries: List<PhoneFileEntry>): ByteArray {
        val p = utf8Clamp(path, PHONEFILE_PATH_MAX)
        val shown = if (err == PHONEFILE_ERR_NONE) entries.take(PHONEFILE_MAX_ENTRIES) else emptyList()
        val body = ByteArray(1 + 1 + 2 + p.size + 2)
        var off = 0
        body[off++] = CMD_PHONE_FILE_LIST_REPLY.toByte()
        body[off++] = err.toByte()
        body[off++] = (p.size and 0xFF).toByte(); body[off++] = ((p.size ushr 8) and 0xFF).toByte()
        p.copyInto(body, off); off += p.size
        body[off++] = (shown.size and 0xFF).toByte(); body[off++] = ((shown.size ushr 8) and 0xFF).toByte()
        // 条目段逐条追加（变长，不能预先定总长）
        val parts = ArrayList<ByteArray>(shown.size + 1)
        parts.add(body)
        for (e in shown) {
            val n = utf8Clamp(e.name, PHONEFILE_NAME_MAX)
            val rec = ByteArray(2 + n.size + 1 + 8)
            rec[0] = (n.size and 0xFF).toByte(); rec[1] = ((n.size ushr 8) and 0xFF).toByte()
            n.copyInto(rec, 2)
            rec[2 + n.size] = if (e.isDir) 1 else 0
            var sz = e.size
            for (i in 0 until 8) {
                rec[3 + n.size + i] = (sz and 0xFF).toByte()
                sz = sz ushr 8
            }
            parts.add(rec)
        }
        return parts.reduce { a, b -> a + b }
    }

    /** 组 0x47 取文件失败回执：`[0x47][err u8][pathLen u16 LE][path]`。成功不发（文件本体即回执）。 */
    fun packPhoneFileFail(err: Int, path: String): ByteArray {
        val p = utf8Clamp(path, PHONEFILE_PATH_MAX)
        val body = ByteArray(1 + 1 + 2 + p.size)
        body[0] = CMD_PHONE_FILE_FAIL.toByte()
        body[1] = err.toByte()
        body[2] = (p.size and 0xFF).toByte(); body[3] = ((p.size ushr 8) and 0xFF).toByte()
        p.copyInto(body, 4)
        return body
    }

    /** 通知同步各字段的字节上限（u16 长度段的单字段上限是 65535，这里按气泡可读性收紧） */
    private const val NOTIFY_KEY_MAX = 512
    private const val NOTIFY_TITLE_MAX = 512
    private const val NOTIFY_TEXT_MAX = 4096
    private const val NOTIFY_APP_MAX = 64

    /** UTF-8 字节截断：超长字符串只取前 max 字节（不在多字节字符中间断开） */
    private fun utf8Clamp(s: String, max: Int): ByteArray {
        val all = s.toByteArray(Charsets.UTF_8)
        if (all.size <= max) return all
        var end = max
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--   // 回退到字符头
        return all.copyOf(end)
    }

    /**
     * 组 0x40 通知推送帧：
     * `[0x40][keyLen u16 LE][key][titleLen u16 LE][title][textLen u16 LE][text][appLen u8][app]`
     * （UTF-8；key 为 Android `StatusBarNotification.key`，PC 端回发 0x42 时原样带回）。
     * @return 载荷；key 为空（无法消除/去重）返回 null，调用方直接丢弃
     */
    fun packNotifyPush(key: String, title: String, text: String, app: String): ByteArray? {
        if (key.isEmpty()) return null
        val kb = utf8Clamp(key, NOTIFY_KEY_MAX)
        val tb = utf8Clamp(title, NOTIFY_TITLE_MAX)
        val xb = utf8Clamp(text, NOTIFY_TEXT_MAX)
        val ab = utf8Clamp(app, NOTIFY_APP_MAX)
        val body = ByteArray(1 + 2 + kb.size + 2 + tb.size + 2 + xb.size + 1 + ab.size)
        var off = 0
        body[off++] = CMD_NOTIFY_PUSH.toByte()
        body[off++] = (kb.size and 0xFF).toByte(); body[off++] = ((kb.size ushr 8) and 0xFF).toByte()
        kb.copyInto(body, off); off += kb.size
        body[off++] = (tb.size and 0xFF).toByte(); body[off++] = ((tb.size ushr 8) and 0xFF).toByte()
        tb.copyInto(body, off); off += tb.size
        body[off++] = (xb.size and 0xFF).toByte(); body[off++] = ((xb.size ushr 8) and 0xFF).toByte()
        xb.copyInto(body, off); off += xb.size
        body[off++] = ab.size.toByte()
        ab.copyInto(body, off)
        return body
    }

    /**
     * 组 0x42 通知消除帧：`[0x42][keyLen u16 LE][key]`。key 为空返回 null（无事可消除）。
     */
    fun packNotifyDismiss(key: String): ByteArray? {
        if (key.isEmpty()) return null
        val kb = utf8Clamp(key, NOTIFY_KEY_MAX)
        val body = ByteArray(1 + 2 + kb.size)
        body[0] = CMD_NOTIFY_DISMISS.toByte()
        body[1] = (kb.size and 0xFF).toByte()
        body[2] = ((kb.size ushr 8) and 0xFF).toByte()
        kb.copyInto(body, 3)
        return body
    }

    private val crcTable = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            // 标准 CRC-32(IEEE 802.3) 表生成：每轮右移 1 位；此前误写成 ushr 8 导致
            // 整张表错误，手机收发帧 CRC 与 PC 端 frame.cpp(c >> 1) 不一致 —— 所有帧校验
            // 失败、pong 被丢弃，触发 24s 死亡检测死循环（223.29.3 用 ushr 1 正常）。
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            t[i] = c
        }
    }

    /** zlib / IEEE 802.3 CRC32（初值全 1、结果取反），与 `shared/src/frame.cpp` 的 crc32 等价 */
    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var c = -1
        for (i in offset until offset + length) {
            c = (c ushr 8) xor crcTable[(c xor data[i].toInt()) and 0xFF]
        }
        return c.inv()
    }

    /**
     * 组一帧（帧头 + body + 尾部 CRC32）。
     * @param streamId 通道号；控制面用 [STREAM_CONTROL]
     * @param body     业务载荷（不含 CRC）
     * @param seq      帧序号（同一逻辑帧的各分片相同）
     */
    fun build(streamId: Int, body: ByteArray, seq: Int, flags: Int = 0): ByteArray {
        val payloadLen = body.size + CRC_SIZE
        val out = ByteArray(HEADER_SIZE + payloadLen)
        out[0] = 'A'.code.toByte()
        out[1] = 'P'.code.toByte()
        out[2] = 'X'.code.toByte()
        out[3] = '1'.code.toByte()
        out[4] = streamId.toByte()
        out[5] = flags.toByte()
        putU16(out, 6, 0)                 // headerExtWords
        putU32(out, 8, payloadLen)
        putU32(out, 12, seq)
        body.copyInto(out, HEADER_SIZE)
        putU32(out, HEADER_SIZE + body.size, crc32(body))
        return out
    }

    // ———————————————————— 远程输入组帧 ————————————————————

    /**
     * 组 REQUEST_INPUT 帧（A→B）：请求对端设备输入。
     *
     * ★ 线上布局必须是 `[0x25, hintLen(u8), hint(UTF-8)]` —— 另三处实现都是这个：
     *   · PC 发送 `ctrl9511.cpp::Ctrl9511Client::sendRequestInput`
     *   · PC 解析 `ctrl9511.cpp` 的 `Ctrl9511Server::dispatch`（`hintLen = p[1]`、文本从 `p+2`）
     *   · 手机 / TV 解析 `onRequestInput`（`hintLen = body[1]`、文本从 `body[2]`）
     *
     * **v1.7 修正**：本函数此前在 cmd 后多塞了 4 字节 `sourceId`，与上述四处**都不兼容** ——
     * 对端会把 `sourceId` 的最低字节当 hintLen、把后续字节当提示文字，表现为
     * "输入面板弹出来了但提示是乱码"。此前无调用方，属**潜在陷阱**（第一个调用它的人就会踩），
     * 故直接对齐线上布局。
     *
     * @param hint 输入框提示文字（如 "搜索"、"输入网址"），超 255 字节按 u8 上限截断
     * @param sourceId 保留入参（防环设计预留）：当前**不上线** —— 没有任何解析方读取它，
     *                 真要做防环需先改全部四处解析
     */
    fun packRequestInput(hint: String, sourceId: Int): ByteArray {
        val all = hint.toByteArray(Charsets.UTF_8)
        val hintBytes = if (all.size > 255) all.copyOf(255) else all
        val body = ByteArray(2 + hintBytes.size)
        body[0] = INPUT_REQUEST.toByte()
        body[1] = hintBytes.size.toByte()   // hintLen：u8（与 PC 侧 `b[1] = bytes.size()` 一致）
        hintBytes.copyInto(body, 2)
        return body
    }

    /**
     * 组 INPUT_TEXT 帧（B→A）：实时输入文本。
     * @param flags INPUT_FLAG_* 位组合
     * @param text  输入的文本（增量/完整）
     */
    fun packInputText(flags: Int, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val body = ByteArray(1 + 1 + 2 + textBytes.size)
        body[0] = INPUT_TEXT.toByte()
        body[1] = flags.toByte()
        putU16(body, 2, textBytes.size)
        textBytes.copyInto(body, 4)
        return body
    }

    /**
     * 组 INPUT_DONE 帧（B→A）：输入完成。
     */
    fun packInputDone(): ByteArray {
        return byteArrayOf(INPUT_DONE.toByte())
    }

    // ———————————————————— 帧解析工具 ————————————————————

    /** 校验帧头 magic；不是 'APX1' 则返回 false（用于收流对齐） */
    fun isMagic(buf: ByteArray, off: Int, len: Int): Boolean =
        len - off >= 4 &&
            buf[off] == 'A'.code.toByte() &&
            buf[off + 1] == 'P'.code.toByte() &&
            buf[off + 2] == 'X'.code.toByte() &&
            buf[off + 3] == '1'.code.toByte()

    /** 读帧头里的 payloadLen（小端 u32） */
    fun payloadLenAt(buf: ByteArray, off: Int): Int = getU32(buf, off + 8)

    /** 读帧头里的 streamId */
    fun streamIdAt(buf: ByteArray, off: Int): Int = buf[off + 4].toInt() and 0xFF

    /** 读帧头里的 flags */
    fun flagsAt(buf: ByteArray, off: Int): Int = buf[off + 5].toInt() and 0xFF

    /** 读帧头里的 seq */
    fun seqAt(buf: ByteArray, off: Int): Int = getU32(buf, off + 12)

    /** 载荷剔除尾部 u32 CRC 后的 body 长度（协议：payloadLen 含那 4 字节）；非法返回 -1 */
    fun bodyLenOf(payloadLen: Int): Int = if (payloadLen < CRC_SIZE) -1 else payloadLen - CRC_SIZE

    /**
     * 校验一帧尾部的 u32 CRC32（覆盖 body，即 payload 去掉最后 4 字节）。
     *
     * v184：此前接收侧**完全不校验** —— 一旦缓冲错位或链路进了脏数据，
     * 坏载荷会被直接喂给解码器 / AudioTrack / 注入器（花屏、爆音、乱点）。
     * 与 PC 侧 `apx::verifyPayload` 行为一致。
     *
     * @return true = 校验通过；false = 该帧应丢弃（缓冲位数不足也返回 false）
     */
    fun verify(buf: ByteArray, off: Int, payloadLen: Int): Boolean {
        if (payloadLen < CRC_SIZE) return false
        val bodyLen = payloadLen - CRC_SIZE
        val crcAt = off + HEADER_SIZE + bodyLen
        if (crcAt + 4 > buf.size) return false
        return crc32(buf, off + HEADER_SIZE, bodyLen) == getU32(buf, crcAt)
    }

    /**
     * 取出 body（**不含**尾部 CRC）。接收侧普遍不校验 CRC，但消费方（解码器 /
     * AudioTrack / JPEG 预览）只想要干净载荷，所以统一在这里剥掉。
     * @return body 副本；越界或长度非法返回 null
     */
    fun bodyAt(buf: ByteArray, off: Int, payloadLen: Int): ByteArray? {
        val n = bodyLenOf(payloadLen)
        if (n < 0 || off + HEADER_SIZE < 0) return null
        val end = off + HEADER_SIZE + n
        if (end > buf.size) return null
        return buf.copyOfRange(off + HEADER_SIZE, end)
    }

    /** 整个帧的字节数（帧头 + 载荷） */
    fun totalSize(payloadLen: Int): Int = HEADER_SIZE + payloadLen

    private fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(dst: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun getU32(src: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or (((src[off + i].toInt() and 0xFF)) shl (8 * i))
        return v
    }
}
