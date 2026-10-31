package com.allperiph.ui.miuix

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
 * 给**非 Activity 容器**（悬浮窗 Service、覆盖层）用的 Compose 宿主。
 *
 * Compose 在 `View` 树上要求 `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner`
 * / `ViewTreeViewModelStoreOwner` 三者齐全，缺一个就抛
 * `IllegalStateException: ViewTreeLifecycleOwner not found`。
 * Activity 里由 `ComponentActivity` 自动提供，Service 里没有 —— 这个类就是补这三样。
 *
 * 生命周期由持有方手动推进：`add` 时 `resume()`、`remove` 时 `destroy()`，
 * 跟着覆盖层的显示/隐藏走，避免后台常驻组合树。
 */
class ApOverlayHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** 建一个挂好三件套 owner 的 ComposeView */
    fun createView(ctx: Context, content: @Composable () -> Unit): ComposeView =
        ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(this@ApOverlayHost)
            setViewTreeSavedStateRegistryOwner(this@ApOverlayHost)
            setViewTreeViewModelStoreOwner(this@ApOverlayHost)
            setContent(content)
        }

    fun resume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        viewModelStore.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
