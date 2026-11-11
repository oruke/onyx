package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.app.filesystem.isSystemVirtualLocation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.isTrashLocation
import com.oruke.onyx.shared.filesystem.toI18nMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 还原指定面板中选中的回收站条目。
 *
 * @param paneId 发起操作的面板标识。
 * @param explicitEntryIds 右键菜单显式指定的目标条目 ID；为空时使用当前面板选中集合。
 */
internal fun DefaultRootComponent.restoreSelectedTrashItems(
    paneId: PaneId,
    explicitEntryIds: Set<String>? = null,
) {
    val readyEntries = readyTrashEntriesOrNull(paneId) ?: return
    val targetIds = explicitEntryIds ?: paneState(paneId).selectedEntryIds
    if (targetIds.isNotEmpty()) {
        trashActionDelegate.restoreItems(paneId, readyEntries, targetIds)
    }
}

/**
 * 还原指定回收站面板内的全部可还原条目。
 *
 * @param paneId 发起操作的面板标识。
 */
internal fun DefaultRootComponent.restoreAllTrashItems(paneId: PaneId) {
    val readyEntries = readyTrashEntriesOrNull(paneId)?.takeIf { it.isNotEmpty() } ?: return
    val restorableIds = readyEntries
        .filter { entry -> entry.parentLocation != null }
        .mapTo(linkedSetOf()) { entry -> entry.id }
    if (restorableIds.isEmpty()) {
        showPaneOperationFailure(
            paneId,
            LocalizedException(I18nMessage(MessageKey.MSG_TRASH_ORIGINAL_PATH_UNKNOWN)),
        )
    } else {
        trashActionDelegate.restoreItems(paneId, readyEntries, restorableIds)
    }
}

/**
 * 请求永久删除回收站中的指定或选中条目。
 *
 * 永久删除不可恢复，无论设置中是否关闭普通删除确认，始终弹出确认对话框。
 *
 * @param paneId 发起操作的面板标识。
 * @param explicitEntryIds 右键菜单显式指定的目标条目 ID；为空时使用当前面板选中集合。
 */
internal fun DefaultRootComponent.requestPermanentDeleteTrashItems(
    paneId: PaneId,
    explicitEntryIds: Set<String>? = null,
) {
    val readyEntries = readyTrashEntriesOrNull(paneId) ?: return
    val validIds = readyEntries.mapTo(hashSetOf()) { entry -> entry.id }
    val targetIds = (explicitEntryIds ?: paneState(paneId).selectedEntryIds)
        .filterTo(linkedSetOf()) { id -> id in validIds }
    if (targetIds.isNotEmpty()) {
        dialogState.value = RootDialogState.TrashPermanentDeleteConfirmation(
            paneId = paneId,
            entryIds = targetIds,
            itemCount = targetIds.size,
        )
        syncImmediateRootState()
    }
}

/**
 * 请求清空回收站。
 *
 * 清空操作不可恢复，始终弹出强制二次确认对话框。
 *
 * @param paneId 发起操作的面板标识。
 */
internal fun DefaultRootComponent.requestEmptyTrash(paneId: PaneId) {
    val state = paneState(paneId)
    val readyCount = (state.entriesState as? PaneEntriesState.Ready)?.entries?.size ?: 0
    if (isTrashLocation(state.location) && readyCount == 0) return
    dialogState.value = RootDialogState.EmptyTrashConfirmation(
        paneId = paneId,
        itemCount = readyCount,
    )
    syncImmediateRootState()
}

/**
 * 确认并提交回收站条目永久删除对话框。
 *
 * @param dialog 当前确认对话框快照。
 */
internal fun DefaultRootComponent.confirmTrashPermanentDeleteDialog(
    dialog: RootDialogState.TrashPermanentDeleteConfirmation,
) {
    dialogState.value = null
    syncImmediateRootState()
    val readyEntries = (paneState(dialog.paneId).entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
    trashActionDelegate.deletePermanently(dialog.paneId, readyEntries, dialog.entryIds)
}

/**
 * 确认并提交清空回收站对话框。
 *
 * @param dialog 当前清空确认对话框快照。
 */
internal fun DefaultRootComponent.confirmEmptyTrashDialog(
    dialog: RootDialogState.EmptyTrashConfirmation,
) {
    dialogState.value = null
    syncImmediateRootState()
    val readyEntries = (paneState(dialog.paneId).entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
    trashActionDelegate.emptyTrash(dialog.paneId, readyEntries)
}

/**
 * 在指定面板中打开回收站条目的原始所在目录；原父目录已不存在时展示本地化提示。
 *
 * @param paneId 目标面板标识。
 * @param entry 回收站虚拟条目。
 */
internal fun DefaultRootComponent.openTrashItemOriginalLocation(
    paneId: PaneId,
    entry: VFile,
) {
    val parentLocation = entry.parentLocation
    if (parentLocation == null) {
        paneComponent(paneId).dispatch(
            PaneIntent.ShowOperationFeedback(
                PaneOperationFeedbackKind.OPEN_FAILED,
                I18nMessage(MessageKey.MSG_TRASH_ORIGINAL_PATH_UNKNOWN),
            ),
        )
        return
    }
    scope.launch {
        val available = withContext(Dispatchers.IO) {
            pathService.isLocationAvailable(parentLocation)
        }
        if (!available) {
            paneComponent(paneId).dispatch(
                PaneIntent.ShowOperationFeedback(
                    PaneOperationFeedbackKind.OPEN_FAILED,
                    I18nMessage(MessageKey.MSG_TRASH_ORIGINAL_LOCATION_UNAVAILABLE, parentLocation),
                ),
            )
        } else {
            activatePane(paneId)
            paneComponent(paneId).dispatch(PaneIntent.DismissOperationFeedback)
            paneComponent(paneId).openDirectory(parentLocation)
        }
    }
}

/**
 * 将回收站条目的原始完整路径复制到系统文本剪贴板。
 *
 * 直接从 [TrashService.listItems] 读取精确原始路径，避免重名后缀导致父目录拼接失真。
 *
 * @param paneId 发起操作的面板标识。
 * @param explicitEntries 右键菜单显式指定的条目列表；为空时读取面板选中集合。
 */
internal fun DefaultRootComponent.copyTrashItemOriginalPaths(
    paneId: PaneId,
    explicitEntries: List<VFile>? = null,
) {
    val targetEntries = explicitEntries ?: selectedEntriesInPane(paneId)
    if (targetEntries.isEmpty()) return
    scope.launch {
        val itemsById = withContext(Dispatchers.IO) {
            trashService.listItems().getOrNull()?.associateBy { item -> item.id }.orEmpty()
        }
        val originalPaths = targetEntries.mapNotNull { entry -> itemsById[entry.id]?.originalLocation }
        if (originalPaths.isEmpty()) {
            paneComponent(paneId).dispatch(
                PaneIntent.ShowOperationFeedback(
                    PaneOperationFeedbackKind.COPY_PATH_FAILED,
                    I18nMessage(MessageKey.MSG_TRASH_ORIGINAL_PATH_UNKNOWN),
                ),
            )
        } else {
            textClipboardService.copyText(originalPaths.joinToString(separator = "\n"))
                .onSuccess {
                    paneComponent(paneId).dispatch(PaneIntent.DismissOperationFeedback)
                }
                .onFailure { failure ->
                    paneComponent(paneId).dispatch(
                        PaneIntent.ShowOperationFeedback(
                            PaneOperationFeedbackKind.COPY_PATH_FAILED,
                            failure.toI18nMessage(),
                        ),
                    )
                }
        }
    }
}

/**
 * 将来源面板中的选中本地文件拖入回收站（由侧栏回收站或打开的回收站面板触发）。
 *
 * @param sourcePaneId 拖放来源面板标识。
 */
internal fun DefaultRootComponent.requestDropEntriesToTrash(sourcePaneId: PaneId) {
    if (isSystemVirtualLocation(paneState(sourcePaneId).location)) return
    val selectedEntries = selectedEntriesInPane(sourcePaneId).takeIf { it.isNotEmpty() } ?: return
    if (!trashService.isSupported || selectedEntries.any { entry -> entry.location.contains("://") }) {
        showPaneOperationFailure(
            sourcePaneId,
            LocalizedException(I18nMessage(MessageKey.MSG_TRASH_UNSUPPORTED)),
        )
    } else {
        val request = FileActionDelegate.PendingDeleteRequest(
            paneId = sourcePaneId,
            entries = selectedEntries,
            moveToTrash = true,
        )
        if (!settings.value.confirmBeforeDelete) {
            fileActionDelegate.executeDeleteRequest(request)
        } else {
            fileActionDelegate.setPendingDeleteRequest(request)
            dialogState.value = RootDialogState.DeleteSelectionConfirmation(
                moveToTrash = true,
                itemCount = selectedEntries.size,
                trashUnavailable = false,
            )
            syncImmediateRootState()
        }
    }
}

/**
 * 读取指定回收站面板内已就绪的条目列表；面板不在回收站或尚未就绪时返回 `null`。
 */
private fun DefaultRootComponent.readyTrashEntriesOrNull(paneId: PaneId): List<VFile>? {
    val state = paneState(paneId)
    return if (isTrashLocation(state.location)) {
        (state.entriesState as? PaneEntriesState.Ready)?.entries
    } else {
        null
    }
}

/**
 * 将文件操作异常展示到指定面板的操作反馈栏。
 *
 * @param paneId 目标面板标识。
 * @param failure 操作失败异常。
 */
internal fun DefaultRootComponent.showPaneOperationFailure(
    paneId: PaneId,
    failure: Throwable,
) {
    paneComponent(paneId).dispatch(
        PaneIntent.ShowOperationFeedback(
            PaneOperationFeedbackKind.FILE_OPERATION_FAILED,
            failure.toI18nMessage(),
        ),
    )
}
