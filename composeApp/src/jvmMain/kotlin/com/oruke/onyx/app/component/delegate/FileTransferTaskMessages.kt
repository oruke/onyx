package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderRegistry

/**
 * 将传输操作映射为任务中心分类。
 *
 * @param operation 文件操作。
 * @return 对应任务类型。
 */
internal fun taskKindFor(operation: FileTransferOperation): BackgroundTaskKind {
    return when (operation) {
        FileTransferOperation.COPY -> BackgroundTaskKind.COPY
        FileTransferOperation.MOVE -> BackgroundTaskKind.MOVE
        FileTransferOperation.EXTRACT -> BackgroundTaskKind.EXTRACT
    }
}

/**
 * 构造可随语言变化解析的传输任务标题。
 *
 * @param operation 文件操作。
 * @param entryCount 顶层请求条目数量。
 * @return 本地化任务标题。
 */
internal fun taskTitleFor(
    operation: FileTransferOperation,
    entryCount: Int,
): I18nMessage {
    return when (operation) {
        FileTransferOperation.COPY -> I18nMessage(MessageKey.MSG_COPY_ITEMS, entryCount)
        FileTransferOperation.MOVE -> I18nMessage(MessageKey.MSG_MOVE_ITEMS, entryCount)
        FileTransferOperation.EXTRACT -> I18nMessage(MessageKey.MSG_EXTRACT_ITEMS, entryCount)
    }
}

/**
 * 构造全部成功（含冲突跳过）时的任务完成摘要。
 */
internal fun completedTransferMessageFor(
    operation: FileTransferOperation,
    totalCount: Int,
    skippedCount: Int,
    targetDirectoryLocation: String,
): I18nMessage {
    if (skippedCount > 0) {
        return I18nMessage(
            MessageKey.MSG_TRANSFER_SKIPPED_ITEMS,
            totalCount - skippedCount,
            skippedCount,
        )
    }
    return when (operation) {
        FileTransferOperation.COPY ->
            I18nMessage(MessageKey.MSG_COPIED_ITEMS, totalCount, targetDirectoryLocation)
        FileTransferOperation.MOVE ->
            I18nMessage(MessageKey.MSG_MOVED_ITEMS, totalCount, targetDirectoryLocation)
        FileTransferOperation.EXTRACT ->
            I18nMessage(MessageKey.MSG_EXTRACTED_ITEMS, totalCount, targetDirectoryLocation)
    }
}

/**
 * 构造存在失败条目时的任务结果摘要：有实际提交成功项时明确展示已完成数与失败数，全部失败时展示首个具体失败原因。
 */
internal fun partialTransferFailureMessageFor(
    operation: FileTransferOperation,
    committedCount: Int,
    failedCount: Int,
    targetDirectoryLocation: String,
    firstFailure: Throwable?,
): I18nMessage {
    if (committedCount <= 0) {
        return firstFailure?.toTransferFailureMessage(operation) ?: failureMessageFor(operation)
    }
    return when (operation) {
        FileTransferOperation.COPY ->
            I18nMessage(MessageKey.MSG_COPY_PARTIAL_FAILED, committedCount, targetDirectoryLocation, failedCount)
        FileTransferOperation.MOVE ->
            I18nMessage(MessageKey.MSG_MOVE_PARTIAL_FAILED, committedCount, targetDirectoryLocation, failedCount)
        FileTransferOperation.EXTRACT ->
            I18nMessage(MessageKey.MSG_EXTRACT_PARTIAL_FAILED, committedCount, targetDirectoryLocation, failedCount)
    }
}

/**
 * 提供传输错误映射使用的操作级兜底消息。
 */
internal fun failureMessageFor(operation: FileTransferOperation): I18nMessage {
    return when (operation) {
        FileTransferOperation.COPY -> I18nMessage(MessageKey.MSG_COPY_FAILED)
        FileTransferOperation.MOVE -> I18nMessage(MessageKey.MSG_MOVE_FAILED)
        FileTransferOperation.EXTRACT -> I18nMessage(MessageKey.MSG_EXTRACT_FAILED)
    }
}

/**
 * 通过统一错误映射保留传输失败原因及本地化参数，并剥离内部暂存路径前缀。
 */
internal fun Throwable.toTransferFailureMessage(operation: FileTransferOperation): I18nMessage {
    val mapped = toI18nMessage(failureMessageFor(operation).key)
    if (mapped.key == MessageKey.MSG_STRING_LITERAL && mapped.args.size == 1) {
        val raw = mapped.args.first() as? String
        if (raw != null) {
            return I18nMessage(MessageKey.MSG_STRING_LITERAL, sanitizeDiagnosticMessage(raw))
        }
    }
    return mapped
}

/**
 * 判断当前传输是否能在内容块或顶层条目边界可靠暂停。
 */
internal fun canPauseTransfer(
    entries: List<VFile>,
    targetDirectoryLocation: String,
    providerRegistry: VfsProviderRegistry,
): Boolean {
    if (entries.size > 1) return true
    val sourceProtocol = entries.firstOrNull()
        ?.let { entry -> providerRegistry.providerFor(entry.location).getOrNull()?.protocol }
        ?: return false
    val targetProtocol = providerRegistry.providerFor(targetDirectoryLocation).getOrNull()?.protocol
        ?: return false
    return sourceProtocol == VfsProtocol.LOCAL || sourceProtocol != targetProtocol
}

/**
 * 汇总部分失败或取消后需要刷新校验的全部关联目录位置。
 */
internal fun collectTouchedTransferLocations(
    structure: PlannedTransferStructure,
    createdDirectories: List<VFile>,
    invalidated: MutableSet<String>,
) {
    invalidated += structure.entries.mapNotNull { it.parentLocation }
    invalidated += structure.targetDirectoryLocation
    invalidated += structure.entryTargetDirectories.values
    invalidated += createdDirectories.mapNotNull { it.parentLocation }
}
