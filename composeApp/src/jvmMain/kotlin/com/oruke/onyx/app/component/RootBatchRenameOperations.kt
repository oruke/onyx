package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.shared.usecase.BatchRenameDraftPlanner
import com.oruke.onyx.shared.usecase.toEditableDraft
import java.util.UUID

/**
 * 更新组件草稿并规划预览，输入无效时保留草稿及原名称。
 * @param draft 最新表单输入。
 */
internal fun DefaultRootComponent.updateBatchRenameDraft(draft: BatchRenameDraft) {
    val current = dialogState.value as? RootDialogState.BatchRename ?: return
    if (current.executing) return
    dialogState.value = current.copy(draft = draft).withBatchRenamePreview()
}

/**
 * 计算当前草稿的名称预览并执行碰撞/非法名检测，UI 不再计算业务名称。
 * @return 包含完整预览、冲突条目集合及本地化校验错误的对话框状态。
 */
internal fun RootDialogState.BatchRename.withBatchRenamePreview(): RootDialogState.BatchRename {
    val preview = BatchRenameDraftPlanner.preview(entries, draft)
    val results = preview.getOrElse { entries.map { it to it.name } }
    val patternError = preview.exceptionOrNull()?.toI18nMessage()
    if (patternError != null) {
        return copy(
            renameResults = results,
            conflictEntryIds = emptySet(),
            validationMessage = patternError,
        )
    }
    val caseInsensitive = entries.firstOrNull()?.parentLocation?.let { parent ->
        isCaseInsensitiveVfsLocation(parent)
    } ?: isWindowsHostPlatform()
    val conflicts = BatchRenameDraftPlanner.findConflicts(
        renameResults = results,
        siblingNamesByParent = siblingNamesByParent,
        caseInsensitive = caseInsensitive,
    )
    return copy(
        renameResults = results,
        conflictEntryIds = conflicts.keys,
        validationMessage = if (conflicts.isNotEmpty()) {
            I18nMessage(MessageKey.MSG_BATCH_RENAME_CONFLICT, conflicts.size)
        } else {
            null
        },
    )
}

/** 判断位置所在文件系统是否按大小写不敏感规则判重（Windows 本地与 SMB 为不敏感，S3/WebDAV 为敏感）。 */
internal fun isCaseInsensitiveVfsLocation(location: String): Boolean {
    val lower = location.lowercase()
    return when {
        lower.startsWith("smb://") -> true
        lower.startsWith("s3://") || lower.startsWith("webdav://") || lower.startsWith("webdavs://") -> false
        lower.startsWith("archive://") -> false
        else -> isWindowsHostPlatform()
    }
}

/** 判断当前宿主操作系统是否为 Windows。 */
internal fun isWindowsHostPlatform(): Boolean {
    return System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
}

/**
 * 从保存的预设恢复草稿，模板式预设通过已有规划器恢复预览。
 * @param id 预设唯一标识。
 */
internal fun DefaultRootComponent.loadBatchRenamePreset(id: String) {
    val current = dialogState.value as? RootDialogState.BatchRename ?: return
    if (current.executing) return
    val preset = settings.value.batchRenamePresets.firstOrNull { it.id == id } ?: return
    val draft = preset.toEditableDraft()
    dialogState.value = current.copy(
        draft = draft, selectedPresetId = id, presetName = preset.name,
    ).withBatchRenamePreview()
}

/**
 * 修改将保存的预设名称，保留其它表单输入。
 * @param name 用户输入的预设名称。
 */
internal fun DefaultRootComponent.updateBatchRenamePresetName(name: String) {
    val current = dialogState.value as? RootDialogState.BatchRename ?: return
    if (current.executing) return
    dialogState.value = current.copy(presetName = name).withBatchRenamePreview()
}

/** 将当前有效表单保存为独立预设，已选中预设则覆盖该记录。 */
internal fun DefaultRootComponent.saveBatchRenamePreset() {
    val current = dialogState.value as? RootDialogState.BatchRename ?: return
    if (current.executing || current.validationMessage != null) return
    if (current.presetName.isBlank()) {
        dialogState.value = current.copy(validationMessage = I18nMessage(MessageKey.MSG_RENAME_PRESET_NAME_REQUIRED))
        return
    }
    val id = current.selectedPresetId ?: UUID.randomUUID().toString()
    val preset = BatchRenamePreset(
        id = id,
        name = current.presetName.trim(),
        replacement = current.draft.replaceText,
        draft = current.draft
    )
    val previous = settings.value.batchRenamePresets
    updateSettings(
        settings.value.copy(
            batchRenamePresets = if (previous.any { it.id == id }) {
                previous.map { if (it.id == id) preset else it }
            } else previous + preset))
    dialogState.value = current.copy(selectedPresetId = id, presetName = preset.name)
}

/** 删除已选中的保存预设，当前规则输入仍可继续用于预览和执行。 */
internal fun DefaultRootComponent.deleteBatchRenamePreset() {
    val current = dialogState.value as? RootDialogState.BatchRename ?: return
    if (current.executing) return
    val id = current.selectedPresetId ?: return
    val retained = settings.value.batchRenamePresets.filterNot { it.id == id }
    updateSettings(settings.value.copy(batchRenamePresets = retained))
    dialogState.value = current.copy(selectedPresetId = null, presetName = "").withBatchRenamePreview()
}

/**
 * 集中分发预设与草稿事件。
 * @param intent 根组件收到的事件。
 * @return 属于批量规则事件并已处理时为 true。
 */
internal fun DefaultRootComponent.dispatchBatchRenameDraftIntent(intent: RootIntent): Boolean = when (intent) {
    is RootIntent.UpdateBatchRenameDraft -> {
        updateBatchRenameDraft(intent.draft); true
    }

    is RootIntent.LoadBatchRenamePreset -> {
        loadBatchRenamePreset(intent.id); true
    }

    is RootIntent.UpdateBatchRenamePresetName -> {
        updateBatchRenamePresetName(intent.name); true
    }

    RootIntent.SaveBatchRenamePreset -> {
        saveBatchRenamePreset(); true
    }

    RootIntent.NewBatchRenamePreset -> {
        val current = dialogState.value as? RootDialogState.BatchRename
        if (current != null && !current.executing) {
            dialogState.value = current.copy(selectedPresetId = null, presetName = "").withBatchRenamePreview()
        }
        true
    }

    RootIntent.DeleteBatchRenamePreset -> {
        deleteBatchRenamePreset(); true
    }

    else -> false
}
