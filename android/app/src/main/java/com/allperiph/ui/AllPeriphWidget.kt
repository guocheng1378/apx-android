package com.allperiph.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.allperiph.R
import com.allperiph.wireless.ControlTarget

/**
 * 桌面小组件：显示全能外设连接状态和设备名。
 *
 * 每次 AppWidgetManager 触发更新时读取 [ControlTarget] 的实时状态，
 * 连接时显示绿色 + 设备名，未连接时灰色 + "未连接"。
 */
class AllPeriphWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) updateOne(context, appWidgetManager, id)
    }

    companion object {

        /** 供连接状态变化时主动刷新所有小组件 */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, AllPeriphWidget::class.java))
            for (id in ids) updateOne(context, mgr, id)
        }

        private fun updateOne(
            ctx: Context,
            mgr: AppWidgetManager,
            id: Int,
        ) {
            val connected = ControlTarget.isControlling()
            val deviceName = ControlTarget.label.ifBlank { "未知设备" }

            val views = RemoteViews(ctx.packageName, R.layout.widget_all_periph)

            // 状态文字
            views.setTextViewText(
                R.id.widget_status,
                if (connected) "已连接" else "未连接",
            )

            // 状态颜色
            val statusColor = if (connected) 0xFF34D399.toInt() else 0xFF9BA1AA.toInt()
            views.setTextColor(R.id.widget_status, statusColor)

            // 设备名
            views.setTextViewText(
                R.id.widget_device_name,
                if (connected) deviceName else "—",
            )

            // 状态指示灯颜色
            val dotColor = if (connected) 0xFF34D399.toInt() else 0xFF6E747C.toInt()
            views.setInt(R.id.widget_status_dot, "setBackgroundColor", dotColor)

            mgr.updateAppWidget(id, views)
        }
    }
}