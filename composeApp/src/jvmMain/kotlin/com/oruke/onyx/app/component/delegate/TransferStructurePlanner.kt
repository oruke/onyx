package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.usecase.TransferDirectoryStep
import com.oruke.onyx.vfs.api.FileRepository
import java.nio.file.FileAlreadyExistsException

/**
 * 相对目录结构保留规划结果。
 *
 * @property entries 待传输的有效选中条目。
 * @property targetDirectoryLocation 根目标目录位置。
 * @property entryTargetDirectories 嵌套子项对应的实际落盘父目录映射（顶层条目省略，默认回退至根目标目录）。
 * @property entryDirectoryChains 每个嵌套子项需按外层到内层确保存在的中间目录链。
 * @property allDirectorySteps 所有嵌套子项去重且保持外层到内层顺序的中间目录步骤。
 */
internal data class PlannedTransferStructure(
    val entries: List<VFile>,
    val targetDirectoryLocation: String,
    val entryTargetDirectories: Map<String, String> = emptyMap(),
    val entryDirectoryChains: Map<String, List<TransferDirectoryStep>> = emptyMap(),
    val allDirectorySteps: List<TransferDirectoryStep> = emptyList(),
) {
    /**
     * 获取指定条目的实际落盘父目录。
     *
     * @param entry 待查询条目。
     * @return 实际目标目录位置。
     */
    fun effectiveTargetOf(entry: VFile): String = entryTargetDirectories[entry.id] ?: targetDirectoryLocation
}

/** 规划内联展开条目的相对目录结构并执行按实际目标目录分组的冲突检测。 */
internal object TransferStructurePlanner {
    /**
     * 根据来源基准目录与根目标目录计算每个条目的目标父目录及中间目录创建链。
     *
     * 当目标目录是来源基准目录内部的严格子目录（如在同一目录内将已展开子项拖入兄弟文件夹归档）时，
     * 直接落入该目标子目录而不重复拼接外层已展开目录名；跨目录或跨面板传输则完整保留相对目录层级。
     *
     * @param entries 待传输条目。
     * @param targetDirectoryLocation 用户指定的根目标目录。
     * @param sourceBaseLocation 来源面板根位置；外部剪贴板等无基准来源时为 `null`。
     * @param pathService 统一 VFS 路径服务。
     * @return 结构化传输规划。
     */
    fun plan(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        sourceBaseLocation: String?,
        pathService: VfsPathService,
    ): PlannedTransferStructure {
        if (sourceBaseLocation == null || entries.isEmpty()) {
            return PlannedTransferStructure(entries = entries, targetDirectoryLocation = targetDirectoryLocation)
        }
        val normalizedBase = pathService.normalizeLocation(sourceBaseLocation)
        val normalizedTarget = pathService.normalizeLocation(targetDirectoryLocation)
        val isIntraSourceSubdirectory = normalizedTarget != normalizedBase &&
            pathService.isSameOrChildOf(targetDirectoryLocation, sourceBaseLocation)
        if (isIntraSourceSubdirectory) {
            return PlannedTransferStructure(entries = entries, targetDirectoryLocation = targetDirectoryLocation)
        }

        val entryTargets = LinkedHashMap<String, String>()
        val entryChains = LinkedHashMap<String, List<TransferDirectoryStep>>()
        val uniqueSteps = LinkedHashMap<String, TransferDirectoryStep>()

        entries.forEach { entry ->
            val parent = entry.parentLocation
            val segments = if (parent != null) {
                pathService.relativePathSegments(sourceBaseLocation, parent).orEmpty()
            } else {
                emptyList()
            }
            if (segments.isNotEmpty()) {
                val chain = buildDirectoryChain(targetDirectoryLocation, segments, pathService)
                entryTargets[entry.id] = chain.last().directoryLocation
                entryChains[entry.id] = chain
                chain.forEach { step -> uniqueSteps.putIfAbsent(step.directoryLocation, step) }
            }
        }

        return PlannedTransferStructure(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            entryTargetDirectories = entryTargets,
            entryDirectoryChains = entryChains,
            allDirectorySteps = uniqueSteps.values.toList(),
        )
    }

    /**
     * 按每个条目的实际目标目录检测同名冲突，尚未创建的中间目录无需额外磁盘列表且不可能产生冲突。
     *
     * @param plan 传输结构规划。
     * @param fileRepository 统一目录查询仓库。
     * @return 实际目标目录中已存在同名条目的源条目列表。
     */
    suspend fun detectConflictingEntries(
        plan: PlannedTransferStructure,
        fileRepository: FileRepository,
    ): List<VFile> {
        val listingCache = mutableMapOf<String, List<VFile>>()
        listingCache[plan.targetDirectoryLocation] = fileRepository.list(plan.targetDirectoryLocation).getOrThrow()
        return plan.entries.filter { entry ->
            val existingNames = resolveExistingTargetNames(entry, plan, fileRepository, listingCache)
            existingNames?.contains(entry.name) == true
        }
    }

    private fun buildDirectoryChain(
        targetRoot: String,
        segments: List<String>,
        pathService: VfsPathService,
    ): List<TransferDirectoryStep> {
        val steps = ArrayList<TransferDirectoryStep>(segments.size)
        var currentParent = targetRoot
        segments.forEach { segment ->
            val nextLocation = pathService.resolveChildDirectory(currentParent, listOf(segment))
            steps += TransferDirectoryStep(
                parentLocation = currentParent,
                directoryName = segment,
                directoryLocation = nextLocation,
            )
            currentParent = nextLocation
        }
        return steps
    }

    private suspend fun resolveExistingTargetNames(
        entry: VFile,
        plan: PlannedTransferStructure,
        fileRepository: FileRepository,
        listingCache: MutableMap<String, List<VFile>>,
    ): Set<String>? {
        val chain = plan.entryDirectoryChains[entry.id].orEmpty()
        var currentEntries: List<VFile>? = listingCache[plan.targetDirectoryLocation]
        for (step in chain) {
            val siblings = currentEntries ?: break
            val matched = findMatchingTargetChild(siblings, step)
            currentEntries = when {
                matched == null -> null
                matched.kind != VFileKind.DIRECTORY -> throw FileAlreadyExistsException(matched.location)
                else -> listingCache.getOrPut(step.directoryLocation) {
                    fileRepository.list(matched.location).getOrThrow()
                }
            }
        }
        return currentEntries?.mapTo(mutableSetOf()) { child -> child.name }
    }

    private fun findMatchingTargetChild(
        siblings: List<VFile>,
        step: TransferDirectoryStep,
    ): VFile? {
        return siblings.firstOrNull { candidate ->
            val exactMatch = candidate.location == step.directoryLocation || candidate.name == step.directoryName
            val ignoreCaseDir = candidate.kind == VFileKind.DIRECTORY &&
                candidate.name.equals(step.directoryName, ignoreCase = true)
            exactMatch || ignoreCaseDir
        }
    }
}
