package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.shared.filesystem.toI18nMessage
import kotlinx.coroutines.launch

/**
 * 复制指定面板的当前选择，经共享剪贴板服务发布系统载荷。
 * @param paneId 本次用户明确操作的来源面板。
 * @return 无返回值，系统写入失败显示在原操作面板。
 */
internal fun DefaultRootComponent.stageCopySelectedInPane(paneId: PaneId) {
    val entries = selectedEntriesInPane(paneId)
    scope.launch { clipboardManager.stageCopy(entries).onFailure { showClipboardFailure(paneId, it) } }
}

/**
 * 剪切指定面板的当前选择，载荷使用唯一标识避免与随后发生的外部复制混淆。
 * @param paneId 本次用户明确操作的来源面板。
 * @return 无返回值，系统写入失败不发布可误执行的内部载荷。
 */
internal fun DefaultRootComponent.stageCutSelectedInPane(paneId: PaneId) {
    val entries = selectedEntriesInPane(paneId)
    scope.launch { clipboardManager.stageCut(entries).onFailure { showClipboardFailure(paneId, it) } }
}

/**
 * 从当前系统剪贴板读取真实文件，按其复制或剪切语义进入现有传输命令链。
 * @param paneId 本次粘贴的目标面板，异步读取期间仍使用用户发起动作时的位置。
 * @return 无返回值，读取或文件解析失败明确反馈，禁止继续使用旧内部载荷。
 */
internal fun DefaultRootComponent.requestPasteIntoPane(paneId: PaneId) {
    val targetLocation = paneState(paneId).location
    scope.launch {
        clipboardManager.consume().onSuccess { payload ->
            if (payload != null) {
                fileTransferDelegate.requestTransferEntriesToDirectory(
                    entries = payload.entries,
                    targetDirectoryLocation = targetLocation,
                    operation = if (payload.operation == ClipboardManager.ClipboardOperation.CUT) {
                        FileTransferOperation.MOVE
                    } else FileTransferOperation.COPY,
                    clearClipboardOnSuccess = payload.operation == ClipboardManager.ClipboardOperation.CUT,
                )
            }
        }.onFailure { showClipboardFailure(paneId, it) }
    }
}

/**
 * 将剪贴板和文件解析错误显示在实际发起操作的面板，避免异步期间切换焦点后提示错位。
 * @param paneId 原始操作面板。
 * @param failure 系统访问或统一文件服务返回的具体失败。
 * @return 无返回值。
 */
private fun DefaultRootComponent.showClipboardFailure(paneId: PaneId, failure: Throwable) {
    paneComponent(paneId).dispatch(
        PaneIntent.ShowOperationFeedback(PaneOperationFeedbackKind.FILE_OPERATION_FAILED, failure.toI18nMessage()),
    )
}
