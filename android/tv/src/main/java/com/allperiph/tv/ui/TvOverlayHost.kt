package com.allperiph.tv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 电视端浮层宿主（对应手机端 `ui/miuix/ApOverlayHost.kt`）。
 *
 * 为什么需要它：`ComposeView` 一旦被 `WindowManager.addView` 挂到应用之外的窗口，
 * 它就脱离了 Activity 的 View 树，`ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner` /
 * `ViewTreeViewModelStoreOwner` 三个 owner 全部拿不到 —— `ComposeView` 在 attach 时会
 * 直接抛 `ViewTreeLifecycleOwner not found` 崩掉。这里用一个自建 owner 把三件套补齐，
 * 浮层窗口里的 Compose 内容才能正常建立组合、跟随生命周期销毁。
 *
 * 用法：
 * ```
 * val host = TvOverlayHost(context)
 * val view = host.create { TvNotificationCard(...) }
 * windowManager.addView(view, params)
 * // 移除窗口时
 * host.destroy()
 * ```
 */
class TvOverlayHost(context: Context) {

    private val appCtx: Context = context.applicationContext
    private val owner = HostOwner()

    /** 建一个带三件套的 ComposeView，`content` 就是浮层内容 */
    fun create(content: @Composable () -> Unit): ComposeView {
        owner.start()
        return ComposeView(appCtx).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent(content)
        }
    }

    /** 移除窗口后调用：销毁组合与 ViewModel，避免泄漏 */
    fun destroy() {
        owner.stop()
    }

    private class HostOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun start() {
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
