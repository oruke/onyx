package com.oruke.onyx.app.component

import com.oruke.onyx.shared.filesystem.DirectoryContentState
import com.oruke.onyx.shared.filesystem.combinedIdentityChanges
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile

/** 将共享目录数据投影到独立标签，刷新状态与浏览状态分开校正。 */
internal object PaneDirectoryReducer {
    /**
     * 应用完整快照或已确认变化，保留仍有效的选择与访问身份。
     * @param tab 当前标签。
     * @param content 共享目录内容。
     * @param focusName 显式导航要求定位的名称。
     * @return 供现有派生状态流程处理的标签。
     */
    fun applyContent(tab: PaneTabState, content: DirectoryContentState, focusName: String?): PaneTabState {
        val entries = content.entries
        val failure = content.failure
        val changed = content.revision != tab.tabState.browserState.contentRevision
        val identities = if (changed) {
            content.changeLog.filter { it.revision > tab.tabState.browserState.contentRevision }
                .combinedIdentityChanges()
        } else emptyMap()
        val removedIds = content.change?.removed?.mapTo(hashSetOf()) { it.id }.orEmpty()
        val editTarget = tab.inlineEditState?.targetEntryId
        val mappedEditTarget = identities[editTarget] ?: editTarget
        // F5 和监听降级校验只有完整快照，也必须结束已消失条目的编辑，避免草稿阻塞后续新建。
        val editTargetRemoved = tab.hasRemovedEditTarget(entries, mappedEditTarget)
        val mapped = tab.withTabState { current ->
            current.copy(
                selectedEntryIds = current.selectedEntryIds.mapTo(linkedSetOf()) { identities[it] ?: it },
                selectionAnchorId = identities[current.selectionAnchorId] ?: current.selectionAnchorId,
                selectionFocusId = identities[current.selectionFocusId] ?: current.selectionFocusId,
                refreshing = content.refreshing,
                directoryError = if (entries != null) content.failure?.toI18nMessage() else null,
                directoryObservationError = content.observationFailure?.toI18nMessage(),
                inlineEditState = current.inlineEditState?.takeUnless { edit ->
                    editTargetRemoved || (edit.targetEntryId in removedIds && edit.targetEntryId !in identities)
                }?.let { edit -> edit.copy(targetEntryId = identities[edit.targetEntryId] ?: edit.targetEntryId) },
                operationFeedback = if (editTargetRemoved) PaneOperationFeedback(
                    PaneOperationFeedbackKind.FILE_OPERATION_FAILED,
                    I18nMessage(MessageKey.MSG_INLINE_EDIT_TARGET_UNAVAILABLE),
                ) else current.operationFeedback,
                browserState = current.browserState.copy(contentRevision = content.revision,
                    identityChanges = content.changeLog),
            )
        }
        return if (entries != null) {
            val focus = focusName?.let { name -> entries.firstOrNull { it.name == name } }
            mapped.withDirectoryEntries(entries, focus)
        } else if (failure != null) {
            mapped.withLoadFailure(failure.toI18nMessage())
        } else {
            mapped.withTabState { it.copy(entriesState = PaneEntriesState.Loading) }
        }
    }
}

/**
 * 判断当前编辑对象是否从真实顶层快照中消失，身份映射已处理的改名不算删除。
 * @param entries 最新目录内容，未读取成功时为空。
 * @param targetId 已应用身份变化的编辑目标。
 * @return 原顶层编辑对象确实不存在时返回 true。
 */
internal fun PaneTabState.hasRemovedEditTarget(entries: List<VFile>?, targetId: String?): Boolean =
    entries != null && allEntries.any { it.id == inlineEditState?.targetEntryId } && entries.none { it.id == targetId }
