#!/system/bin/sh
# =============================================================================
# 全能外设 · ConfigFS 复合设备挂载 / 卸载 / 自检脚本
# 归属：L2（android-devices）。与 App 内 `gadget/ConfigFsLayout.kt` 保持同一挂载顺序。
#
# 用途：脱离 App 单独验证 MS1（加速度计 → HID → PC 传感器面板）链路。
#       App 挂载失败时，用它判断是「内核/ConfigFS 不支持」还是「App 逻辑问题」。
#
# 用法（Windows PowerShell / Linux 均可）：
#   adb push scripts/apx_gadget.sh /data/local/tmp/apx_gadget.sh
#   adb shell chmod 0755 /data/local/tmp/apx_gadget.sh
#   adb shell su -c 'sh /data/local/tmp/apx_gadget.sh status'
#   adb shell su -c 'sh /data/local/tmp/apx_gadget.sh up   /data/local/tmp/apx/hid_report_desc.bin'
#   adb shell su -c 'sh /data/local/tmp/apx_gadget.sh down'
#   adb shell su -c 'sh /data/local/tmp/apx_gadget.sh heal  /data/local/tmp/apx/hid_report_desc.bin'
#
# 子命令：
#   status                 打印 UDC / current_speed / 设备节点 / sys.usb.config
#   speed                  只打印 UDC 当前速度（架构 §6.1 速度门禁）
#   up   [desc.bin]        抢占 UDC 并挂载 f_hid + f_acm（desc.bin 默认 $TMP/hid_report_desc.bin）
#   down                   卸载并恢复原有 sys.usb.config
#   heal [desc.bin]        不恢复 USB 配置，直接清场重挂（心跳丢失自愈的人工等价操作）
#
# 硬性要求：必须以 root 执行；退出码 0=成功，非 0=失败（失败会自动回滚 USB 配置）。
# 只使用 POSIX sh 语法（Android /system/bin/sh 为 mksh/toybox，无 bash 数组与 [[ ]]）。
# =============================================================================

GADGET_NAME=apx
CONFIG_NAME=c.1
ID_VENDOR=0x1d6b
ID_PRODUCT=0x0104
BCD_DEVICE=0x0310
MANUFACTURER=AllPeriph
PRODUCT="AllPeriph Composite"
SERIAL=APX00000001
CONFIGURATION="AllPeriph Composite"
LANG_US=0x409
MAXPOWER=500
HID_REPORT_LENGTH=1024

TMP=/data/local/tmp/apx
DEFAULT_DESC="$TMP/hid_report_desc.bin"
SAVED_PROP_FILE="$TMP/.saved_sys_usb_config"

APX_FAIL=0

log()  { printf '[apx] %s\n' "$*"; }
warn() { printf '[apx] WARN: %s\n' "$*" >&2; }
die()  { printf '[apx] ERROR: %s\n' "$*" >&2; APX_FAIL=1; exit 1; }

need_root() {
    if [ "$(id -u 2>/dev/null)" != "0" ]; then
        die "需要 root：改用 su -c 'sh $0 $*' 或 adb root"
    fi
}

# ---------------------------------------------------------------- 环境探测 ----

# ConfigFS 挂载点：优先 Android 通用 /config/usb_gadget，其次内核标准路径
configfs_root() {
    if [ -d /config/usb_gadget ]; then
        printf '%s\n' /config/usb_gadget
        return 0
    fi
    if [ -d /sys/kernel/config/usb_gadget ]; then
        printf '%s\n' /sys/kernel/config/usb_gadget
        return 0
    fi
    mkdir -p /config 2>/dev/null
    mount -t configfs none /config 2>/dev/null
    if [ -d /config/usb_gadget ]; then
        printf '%s\n' /config/usb_gadget
        return 0
    fi
    printf '\n'
    return 1
}

# 第一个 UDC 控制器名；多 UDC 机型可用 APX_UDC 环境变量覆盖
pick_udc() {
    if [ -n "${APX_UDC:-}" ]; then
        printf '%s\n' "$APX_UDC"
        return 0
    fi
    ls /sys/class/udc 2>/dev/null | head -n 1
}

udc_speed() {
    cat "/sys/class/udc/$1/current_speed" 2>/dev/null
}

# ---------------------------------------------------------------- 基本工具 ----

# 写 sysfs/ConfigFS 属性；用 printf 避免 toybox echo 的行为差异与多余换行
w() {
    printf '%s' "$2" > "$1" 2>/dev/null || {
        warn "写 $1 失败"
        APX_FAIL=1
        return 1
    }
    return 0
}

mkdirs() { mkdir -p "$1" 2>/dev/null || { warn "mkdir $1 失败"; APX_FAIL=1; return 1; }; return 0; }

save_prop() {
    mkdir -p "$TMP" 2>/dev/null
    getprop sys.usb.config 2>/dev/null > "$SAVED_PROP_FILE"
    log "已保存原 sys.usb.config=$(cat "$SAVED_PROP_FILE" 2>/dev/null)"
}

restore_prop() {
    saved=""
    [ -f "$SAVED_PROP_FILE" ] && saved=$(cat "$SAVED_PROP_FILE" 2>/dev/null)
    [ -n "$saved" ] || saved=adb
    log "恢复 sys.usb.config=$saved"
    setprop sys.usb.config "$saved" 2>/dev/null
    rm -f "$SAVED_PROP_FILE" 2>/dev/null
}

# 抢占 UDC：架构 §6.2 —— 写 UDC 之前必须让 USB HAL 解绑
release_udc() {
    setprop sys.usb.config none 2>/dev/null || warn "setprop none 失败（厂商 HAL 可能拦截）"
    sleep 1
}

# 解绑 + 清场（不恢复 USB 配置），供 down / heal / 失败回滚共用
teardown_gadget() {
    base=$(configfs_root)
    [ -n "$base" ] || return 0
    g="$base/$GADGET_NAME"
    [ -d "$g" ] || return 0
    printf '' > "$g/UDC" 2>/dev/null
    sleep 0.3
    for f in hid.usb0 acm.usb0 uvc.usb0 uac2.usb0 ncm.usb0; do
        rm -f "$g/configs/$CONFIG_NAME/$f" 2>/dev/null
        rmdir "$g/functions/$f" 2>/dev/null
    done
    rmdir "$g/configs/$CONFIG_NAME/strings/$LANG_US" 2>/dev/null
    rmdir "$g/configs/$CONFIG_NAME" 2>/dev/null
    rmdir "$g/strings/$LANG_US" 2>/dev/null
    rmdir "$g" 2>/dev/null
    rm -rf "$g" 2>/dev/null
    return 0
}

rollback() {
    if [ "$APX_FAIL" != "0" ]; then
        warn "检测到失败，回滚现场"
        teardown_gadget
        restore_prop
    fi
}
trap rollback EXIT

# ---------------------------------------------------------------- 子命令 ------

cmd_status() {
    printf -- '--- sys.usb ---\n'
    printf 'sys.usb.config = %s\n' "$(getprop sys.usb.config 2>/dev/null)"
    printf 'sys.usb.state  = %s\n' "$(getprop sys.usb.state 2>/dev/null)"
    printf -- '--- udc ---\n'
    u=$(pick_udc)
    if [ -z "$u" ]; then
        printf 'no UDC found\n'
    else
        printf 'udc           = %s\n' "$u"
        printf 'current_speed = %s\n' "$(udc_speed "$u")"
        printf 'state         = %s\n' "$(cat "/sys/class/udc/$u/state" 2>/dev/null)"
    fi
    printf -- '--- configfs ---\n'
    base=$(configfs_root)
    printf 'configfs      = %s\n' "${base:-<none>}"
    if [ -n "$base" ] && [ -d "$base/$GADGET_NAME" ]; then
        printf 'gadget UDC    = %s\n' "$(cat "$base/$GADGET_NAME/UDC" 2>/dev/null)"
        ls -1 "$base/$GADGET_NAME/functions" 2>/dev/null | sed 's/^/function       /'
    else
        printf 'gadget        = <not mounted>\n'
    fi
    printf -- '--- device nodes ---\n'
    ls -l /dev/hidg0 2>/dev/null || printf '/dev/hidg0     = <missing>\n'
    ls -l /dev/ttyGS0 2>/dev/null || printf '/dev/ttyGS0    = <missing>\n'
    printf -- '--- descriptor ---\n'
    if [ -s "$DEFAULT_DESC" ]; then
        printf 'report_desc   = %s (%s bytes)\n' "$DEFAULT_DESC" "$(wc -c < "$DEFAULT_DESC" 2>/dev/null)"
    else
        printf 'report_desc   = <missing> %s\n' "$DEFAULT_DESC"
    fi
    return 0
}

cmd_speed() {
    u=$(pick_udc)
    [ -n "$u" ] || die "未找到 UDC（内核未启用 gadget device 模式）"
    s=$(udc_speed "$u")
    printf '%s: %s\n' "$u" "${s:-unknown}"
    case "$s" in
        super-speed-plus|super-speed) printf 'PASS: SuperSpeed，可全速运行\n'; return 0 ;;
        *) printf 'DEGRADED: 非 SuperSpeed，按架构 §6.1 降级运行（副屏受限）\n'; return 0 ;;
    esac
}

cmd_up() {
    need_root up "$@"
    desc="${1:-$DEFAULT_DESC}"

    base=$(configfs_root) || die "ConfigFS 不可用（内核未启用 CONFIG_CONFIGFS_FS / USB_GADGET）"
    [ -n "$base" ] || die "ConfigFS 不可用"
    u=$(pick_udc)
    [ -n "$u" ] || die "未找到 UDC"
    s=$(udc_speed "$u")
    log "UDC=$u current_speed=${s:-unknown}"
    case "$s" in
        super-speed-plus|super-speed)
            BCD_USB=0x0300
            log "SuperSpeed：声明 bcdUSB=0x0300"
            ;;
        *)
            BCD_USB=0x0200
            warn "非 SuperSpeed（${s:-unknown}）：按架构 §6.1 降级为 USB 2.0 声明"
            ;;
    esac

    [ -s "$desc" ] || die "HID 报告描述符缺失：$desc（由 shared/ 生成，App 启动后会导出到该路径）"

    save_prop
    release_udc

    g="$base/$GADGET_NAME"
    c="$g/configs/$CONFIG_NAME"
    mkdirs "$g" || exit 1
    w "$g/idVendor"   "$ID_VENDOR"   || exit 1
    w "$g/idProduct"  "$ID_PRODUCT"  || exit 1
    w "$g/bcdDevice"  "$BCD_DEVICE"  || exit 1
    w "$g/bcdUSB"     "$BCD_USB"     || exit 1

    mkdirs "$g/strings/$LANG_US" || exit 1
    w "$g/strings/$LANG_US/serialnumber"  "$SERIAL"       || exit 1
    w "$g/strings/$LANG_US/manufacturer"  "$MANUFACTURER" || exit 1
    w "$g/strings/$LANG_US/product"       "$PRODUCT"      || exit 1

    mkdirs "$c/strings/$LANG_US" || exit 1
    w "$c/strings/$LANG_US/configuration" "$CONFIGURATION" || exit 1
    w "$c/MaxPower" "$MAXPOWER" || exit 1

    # —— f_hid：复合 HID（传感器/触控/按键/电池/Vendor，6 个 TLC）——
    hid="$g/functions/hid.usb0"
    mkdirs "$hid" || exit 1
    w "$hid/subclass"     0                 || exit 1
    w "$hid/protocol"     0                 || exit 1
    w "$hid/report_length" "$HID_REPORT_LENGTH" || exit 1
    cp "$desc" "$hid/report_desc" 2>/dev/null || die "写入 report_desc 失败"
    ln -s "$hid" "$c/hid.usb0" 2>/dev/null || warn "链接 hid.usb0 失败"

    # —— f_acm：CDC ACM（GPS NMEA → PC COM 口）——
    acm="$g/functions/acm.usb0"
    if mkdirs "$acm"; then
        ln -s "$acm" "$c/acm.usb0" 2>/dev/null || warn "链接 acm.usb0 失败"
    else
        warn "f_acm 不可用：GPS over ACM 将缺失"
    fi

    # —— 预留：MS5 相机/音频与控制面网卡，内核未启用时忽略失败 ——
    for f in uvc.usb0 uac2.usb0 ncm.usb0; do
        if mkdirs "$g/functions/$f" 2>/dev/null; then
            ln -s "$g/functions/$f" "$c/$f" 2>/dev/null && log "已挂载预留 function: $f"
        fi
    done

    # —— 最后一步才绑定 UDC：此前任何失败都不会影响手机原有 USB 功能 ——
    w "$g/UDC" "$u" || die "绑定 UDC 失败（可能仍被 USB HAL 占用）"
    sleep 0.5
    chmod 666 /dev/hidg0 2>/dev/null
    chmod 666 /dev/ttyGS0 2>/dev/null

    APX_FAIL=0
    log "挂载完成：gadget=$g udc=$u speed=${s:-unknown}"
    cmd_status
    return 0
}

cmd_down() {
    need_root down
    teardown_gadget
    restore_prop
    log "卸载完成"
    return 0
}

cmd_heal() {
    need_root heal "$@"
    teardown_gadget
    cmd_up "$@"
}

usage() {
    cat <<'EOF'
用法：sh apx_gadget.sh <status|speed|up [desc.bin]|down|heal [desc.bin]>
  status  打印 UDC / 速度 / 设备节点 / 描述符现状
  speed   只做速度门禁检查（架构 §6.1）
  up      抢占 UDC 并挂载 f_hid + f_acm（desc.bin 默认 /data/local/tmp/apx/hid_report_desc.bin）
  down    卸载并恢复原 sys.usb.config
  heal    清场重挂（心跳丢失自愈的人工等价操作）
环境变量：APX_UDC 可指定 UDC 控制器名。
EOF
}

case "${1:-}" in
    status) shift; cmd_status ;;
    speed)  shift; cmd_speed ;;
    up)     shift; cmd_up "$@" ;;
    down)   shift; cmd_down ;;
    heal)   shift; cmd_heal "$@" ;;
    -h|--help|help) usage ;;
    *) usage; exit 2 ;;
esac
