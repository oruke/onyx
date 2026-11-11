package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import java.nio.file.FileAlreadyExistsException

/**
 * 为保留相对目录结构而需按序确保存在的单个中间目录节点。
 *
 * @property parentLocation 父目录位置。
 * @property directoryName 子目录名称。
 * @property directoryLocation 子目录规范化位置。
 */
data class TransferDirectoryStep(
    val parentLocation: String,
    val directoryName: String,
    val directoryLocation: String,
)

/** 为结构化文件传输按序校验并创建中间目录。 */
internal object FileTransferDirectoryOperations {
    /**
     * 按外层到内层顺序确保中间目录链存在，并返回本次实际新建的目录条目。
     *
     * @param chain 待确保存在的中间目录链。
     * @param ensuredLocations 本次传输任务内已确认存在的目录位置缓存。
     * @param providerRegistry 统一目录查询注册表。
     * @param fileCommandService 统一文件变更服务。
     * @return 本次实际新建的目录条目列表。
     */
    suspend fun ensureChain(
        chain: List<TransferDirectoryStep>,
        ensuredLocations: MutableSet<String>,
        providerRegistry: VfsProviderRegistry,
        fileCommandService: FileCommandService,
    ): List<VFile> {
        if (chain.isEmpty()) return emptyList()
        val created = mutableListOf<VFile>()
        for (step in chain) {
            if (step.directoryLocation in ensuredLocations) continue
            val newlyCreated = ensureSingleTransferDirectory(
                step = step,
                ensuredLocations = ensuredLocations,
                providerRegistry = providerRegistry,
                fileCommandService = fileCommandService,
            )
            if (newlyCreated != null) {
                created += newlyCreated
            }
        }
        return created
    }
}

private suspend fun ensureSingleTransferDirectory(
    step: TransferDirectoryStep,
    ensuredLocations: MutableSet<String>,
    providerRegistry: VfsProviderRegistry,
    fileCommandService: FileCommandService,
): VFile? {
    val siblings = providerRegistry.list(step.parentLocation).getOrThrow()
    val exactMatch = siblings.firstOrNull { entry ->
        entry.location == step.directoryLocation || entry.name == step.directoryName
    }
    return if (exactMatch != null) {
        if (exactMatch.kind != VFileKind.DIRECTORY) {
            throw FileAlreadyExistsException(exactMatch.location)
        }
        ensuredLocations += step.directoryLocation
        ensuredLocations += exactMatch.location
        null
    } else {
        createOrReuseIgnoreCaseDirectory(step, ensuredLocations, providerRegistry, fileCommandService)
    }
}

private suspend fun createOrReuseIgnoreCaseDirectory(
    step: TransferDirectoryStep,
    ensuredLocations: MutableSet<String>,
    providerRegistry: VfsProviderRegistry,
    fileCommandService: FileCommandService,
): VFile? {
    return fileCommandService.createDirectory(step.parentLocation, step.directoryName).fold(
        onSuccess = { createdDir ->
            ensuredLocations += step.directoryLocation
            ensuredLocations += createdDir.location
            createdDir
        },
        onFailure = { failure ->
            val existingIgnoreCase = providerRegistry.list(step.parentLocation)
                .getOrNull()
                ?.firstOrNull { entry ->
                    entry.kind == VFileKind.DIRECTORY && entry.name.equals(step.directoryName, ignoreCase = true)
                } ?: throw failure
            ensuredLocations += step.directoryLocation
            ensuredLocations += existingIgnoreCase.location
            null
        },
    )
}
