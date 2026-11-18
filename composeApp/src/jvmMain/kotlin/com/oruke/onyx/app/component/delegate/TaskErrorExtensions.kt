package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage

/** 内部暂存路径前缀正则，用于剥离遗留或异常透出的 `.onyx-transfer-<id>/content -> ` 片段。 */
private val STAGING_ARROW_PREFIX_REGEX = Regex("""^.*?[/\\]\.onyx-transfer-[^/\\]+[/\\][^/\\]+\s*->\s*""")

/**
 * 将单个条目的传输异常映射为可重新本地化的文件错误，剥离重复的目标长路径前缀以便在任务列表直接展示核心原因。
 *
 * @param entry 失败的源条目。
 * @param targetDirectoryLocation 该条目实际落盘目标父目录。
 * @return 精确关联到该条目的错误记录。
 */
internal fun Throwable.toTaskError(
    entry: VFile,
    targetDirectoryLocation: String? = null,
): TaskError {
    val diagnostic = sanitizeDiagnosticMessage(message)
    val detail = toI18nMessage().stripRedundantPathPrefix(entry.name, targetDirectoryLocation)
    return TaskError(
        fileName = entry.name,
        message = diagnostic,
        detail = detail,
    )
}

/**
 * 将预检或目录级传输异常映射为可重新本地化的文件错误，保留原始诊断供历史记录使用。
 *
 * @param entries 本次操作的文件；为空时将错误关联到目标目录。
 * @param targetDirectoryLocation 当前传输目标位置。
 * @return 携带统一消息键和原始详情的错误列表。
 */
internal fun Throwable.toTaskErrors(
    entries: List<VFile>,
    targetDirectoryLocation: String,
): List<TaskError> {
    val rawDetail = toI18nMessage()
    val diagnostic = sanitizeDiagnosticMessage(message)
    val fileNames = entries.map(VFile::name).ifEmpty { listOf(targetDirectoryLocation) }
    return fileNames.map { fileName ->
        TaskError(
            fileName = fileName,
            message = diagnostic,
            detail = rawDetail.stripRedundantPathPrefix(fileName, targetDirectoryLocation),
        )
    }
}

/**
 * 清理诊断文本中可能残存的内部 `.onyx-transfer-*` 暂存源路径前缀。
 */
internal fun sanitizeDiagnosticMessage(rawMessage: String?): String {
    val nonBlank = rawMessage?.takeIf { it.isNotBlank() } ?: return ""
    return nonBlank.replace(STAGING_ARROW_PREFIX_REGEX, "")
}

/**
 * 当错误详情为纯字符串字面量（如 Java NIO `FileSystemException` 的 `<targetPath>: <reason>`）时，
 * 剥离与当前文件名或目标目录重复的前缀路径，避免超长文件名把真正的系统错误原因挤出可视区域。
 */
internal fun I18nMessage.stripRedundantPathPrefix(
    fileName: String,
    targetDirectoryLocation: String? = null,
): I18nMessage {
    val raw = takeIf { key == MessageKey.MSG_STRING_LITERAL && args.size == 1 }
        ?.let { args.first() as? String }
        ?.let(::sanitizeDiagnosticMessage)
        ?.trim()
        ?: return this

    val separatorIndex = raw.lastIndexOf(": ")
    val hasReasonSuffix = separatorIndex > 0 && separatorIndex + 2 < raw.length
    val extractedReason = if (hasReasonSuffix) {
        val pathPart = raw.substring(0, separatorIndex).trim()
        val reasonPart = raw.substring(separatorIndex + 2).trim()
        reasonPart.takeIf { reason ->
            reason.isNotEmpty() && isRedundantTargetPathPrefix(pathPart, fileName, targetDirectoryLocation)
        }
    } else {
        null
    }
    return I18nMessage(MessageKey.MSG_STRING_LITERAL, extractedReason ?: raw)
}

private fun isRedundantTargetPathPrefix(
    pathPart: String,
    fileName: String,
    targetDirectoryLocation: String?,
): Boolean {
    val normalizedPathPart = pathPart.replace('\\', '/')
    val normalizedTargetDir = targetDirectoryLocation?.replace('\\', '/')?.trimEnd('/')
    val matchesFileName = fileName.isNotBlank() && (
        normalizedPathPart == fileName || normalizedPathPart.endsWith("/$fileName")
        )
    val matchesTargetDir = !normalizedTargetDir.isNullOrBlank() &&
        normalizedPathPart.startsWith("$normalizedTargetDir/")
    return matchesFileName || matchesTargetDir
}
