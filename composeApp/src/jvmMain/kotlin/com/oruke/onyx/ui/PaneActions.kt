package com.oruke.onyx.ui

import com.oruke.onyx.app.filesystem.OpenWithApp
import com.oruke.onyx.core.model.VFile

/**
 * 面板级的 RootComponent 操作打包。
 *
 * PaneSurface 需要的回调分两类：
 * 1. PaneComponent 自身方法 — 已通过 `component` 参数直接调用
 * 2. 需要 RootComponent 参与的跨面板操作 — 由此类打包
 *
 * 此类消除了 PaneSurface 参数列表中 20+ 个独立回调的散列传参。
 */
data class PaneActions(
    val onDeleteSelection: () -> Unit,
    val onExtractSelection: () -> Unit,
    val onExtractToDirectory: () -> Unit,
    val onExtractSmart: () -> Unit,
    val onBatchRename: () -> Unit,
    val onCopySelection: () -> Unit,
    val onCutSelection: () -> Unit,
    val onPaste: () -> Unit,
    val onBeginCreateDirectory: () -> Unit,
    val onToggleFavoriteLocation: (String) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenWith: (VFile, OpenWithApp) -> Unit,
    val onOpenWithChooser: (VFile) -> Unit,
    val onQueryOpenWithApps: (suspend (VFile) -> List<OpenWithApp>)? = null,
)
