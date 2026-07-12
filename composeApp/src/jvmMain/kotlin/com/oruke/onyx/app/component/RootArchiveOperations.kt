package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile

/** @param paneId 解压来源面板。 */
internal fun DefaultRootComponent.extractSelectedInPane(paneId: PaneId) {
    archiveActionDelegate.launchArchiveExtraction(
        selectedEntries = selectedEntriesInPane(paneId),
        currentLocation = paneState(paneId).location,
        taskTitle = I18nMessage(MessageKey.ACTION_EXTRACT_HERE),
    ) { entry, location, password, progressSink ->
        archiveService.extract(
            archivePath = entry.location,
            targetDirectory = location,
            password = password,
            progressSink = progressSink,
        )
    }
}

/** @param paneId 解压来源面板。 */
internal fun DefaultRootComponent.extractToDirectoryInPane(paneId: PaneId) {
    archiveActionDelegate.launchArchiveExtraction(
        selectedEntries = selectedEntriesInPane(paneId),
        currentLocation = paneState(paneId).location,
        taskTitle = I18nMessage(MessageKey.ACTION_EXTRACT_TO_DIRECTORY),
    ) { entry, location, password, progressSink ->
        archiveService.extractToDirectory(
            archivePath = entry.location,
            targetDirectory = location,
            password = password,
            progressSink = progressSink,
        )
    }
}

/** @param paneId 智能解压来源面板。 */
internal fun DefaultRootComponent.extractSmartInPane(paneId: PaneId) {
    archiveActionDelegate.launchArchiveExtraction(
        selectedEntries = selectedEntriesInPane(paneId),
        currentLocation = paneState(paneId).location,
        taskTitle = I18nMessage(MessageKey.ACTION_EXTRACT_SMART),
    ) { entry, location, password, progressSink ->
        archiveService.extractSmart(
            archivePath = entry.location,
            targetDirectory = location,
            password = password,
            progressSink = progressSink,
        )
    }
}

/** @param password 当前压缩包密码。 */
internal fun DefaultRootComponent.submitArchivePassword(password: String) {
    archiveActionDelegate.submitArchivePassword(password)
}

/** @param paneId 批量重命名来源面板。 */
internal fun DefaultRootComponent.batchRenameInPane(paneId: PaneId) {
    val selectedEntries = selectedEntriesInPane(paneId)
    if (selectedEntries.size < 2) return
    dialogState.value = RootDialogState.BatchRename(paneId = paneId, entries = selectedEntries)
}

/**
 * 执行批量重命名。
 *
 * @param paneId 来源面板。
 * @param renameMap 条目与目标名称映射。
 */
internal fun DefaultRootComponent.executeBatchRename(paneId: PaneId, renameMap: List<Pair<VFile, String>>) {
    fileActionDelegate.executeBatchRename(paneId, renameMap)
}

/** @param paneId 继续批量重命名的来源面板。 */
internal fun DefaultRootComponent.resetBatchRenameForContinue(paneId: PaneId) {
    fileActionDelegate.resetBatchRenameForContinue(paneId)
}
