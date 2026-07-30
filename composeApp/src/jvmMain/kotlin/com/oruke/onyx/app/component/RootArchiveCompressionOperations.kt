package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.vfs.archive.ZipArchiveCreationService
import com.oruke.onyx.vfs.archive.ZipArchiveNameValidation

/**
 * 打开 ZIP 压缩包名称输入对话框。
 *
 * @param paneId 发起压缩操作的面板标识。
 * @return 无返回值。
 */
internal fun DefaultRootComponent.beginCreateZipArchiveInPane(paneId: PaneId) {
    val selectedEntries = selectedEntriesInPane(paneId)
    if (selectedEntries.isEmpty()) return
    dialogState.value = RootDialogState.CreateZipArchive(
        paneId = paneId,
        location = paneState(paneId).location,
        entries = selectedEntries,
        draft = "",
    )
}

/**
 * 更新 ZIP 压缩包名称草稿并清除已展示的校验错误。
 *
 * @param draft 用户输入的归档名称草稿。
 * @return 无返回值。
 */
internal fun DefaultRootComponent.updateZipArchiveNameDraft(draft: String) {
    val currentDialog = dialogState.value as? RootDialogState.CreateZipArchive ?: return
    dialogState.value = currentDialog.copy(draft = draft, error = null)
}

/**
 * 校验 ZIP 名称并将创建任务交给后台压缩委托。
 *
 * @param dialog 当前 ZIP 压缩包创建对话框状态。
 * @return 无返回值。
 */
internal fun DefaultRootComponent.confirmCreateZipArchive(dialog: RootDialogState.CreateZipArchive) {
    when (val validation = ZipArchiveCreationService.validateArchiveName(dialog.draft)) {
        ZipArchiveNameValidation.Empty -> {
            dialogState.value = dialog.copy(error = CreateZipArchiveDialogError.EMPTY_INPUT)
        }

        ZipArchiveNameValidation.Invalid -> {
            dialogState.value = dialog.copy(error = CreateZipArchiveDialogError.INVALID_NAME)
        }

        is ZipArchiveNameValidation.Valid -> {
            dialogState.value = null
            archiveCompressionDelegate.launchZipArchiveCreation(
                selectedEntries = dialog.entries,
                targetDirectoryLocation = dialog.location,
                archiveName = validation.fileName,
            )
        }
    }
}
