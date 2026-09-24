package com.allperiph.gadget

import com.allperiph.core.AudioConst
import com.allperiph.core.GadgetConst
import com.allperiph.core.SysPath
import com.allperiph.core.UsbId

enum class GadgetFeature(
    val instance: String,
    val enabledByDefault: Boolean,
    val optional: Boolean,
    val label: String,
) {
    HID("hid.usb0", true, false, "f_hid 复合 HID（传感器/触控/按键/电池/Vendor）"),
    ACM("acm.usb0", true, false, "f_acm CDC ACM（GPS NMEA → COM 口）"),
    UAC2("uac2.usb0", true, true, "f_uac2 声卡"),
    NCM("ncm.usb0", false, true, "f_ncm 网卡"),
    FFS("ffs.apx", false, true, "f_fs FunctionFS（副屏 bulk）"),
    ;

    companion object {
        fun defaults(): Set<GadgetFeature> = entries.filter { it.enabledByDefault }.toSet()
    }
}

data class GadgetOptions(
    val gadget: String = GadgetConst.GADGET_NAME,
    val config: String = GadgetConst.CONFIG_NAME,
    val features: Set<GadgetFeature> = GadgetFeature.defaults(),
    val bcdUsb: String = UsbId.BCD_USB_20,
    val serial: String = UsbId.SERIAL,
    val maxPowerMa: Int = 500,
    val reportLength: Int = GadgetConst.HID_REPORT_LENGTH_FALLBACK,
    val reportDescSource: String? = null,
    val configfsRoot: String = SysPath.CONFIGFS_USB_GADGET,
    val reuse: Boolean = false,
) {
    fun root(): String = "$configfsRoot/$gadget"
    fun configDir(): String = "${root()}/configs/$config"
    fun functionDir(f: GadgetFeature): String = "${root()}/functions/${f.instance}"
}

data class Step(
    val cmd: String,
    val optional: Boolean = false,
)

object ConfigFsLayout {

    fun mount(o: GadgetOptions, udc: String): List<Step> {
        if (o.reuse) return mountReuse(o)

        val g = o.root()
        val steps = mutableListOf<Step>()

        steps += Step("mkdir -p '$g'")
        steps += Step("printf '%s' '${UsbId.ID_VENDOR}' > '$g/idVendor'")
        steps += Step("printf '%s' '${UsbId.ID_PRODUCT}' > '$g/idProduct'")
        steps += Step("printf '%s' '${UsbId.BCD_DEVICE}' > '$g/bcdDevice'")
        steps += Step("printf '%s' '${o.bcdUsb}' > '$g/bcdUSB'")

        steps += Step("mkdir -p '$g/strings/${UsbId.LANG_US}'")
        steps += Step("printf '%s' '${o.serial}' > '$g/strings/${UsbId.LANG_US}/serialnumber'")
        steps += Step("printf '%s' '${UsbId.MANUFACTURER}' > '$g/strings/${UsbId.LANG_US}/manufacturer'")
        steps += Step("printf '%s' '${UsbId.PRODUCT}' > '$g/strings/${UsbId.LANG_US}/product'")

        steps += Step("mkdir -p '${o.configDir()}/strings/${UsbId.LANG_US}'")
        steps += Step("printf '%s' '${UsbId.CONFIGURATION}' > '${o.configDir()}/strings/${UsbId.LANG_US}/configuration'")
        steps += Step("printf '%s' '${o.maxPowerMa}' > '${o.configDir()}/MaxPower'")

        steps += Step("for l in '${o.configDir()}'/*; do rm -f \"\$l\" 2>/dev/null; done", optional = true)

        for (f in o.features.sortedBy { it.ordinal }) {
            val fd = o.functionDir(f)
            val optional = f.optional
            steps += Step("mkdir -p '$fd'", optional)
            steps += functionProps(f, fd, o)
            steps += Step("rm -f '${o.configDir()}/${f.instance}' 2>/dev/null", optional = true)
            steps += Step("cd '${o.configDir()}' && ln -s '../../functions/${f.instance}' '${f.instance}'", optional)
        }

        if (GadgetFeature.FFS in o.features) {
            for (f in RELEASABLE_FFS) {
                steps += Step("{ umount '/dev/usb-ffs/$f' 2>&1; echo \"umount $f rc=\$?\"; } >> /data/local/tmp/apx_ffs.txt", optional = true)
            }
            steps += Step("mkdir -p '${SysPath.FFS_MOUNT_DIR}'", optional = true)
            steps += Step("umount '${SysPath.FFS_MOUNT_DIR}' 2>/dev/null", optional = true)
            steps += Step("{ mount -t functionfs '${SysPath.FFS_INSTANCE}' '${SysPath.FFS_MOUNT_DIR}' 2>&1; echo \"ffs mount rc=\$?\"; } >> /data/local/tmp/apx_ffs.txt", optional = true)
            steps += Step("echo '--- ffs instances:' >> /data/local/tmp/apx_ffs.txt; ls '/dev/usb-ffs' >> /data/local/tmp/apx_ffs.txt 2>&1", optional = true)
        }

        steps += Step("for u in /config/usb_gadget/*/UDC; do echo \"\$u rc=\$(echo '' > \"\$u\" 2>&1; echo \$?)\"; done >> /data/local/tmp/apx_unbind.txt 2>&1", optional = true)
        steps += Step("for i in \$(seq 12); do s=\$(cat '/sys/class/udc/$udc/state' 2>/dev/null); case \"\$s\" in *configured*|*addressed*|*default*) sleep 0.5;; *) break;; esac; done; echo \"udc-state=[\$s] after \$i rounds\" >> /data/local/tmp/apx_ffs.txt", optional = true)

        o.reportDescSource?.let { src ->
            val hidFd = o.functionDir(GadgetFeature.HID)
            steps += Step("src=\$(wc -c < '$src' 2>/dev/null || echo 0); rd=\$(wc -c < '$hidFd/report_desc' 2>/dev/null || echo 0); ok=0; if [ \"\$rd\" = \"\$src\" ]; then ok=1; elif [ \"\$src\" != \"0\" ] && head -c \"\$src\" '$hidFd/report_desc' 2>/dev/null | cmp -s - '$src'; then ok=1; fi; echo \"hid desc src=\$src device=\$rd ok=\$ok\" >> /data/local/tmp/apx_ffs.txt; test \"\$ok\" = \"1\"")
        }

        steps += Step("ok=0; for t in 1 2 3; do if printf '%s' '$udc' > '$g/UDC' 2>/tmp/apx_udc_err; then ok=1; break; else echo \"udc try \$t rc=\$? err=\$(cat /tmp/apx_udc_err)\" >> /data/local/tmp/apx_unbind.txt; sleep 1; fi; done; test \$ok -eq 1")
        steps += Step("chmod 666 '${SysPath.HIDG_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.ACM_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.FFS_MOUNT_DIR}'/ep* 2>/dev/null", optional = true)
        steps += Step("chmod 666 /dev/snd/pcmC*D* 2>/dev/null", optional = true)
        steps += Step("dmesg > /data/local/tmp/apx_dmesg.txt 2>&1", optional = true)
        steps += Step("dmesg | grep -i -E 'udc|gadget|ffs|configfs|video' > /data/local/tmp/apx_dmesg.txt 2>&1", optional = true)
        return steps
    }

    private fun mountReuse(o: GadgetOptions): List<Step> {
        val steps = mutableListOf<Step>()
        for (f in o.features.sortedBy { it.ordinal }) {
            val fd = o.functionDir(f)
            val optional = f.optional
            steps += Step("rmdir '$fd' 2>/dev/null", optional = true)
            steps += Step("mkdir '$fd'", optional = true)
            steps += functionProps(f, fd, o)
            steps += Step("rm -f '${o.configDir()}/${f.instance}' 2>/dev/null", optional = true)
            steps += Step("cd '${o.configDir()}' && ln -s '../../functions/${f.instance}' '${f.instance}'", optional)
        }
        steps += Step("chmod 666 '${SysPath.HIDG_DEVICE}'", optional = true)
        steps += Step("chmod 666 '${SysPath.ACM_DEVICE}'", optional = true)
        return steps
    }

    private fun functionProps(f: GadgetFeature, fd: String, o: GadgetOptions): List<Step> {
        val optional = f.optional
        val steps = mutableListOf<Step>()
        when (f) {
            GadgetFeature.HID -> {
                steps += Step("printf '%s' '${GadgetConst.HID_SUBCLASS_NONE}' > '$fd/subclass'", optional)
                steps += Step("printf '%s' '${GadgetConst.HID_PROTOCOL_NONE}' > '$fd/protocol'", optional)
                steps += Step("printf '%s' '${o.reportLength}' > '$fd/report_length'", optional)
                val src = o.reportDescSource
                if (src == null) {
                    steps += Step("false # missing HID report descriptor")
                } else {
                    steps += Step("cp '$src' '$fd/report_desc'", optional = o.reuse)
                }
            }
            GadgetFeature.UAC2 -> {
                steps += Step("printf '%s' '${AudioConst.CHANNEL_MASK_STEREO}' > '$fd/c_chmask'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_RATE}' > '$fd/c_srate'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_SIZE_BYTES}' > '$fd/c_ssize'", optional)
                steps += Step("printf '%s' '${AudioConst.CHANNEL_MASK_STEREO}' > '$fd/p_chmask'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_RATE}' > '$fd/p_srate'", optional)
                steps += Step("printf '%s' '${AudioConst.SAMPLE_SIZE_BYTES}' > '$fd/p_ssize'", optional)
                steps += Step("printf '%s' '${AudioConst.C_TERMINAL}' > '$fd/c_terminal'", optional)
                steps += Step("printf '%s' '${AudioConst.P_TERMINAL}' > '$fd/p_terminal'", optional)
            }
            else -> Unit
        }
        return steps
    }

    fun unmount(o: GadgetOptions): List<Step> {
        val g = o.root()
        val steps = mutableListOf<Step>()
        if (o.reuse) {
            for (f in o.features.sortedByDescending { it.ordinal }) {
                steps += Step("rm -f '${o.configDir()}/${f.instance}'", optional = true)
                steps += Step("rmdir '${o.functionDir(f)}' 2>/dev/null", optional = true)
            }
            return steps
        }
        steps += Step("echo '' > '$g/UDC'", optional = true)
        steps += Step("umount '${SysPath.FFS_MOUNT_DIR}' 2>/dev/null", optional = true)
        return steps
    }

    fun forceClean(o: GadgetOptions): List<Step> {
        val g = o.root()
        val steps = mutableListOf<Step>()
        steps += Step("echo '' > '$g/UDC' 2>/dev/null", optional = true)
        for (f in o.features.sortedByDescending { it.ordinal }) {
            steps += Step("rm -f '$g/configs/${o.config}/${f.instance}' 2>/dev/null", optional = true)
            steps += Step("rmdir '$g/functions/${f.instance}' 2>/dev/null", optional = true)
        }
        steps += Step("rmdir '$g/configs/${o.config}/strings/${UsbId.LANG_US}' 2>/dev/null", optional = true)
        steps += Step("rmdir '$g/configs/${o.config}' 2>/dev/null", optional = true)
        steps += Step("rmdir '$g/strings/${UsbId.LANG_US}' 2>/dev/null", optional = true)
        return steps
    }

    private val RELEASABLE_FFS = listOf("ipcr", "aoa", "ctrl")
}
