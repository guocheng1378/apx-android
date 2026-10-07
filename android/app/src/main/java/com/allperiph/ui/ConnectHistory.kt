package com.allperiph.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接历史记录管理器：使用 SharedPreferences 持久化连接历史
 * 支持最多保存 20 条历史记录，按时间倒序排列
 */
object ConnectHistory {
    private const val PREFS_NAME = "connect_history"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_HISTORY = 20
    
    /** 连接历史记录数据类 */
    data class HistoryItem(
        val name: String,
        val host: String,
        val port: Int,
        val timestamp: Long,
        val lastConnected: Long = 0L
    )
    
    /**
     * 添加连接记录到历史
     * @param context 上下文
     * @param name 设备名称
     * @param host 主机地址
     * @param port 端口号
     */
    fun add(context: Context, name: String, host: String, port: Int) {
        val prefs = getPrefs(context)
        val history = getHistoryList(context)
        
        // 检查是否已存在相同连接，如果存在则更新时间
        val existingIndex = history.indexOfFirst { 
            it.host == host && it.port == port 
        }
        
        if (existingIndex >= 0) {
            // 更新已有记录
            val existing = history[existingIndex]
            history[existingIndex] = existing.copy(
                lastConnected = System.currentTimeMillis()
            )
        } else {
            // 添加新记录
            val newItem = HistoryItem(
                name = name,
                host = host,
                port = port,
                timestamp = System.currentTimeMillis(),
                lastConnected = System.currentTimeMillis()
            )
            history.add(0, newItem)
            
            // 超过最大数量时删除最旧的记录
            while (history.size > MAX_HISTORY) {
                history.removeAt(history.lastIndex)
            }
        }
        
        saveHistory(context, history)
    }
    
    /**
     * 获取所有历史记录（按时间倒序）
     * @param context 上下文
     * @return 历史记录列表
     */
    fun getAll(context: Context): List<HistoryItem> {
        return getHistoryList(context)
    }
    
    /**
     * 获取最近的连接记录
     * @param context 上下文
     * @param count 数量，默认 5
     * @return 最近的连接记录列表
     */
    fun getRecent(context: Context, count: Int = 5): List<HistoryItem> {
        return getHistoryList(context).take(count)
    }
    
    /**
     * 删除指定的历史记录
     * @param context 上下文
     * @param host 主机地址
     * @param port 端口号
     */
    fun remove(context: Context, host: String, port: Int) {
        val history = getHistoryList(context)
        history.removeAll { it.host == host && it.port == port }
        saveHistory(context, history)
    }
    
    /**
     * 清空所有历史记录
     * @param context 上下文
     */
    fun clear(context: Context) {
        getPrefs(context).edit().remove(KEY_HISTORY).apply()
    }
    
    /**
     * 获取历史记录数量
     * @param context 上下文
     * @return 记录数量
     */
    fun size(context: Context): Int {
        return getHistoryList(context).size
    }
    
    // —————————————————————————— 内部方法 ——————————————————————————
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private fun getHistoryList(context: Context): MutableList<HistoryItem> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<HistoryItem>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        name = obj.getString("name"),
                        host = obj.getString("host"),
                        port = obj.getInt("port"),
                        timestamp = obj.getLong("timestamp"),
                        lastConnected = obj.getLong("lastConnected")
                    )
                )
            }
            
            // 按最近连接时间倒序排序
            list.sortedByDescending { it.lastConnected }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }
    
    private fun saveHistory(context: Context, history: List<HistoryItem>) {
        val jsonArray = JSONArray()
        
        history.forEach { item ->
            val obj = JSONObject().apply {
                put("name", item.name)
                put("host", item.host)
                put("port", item.port)
                put("timestamp", item.timestamp)
                put("lastConnected", item.lastConnected)
            }
            jsonArray.put(obj)
        }
        
        getPrefs(context).edit()
            .putString(KEY_HISTORY, jsonArray.toString())
            .apply()
    }
}