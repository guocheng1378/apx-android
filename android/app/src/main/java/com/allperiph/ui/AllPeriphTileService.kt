package com.allperiph.ui

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.allperiph.core.ApxNative
import com.allperiph.shared.net.ControlTarget
import com.allperiph.shared.net.TcpClient

/**
 * 快捷设置 Tile：下拉通知栏一键开关触控板模式。
 * 当 TCP 已连接时显示「已连接」，否则显示「未连接」。
 */
class AllPeriphTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // 点击时刷新状态
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val connected = ControlTarget.isConnected()
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (connected) "全能外设·已连接" else "全能外设·未连接"
        tile.subtitle = if (connected) "触控板可用" else "点击刷新状态"
        tile.updateTile()
    }

    companion object {
        private const val TAG = "AllPeriphTile"
    }
}