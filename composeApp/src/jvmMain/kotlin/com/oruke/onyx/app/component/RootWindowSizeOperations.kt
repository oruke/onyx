package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings

/** 允许持久化的浮动窗口最小逻辑宽度，过滤 Windows 最小化瞬态尺寸（如 160x28）。 */
internal const val MIN_PERSISTED_WINDOW_WIDTH = 320

/** 允许持久化的浮动窗口最小逻辑高度，过滤 Windows 最小化瞬态尺寸（如 160x28）。 */
internal const val MIN_PERSISTED_WINDOW_HEIGHT = 240

/**
 * 将异步尺寸与最大化状态事件合并到最新设置，草稿窗口同步尺寸但保留尚未应用的用户编辑。
 *
 * @param intent 指定窗口及有效的逻辑尺寸与最大化状态。
 * @return 无返回值；窗口初始化或最小化时的无效尺寸不持久化。
 */
internal fun DefaultRootComponent.updateWindowSize(intent: RootIntent.UpdateWindowSize) {
    val nextSettings = settings.value.withWindowSize(intent)
    if (nextSettings == settings.value) return
    // AWT 尺寸事件可能晚于语言或缩放操作，不能写回 UI 捕获的旧设置快照。
    settings.value = nextSettings
    val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
    dialogState.value = currentDialog.copy(draft = currentDialog.draft.withWindowSize(intent))
}

/**
 * 仅替换尺寸与最大化所属字段，保持语言、收藏、自定义命令和其它窗口尺寸。
 *
 * 最大化状态下仅记录最大化标记，保留浮动窗口宽高，以便还原窗口时恢复原尺寸。
 *
 * @param intent 待合并的窗口尺寸与最大化状态。
 * @return 合并后的不可变设置；尺寸无效时保持原值。
 */
internal fun OnyxSettings.withWindowSize(intent: RootIntent.UpdateWindowSize): OnyxSettings {
    if (intent.maximized == true) {
        return withWindowMaximized(intent.windowKind)
    }
    if (intent.width < MIN_PERSISTED_WINDOW_WIDTH || intent.height < MIN_PERSISTED_WINDOW_HEIGHT) {
        return this
    }
    return withFloatingWindowSize(intent)
}

/**
 * 记录窗口进入最大化状态，保持已有浮动宽高不变。
 *
 * @param windowKind 目标窗口类型。
 * @return 标记最大化后的不可变设置。
 */
private fun OnyxSettings.withWindowMaximized(windowKind: RootWindowKind): OnyxSettings = when (windowKind) {
    RootWindowKind.MAIN -> copy(mainWindowMaximized = true)
    RootWindowKind.IMAGE_VIEWER -> copy(imageViewerWindowMaximized = true)
    RootWindowKind.SEARCH -> copy(searchWindowMaximized = true)
    RootWindowKind.TASK_CENTER -> copy(taskCenterWindowMaximized = true)
    RootWindowKind.SETTINGS, RootWindowKind.BATCH_RENAME -> this
}

/**
 * 更新浮动窗口的逻辑宽高与还原后的最大化状态。
 *
 * @param intent 已通过最小阈值校验的窗口尺寸意图。
 * @return 更新宽高后的不可变设置。
 */
private fun OnyxSettings.withFloatingWindowSize(intent: RootIntent.UpdateWindowSize): OnyxSettings =
    when (intent.windowKind) {
        RootWindowKind.MAIN -> copy(
            mainWindowWidth = intent.width,
            mainWindowHeight = intent.height,
            mainWindowMaximized = intent.maximized ?: mainWindowMaximized,
        )
        RootWindowKind.IMAGE_VIEWER -> copy(
            imageViewerWindowWidth = intent.width,
            imageViewerWindowHeight = intent.height,
            imageViewerWindowMaximized = intent.maximized ?: imageViewerWindowMaximized,
        )
        RootWindowKind.SETTINGS -> copy(
            settingsWindowWidth = intent.width,
            settingsWindowHeight = intent.height,
        )
        RootWindowKind.BATCH_RENAME -> copy(
            batchRenameWindowWidth = intent.width,
            batchRenameWindowHeight = intent.height,
        )
        RootWindowKind.SEARCH -> copy(
            searchWindowWidth = intent.width,
            searchWindowHeight = intent.height,
            searchWindowMaximized = intent.maximized ?: searchWindowMaximized,
        )
        RootWindowKind.TASK_CENTER -> copy(
            taskCenterWindowWidth = intent.width,
            taskCenterWindowHeight = intent.height,
            taskCenterWindowMaximized = intent.maximized ?: taskCenterWindowMaximized,
        )
    }
