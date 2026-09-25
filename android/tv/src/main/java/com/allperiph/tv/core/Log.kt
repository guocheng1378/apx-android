package com.allperiph.tv.core

/** 极简日志封装：TV 模块自包含，不依赖手机端 core。 */
object Log {
    private const val TAG = "ApxTv"

    fun i(msg: String) = android.util.Log.i(TAG, msg)
    fun w(msg: String) = android.util.Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) =
        if (t != null) android.util.Log.e(TAG, msg, t) else android.util.Log.e(TAG, msg)
}
