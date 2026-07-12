package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.FileContextMenuCommand
import com.oruke.onyx.vfs.api.OpenWithApp
import com.oruke.onyx.vfs.api.SystemMenuAction
import kotlinx.coroutines.launch

/**
 * 打开图片查看器。
 *
 * @param file 当前图片。
 * @param allImages 同目录图片序列。
 */
internal fun DefaultRootComponent.openImageViewer(file: VFile, allImages: List<VFile>) {
    imageViewerController.open(file, allImages)
}

/**
 * 使用指定系统应用打开条目。
 *
 * @param entry 文件条目。
 * @param app 目标应用。
 */
internal fun DefaultRootComponent.openWithApp(entry: VFile, app: OpenWithApp) {
    scope.launch {
        openWithService.openWith(entry, app)
            .onSuccess { dismissActivePaneFeedback() }
            .onFailure(::showActivePaneOpenFailure)
    }
}

/** @param entry 待交给系统应用选择器的条目。 */
internal fun DefaultRootComponent.openWithChooser(entry: VFile) {
    scope.launch {
        openWithService.openWithChooser(entry)
            .onSuccess { dismissActivePaneFeedback() }
            .onFailure(::showActivePaneOpenFailure)
    }
}

/**
 * 执行系统菜单动作。
 *
 * @param action 系统菜单动作。
 * @param entries 动作目标。
 */
internal fun DefaultRootComponent.executeSystemMenuAction(action: SystemMenuAction, entries: List<VFile>) {
    scope.launch {
        systemMenuService.execute(action, entries)
            .onSuccess { dismissActivePaneFeedback() }
            .onFailure(::showActivePaneOperationFailure)
    }
}

/**
 * 执行统一文件上下文菜单命令。
 *
 * @param command 文件上下文菜单命令。
 * @param entries 命令目标。
 */
internal fun DefaultRootComponent.executeFileContextMenuCommand(
    command: FileContextMenuCommand,
    entries: List<VFile>,
) {
    scope.launch {
        fileContextMenuService.execute(command, entries)
            .onSuccess { dismissActivePaneFeedback() }
            .onFailure(::showActivePaneOperationFailure)
    }
}

/** @param location 待打开终端的 VFS 位置。 */
internal fun DefaultRootComponent.openTerminalAt(location: String) {
    scope.launch { terminalLauncherService.openTerminal(location) }
}

/** 清除活动面板操作反馈。 */
private fun DefaultRootComponent.dismissActivePaneFeedback() {
    paneComponent(activePane.value).dispatch(PaneIntent.DismissOperationFeedback)
}

/** @param failure 打开文件失败原因。 */
private fun DefaultRootComponent.showActivePaneOpenFailure(failure: Throwable) {
    showActivePaneFailure(PaneOperationFeedbackKind.OPEN_FAILED, failure)
}

/** @param failure 文件操作失败原因。 */
internal fun DefaultRootComponent.showActivePaneOperationFailure(failure: Throwable) {
    showActivePaneFailure(PaneOperationFeedbackKind.FILE_OPERATION_FAILED, failure)
}

/**
 * 将失败映射到活动面板操作反馈。
 *
 * @param kind 反馈类型。
 * @param failure 失败原因。
 */
private fun DefaultRootComponent.showActivePaneFailure(
    kind: PaneOperationFeedbackKind,
    failure: Throwable,
) {
    paneComponent(activePane.value).dispatch(
        PaneIntent.ShowOperationFeedback(kind, failure.toI18nMessage())
    )
}
