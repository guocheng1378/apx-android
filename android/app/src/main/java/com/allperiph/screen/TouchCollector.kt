package com.allperiph.screen

import android.view.MotionEvent

/**
 * 触控事件采集器：Android MotionEvent → HID Digitizer 报告。
 *
 * 输出格式（对齐 shared/hid_layout.h DigitizerContact）：
 *   [0]     Report ID = 3 (kReportDigitizer)
 *   [1]     flags: bit1=tip_switch(手指按下)
 *   [2]     contact_count
 *   [3]     reserved (0)
 *   [4..11] tsNs (u64 LE)
 *   [12..]  contacts: 每个 contact 8B
 *           {u16 contactId, u16 x, u16 y, u16 pressure}
 *
 * 坐标归一化到 0..65535（kPtpLogicalMaxX/Y）。
 */
class TouchCollector(
    private val viewWidth: Int,
    private val viewHeight: Int
) {
    var onReport: ((ByteArray) -> Boolean)? = null

    fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount.coerceAtMost(MAX_CONTACTS)

        // 标志位
        var flags: Byte = 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> {
                flags = (flags.toInt() or 0x02).toByte()  // tip_switch = bit1
            }
        }

        // 报告长度
        val reportSize = HEADER_SIZE + pointerCount * CONTACT_SIZE
        val report = ByteArray(reportSize)

        report[0] = REPORT_ID
        report[1] = flags
        report[2] = pointerCount.toByte()
        report[3] = 0  // reserved

        // tsNs (u64 LE)
        val tsNs = System.nanoTime()
        for (i in 0 until 8) {
            report[4 + i] = ((tsNs shr (8 * i)) and 0xFF).toByte()
        }

        // contacts
        for (i in 0 until pointerCount) {
            val offset = HEADER_SIZE + i * CONTACT_SIZE
            val x = ((event.getX(i) / viewWidth) * MAX_X).toInt().coerceIn(0, MAX_X)
            val y = ((event.getY(i) / viewHeight) * MAX_Y).toInt().coerceIn(0, MAX_Y)
            val pressure = (event.getPressure(i) * MAX_PRESSURE).toInt().coerceIn(0, MAX_PRESSURE)

            report[offset]     = (event.getPointerId(i) and 0xFF).toByte()
            report[offset + 1] = 0
            report[offset + 2] = (x and 0xFF).toByte()
            report[offset + 3] = ((x shr 8) and 0xFF).toByte()
            report[offset + 4] = (y and 0xFF).toByte()
            report[offset + 5] = ((y shr 8) and 0xFF).toByte()
            report[offset + 6] = (pressure and 0xFF).toByte()
            report[offset + 7] = ((pressure shr 8) and 0xFF).toByte()
        }

        return onReport?.invoke(report) ?: false
    }

    companion object {
        private const val REPORT_ID: Byte = 3  // kReportDigitizer
        private const val MAX_CONTACTS = 10    // kMaxContacts
        private const val HEADER_SIZE = 12     // DigitizerHeader
        private const val CONTACT_SIZE = 8     // DigitizerContact
        private const val MAX_X = 65535
        private const val MAX_Y = 65535
        private const val MAX_PRESSURE = 65535
    }
}
