package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings

/**
 * 将异步尺寸事件合并到最新设置，草稿窗口同步尺寸但保留尚未应用的用户编辑。
 *
 * @param intent 指定窗口及有效的逻辑尺寸。
 * @return 无返回值；窗口初始化时的无效尺寸不持久化。
 */
internal fun DefaultRootComponent.updateWindowSize(intent: RootIntent.UpdateWindowSize) {
    if (intent.width <= 0 || intent.height <= 0) return
    // AWT 尺寸事件可能晚于语言或缩放操作，不能写回 UI 捕获的旧设置快照。
    settings.value = settings.value.withWindowSize(intent)
    val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
    if (intent.windowKind == RootWindowKind.SETTINGS) {
        dialogState.value = currentDialog.copy(draft = currentDialog.draft.withWindowSize(intent))
    }
}

/**
 * 仅替换尺寸所属字段，保持语言、收藏、自定义命令和其它窗口尺寸。
 *
 * @param intent 待合并的窗口尺寸。
 * @return 合并后的不可变设置。
 */
private fun OnyxSettings.withWindowSize(intent: RootIntent.UpdateWindowSize): OnyxSettings = when (intent.windowKind) {
    RootWindowKind.MAIN -> copy(mainWindowWidth = intent.width, mainWindowHeight = intent.height)
    RootWindowKind.IMAGE_VIEWER -> copy(imageViewerWindowWidth = intent.width, imageViewerWindowHeight = intent.height)
    RootWindowKind.SETTINGS -> copy(settingsWindowWidth = intent.width, settingsWindowHeight = intent.height)
    RootWindowKind.BATCH_RENAME -> copy(batchRenameWindowWidth = intent.width, batchRenameWindowHeight = intent.height)
}
