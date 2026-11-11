package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.usecase.TransferDirectoryStep
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository

/**
 * 按由内向外的顺序清理本次历史操作新建且当前已为空的中间目录。
 *
 * 若用户在复制或移动后向该中间目录放入了其他文件，目录非空时予以保留。
 *
 * @param createdDirectories 本次操作新建的中间目录（外层到内层顺序）。
 * @param repository 统一目录查询服务。
 * @param commands 统一文件变更服务。
 */
internal suspend fun deleteEmptyCreatedDirectories(
    createdDirectories: List<VFile>,
    repository: FileRepository,
    commands: FileCommandService,
) {
    createdDirectories.asReversed().forEach { dir ->
        val children = repository.list(dir.location).getOrNull() ?: return@forEach
        if (children.isEmpty()) {
            val parentLocation = dir.parentLocation
            val currentDir = if (parentLocation != null) {
                repository.list(parentLocation).getOrNull()
                    ?.firstOrNull { candidate -> candidate.location == dir.location || candidate.name == dir.name }
                    ?: dir
            } else {
                dir
            }
            commands.delete(listOf(currentDir)).getOrThrow()
        }
    }
}

/**
 * 在重做结构化传输前按外层到内层顺序确保中间目录链存在，并返回本次重做新建的目录条目。
 *
 * @param steps 中间目录创建步骤（外层到内层顺序）。
 * @param repository 统一目录查询服务。
 * @param commands 统一文件变更服务。
 * @return 本次重做实际新建的中间目录列表。
 */
internal suspend fun ensureHistoryDirectorySteps(
    steps: List<TransferDirectoryStep>,
    repository: FileRepository,
    commands: FileCommandService,
): List<VFile> {
    if (steps.isEmpty()) return emptyList()
    val created = mutableListOf<VFile>()
    steps.forEach { step ->
        val siblings = repository.list(step.parentLocation).getOrThrow()
        val matched = siblings.firstOrNull { entry ->
            val exact = entry.location == step.directoryLocation || entry.name == step.directoryName
            val ignoreCaseDir = entry.kind == VFileKind.DIRECTORY &&
                entry.name.equals(step.directoryName, ignoreCase = true)
            exact || ignoreCaseDir
        }
        when {
            matched == null -> created += commands.createDirectory(step.parentLocation, step.directoryName).getOrThrow()
            matched.kind != VFileKind.DIRECTORY ->
                throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
        }
    }
    return created
}
