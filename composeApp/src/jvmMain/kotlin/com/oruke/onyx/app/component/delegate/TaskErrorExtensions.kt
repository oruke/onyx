package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage

/**
 * 将传输异常映射为可重新本地化的文件错误，保留原始诊断供历史记录使用。
 *
 * @param entries 本次操作的文件；为空时将错误关联到目标目录。
 * @param targetDirectoryLocation 当前传输目标位置。
 * @return 携带统一消息键和原始详情的错误列表。
 */
internal fun Throwable.toTaskErrors(
    entries: List<VFile>,
    targetDirectoryLocation: String,
): List<TaskError> {
    val detail = toI18nMessage()
    val diagnostic = message?.takeIf { it.isNotBlank() }.orEmpty()
    val fileNames = entries.map(VFile::name).ifEmpty { listOf(targetDirectoryLocation) }
    return fileNames.map { fileName ->
        TaskError(fileName = fileName, message = diagnostic, detail = detail)
    }
}
