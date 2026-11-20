package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Batch 3 共享领域用例回归测试：覆盖用户命令防二次注入与空行过滤（`SET-06`）、
 * 批量重命名冲突检测与链式位移/互换两阶段执行与部分失败汇报（`OPS-003`）。
 */
class Batch3UseCaseRegressionTest {
    /** 验证文件名中包含 `{parent}` / `{name}` / `{selection}` 字面量时不会被二次替换注入，且空白参数行会被过滤。 */
    @Test
    fun userCommandRendersPlaceholdersInSinglePassAndFiltersBlankArgLines() {
        val useCase = UserCommandUseCase()
        val entry = testEntry(
            id = "e1",
            name = "evil_{parent}_{selection}.txt",
            location = "/work/evil_{parent}_{selection}.txt",
            parent = "/work",
        )
        val definition = UserCommandDefinition(
            id = "cmd-1",
            name = "Inspect",
            executable = "tool",
            argumentTemplate = listOf(
                "  --file={name}  ",
                "",
                "   ",
                "{location}",
                "{selection}",
            ),
            placements = setOf(UserCommandPlacement.TOOLBAR),
        )

        val request = useCase.renderInvocation(
            command = definition,
            currentLocation = "/work",
            selection = listOf(entry),
        )

        assertEquals(
            listOf(
                "--file=evil_{parent}_{selection}.txt",
                "/work/evil_{parent}_{selection}.txt",
                "/work/evil_{parent}_{selection}.txt",
            ),
            request.arguments,
        )
        assertEquals("/work", request.workingDirectory)
    }

    /** 验证批量重命名冲突检测可检出非法名、批内重名与已有兄弟冲突，且不误报链式位移和双向互换。 */
    @Test
    fun batchRenameConflictDetectionHandlesValidChainsSwapsAndRealConflicts() {
        val file1 = testEntry("1", "1.txt", "/dir/1.txt", "/dir")
        val file2 = testEntry("2", "2.txt", "/dir/2.txt", "/dir")
        val file3 = testEntry("3", "existing.txt", "/dir/existing.txt", "/dir")
        val siblings = mapOf("/dir" to setOf("1.txt", "2.txt", "existing.txt", "locked.txt"))

        // 合法链式位移 1.txt -> 2.txt, 2.txt -> 3.txt 不应误报冲突
        val chainConflicts = BatchRenameDraftPlanner.findConflicts(
            renameResults = listOf(file1 to "2.txt", file2 to "3.txt"),
            siblingNamesByParent = siblings,
            caseInsensitive = true,
        )
        assertTrue(chainConflicts.isEmpty(), "合法链式位移不应误报冲突: $chainConflicts")

        // 合法双向互换 1.txt <-> 2.txt 不应误报冲突
        val swapConflicts = BatchRenameDraftPlanner.findConflicts(
            renameResults = listOf(file1 to "2.txt", file2 to "1.txt"),
            siblingNamesByParent = siblings,
            caseInsensitive = true,
        )
        assertTrue(swapConflicts.isEmpty(), "合法双向互换不应误报冲突: $swapConflicts")

        // 非法名、批内重复（大小写不敏感）与已有未参与兄弟冲突（LOCKED.TXT）应全部检出
        val realConflicts = BatchRenameDraftPlanner.findConflicts(
            renameResults = listOf(
                file1 to "LOCKED.TXT",
                file2 to "same.txt",
                file3 to "SAME.TXT",
            ),
            siblingNamesByParent = siblings,
            caseInsensitive = true,
        )
        assertEquals(BatchRenameConflictKind.EXISTING_SIBLING, realConflicts["1"])
        assertEquals(BatchRenameConflictKind.DUPLICATE_TARGET, realConflicts["2"])
        assertEquals(BatchRenameConflictKind.DUPLICATE_TARGET, realConflicts["3"])

        val invalidConflicts = BatchRenameDraftPlanner.findConflicts(
            renameResults = listOf(file1 to "bad/name.txt"),
            siblingNamesByParent = siblings,
            caseInsensitive = true,
        )
        assertEquals(BatchRenameConflictKind.INVALID_NAME, invalidConflicts["1"])
    }

    /** 验证两阶段临时名中转可无冲突完成 `1.txt -> 2.txt, 2.txt -> 3.txt` 链式位移与 `a <-> b` 互换。 */
    @Test
    fun batchRenameUseCaseExecutesChainShiftAndCycleSwapViaStagingNames() = runBlocking {
        val service = InMemoryRenameFileCommandService(
            initialFiles = mapOf(
                "/dir/1.txt" to "content-1",
                "/dir/2.txt" to "content-2",
            ),
        )
        val useCase = BatchRenameUseCase(service)
        val e1 = testEntry("1", "1.txt", "/dir/1.txt", "/dir")
        val e2 = testEntry("2", "2.txt", "/dir/2.txt", "/dir")

        // 链式位移：1.txt -> 2.txt, 2.txt -> 3.txt
        val chainSummary = useCase.executeCollecting(
            BatchRenameUseCase.BatchRenameRequest(listOf(e1 to "2.txt", e2 to "3.txt")),
        ) {}
        assertTrue(chainSummary.failures.isEmpty())
        assertEquals(2, chainSummary.succeededPairs.size)
        assertEquals("content-1", service.files["/dir/2.txt"])
        assertEquals("content-2", service.files["/dir/3.txt"])
        assertFalse("/dir/1.txt" in service.files)

        // 双向互换：2.txt <-> 3.txt
        val swappedE1 = chainSummary.renamedEntriesByOriginalId.getValue("1")
        val swappedE2 = chainSummary.renamedEntriesByOriginalId.getValue("2")
        val swapSummary = useCase.executeCollecting(
            BatchRenameUseCase.BatchRenameRequest(listOf(swappedE1 to "3.txt", swappedE2 to "2.txt")),
        ) {}
        assertTrue(swapSummary.failures.isEmpty())
        assertEquals("content-2", service.files["/dir/2.txt"])
        assertEquals("content-1", service.files["/dir/3.txt"])
    }

    /** 验证批量重命名部分失败时继续处理其余项并精确返回成功子集与失败条目列表。 */
    @Test
    fun batchRenameUseCaseReportsPartialSuccessesAndFailures() = runBlocking {
        val service = InMemoryRenameFileCommandService(
            initialFiles = mapOf(
                "/dir/ok.txt" to "ok",
                "/dir/fail.txt" to "fail",
            ),
            failOriginalNames = setOf("fail.txt"),
        )
        val useCase = BatchRenameUseCase(service)
        val okEntry = testEntry("ok", "ok.txt", "/dir/ok.txt", "/dir")
        val failEntry = testEntry("fail", "fail.txt", "/dir/fail.txt", "/dir")

        val summary = useCase.executeCollecting(
            BatchRenameUseCase.BatchRenameRequest(listOf(okEntry to "ok-renamed.txt", failEntry to "fail-renamed.txt")),
        ) {}

        assertEquals(1, summary.succeededPairs.size)
        assertEquals("ok-renamed.txt", summary.succeededPairs.single().second)
        assertEquals(1, summary.failures.size)
        assertEquals("fail", summary.failures.single().entry.id)
        assertTrue("/dir/ok-renamed.txt" in service.files)
        assertTrue("/dir/fail.txt" in service.files)
    }

    /** 验证阶段 2 链式失败、非容错中止及协程取消均完整回滚未落位项，磁盘绝不残留 `.onyx-br-*` 临时文件（REV-05）。 */
    @Test
    fun batchRenameStage2FailureAndCancellationLeaveZeroStagingTempFiles() = runBlocking {
        // 1. 链式 1->2, 2->3 且目标 3.txt 在阶段 2 落位失败：逆拓扑序先做 2->3，失败后安全回滚为 2.txt，无 .onyx-br-* 残留
        val chainService = InMemoryRenameFileCommandService(
            initialFiles = mapOf("/dir/1.txt" to "c1", "/dir/2.txt" to "c2"),
            failTargetNames = setOf("3.txt"),
        )
        val e1 = testEntry("1", "1.txt", "/dir/1.txt", "/dir")
        val e2 = testEntry("2", "2.txt", "/dir/2.txt", "/dir")
        val chainSummary = BatchRenameUseCase(chainService).executeCollecting(
            BatchRenameUseCase.BatchRenameRequest(listOf(e1 to "2.txt", e2 to "3.txt")),
        ) {}
        assertTrue(chainSummary.failures.isNotEmpty())
        assertTrue(
            chainService.files.keys.none { it.contains(".onyx-br-") },
            "链式阶段 2 失败后绝不能残留 .onyx-br-* 临时文件: ${chainService.files.keys}",
        )
        assertEquals("c2", chainService.files["/dir/2.txt"])

        // 2. 流式 execute() (!continueOnError) 在阶段 2 首项失败时必须回滚全部剩余暂存项
        val abortService = InMemoryRenameFileCommandService(
            initialFiles = mapOf("/dir/a.txt" to "ca", "/dir/b.txt" to "cb"),
            failTargetNames = setOf("b.txt"),
        )
        val a = testEntry("a", "a.txt", "/dir/a.txt", "/dir")
        val b = testEntry("b", "b.txt", "/dir/b.txt", "/dir")
        assertFailsWith<IllegalStateException> {
            BatchRenameUseCase(abortService)
                .execute(BatchRenameUseCase.BatchRenameRequest(listOf(a to "b.txt", b to "a.txt")))
                .collect()
        }
        assertTrue(
            abortService.files.keys.none { it.contains(".onyx-br-") },
            "非容错中止后绝不能残留 .onyx-br-* 临时文件: ${abortService.files.keys}",
        )
        assertEquals("ca", abortService.files["/dir/a.txt"])
        assertEquals("cb", abortService.files["/dir/b.txt"])

        // 3. 阶段 2 途中遇到 CancellationException 时经 NonCancellable 回滚所有暂存项
        val cancelService = InMemoryRenameFileCommandService(
            initialFiles = mapOf("/dir/a.txt" to "ca", "/dir/b.txt" to "cb"),
            cancelOnTargetNames = setOf("a.txt"),
        )
        assertFailsWith<CancellationException> {
            BatchRenameUseCase(cancelService).executeCollecting(
                BatchRenameUseCase.BatchRenameRequest(listOf(a to "b.txt", b to "a.txt")),
            ) {}
        }
        assertTrue(
            cancelService.files.keys.none { it.contains(".onyx-br-") },
            "取消任务后绝不能残留 .onyx-br-* 临时文件: ${cancelService.files.keys}",
        )
        assertEquals("ca", cancelService.files["/dir/a.txt"])
        assertEquals("cb", cancelService.files["/dir/b.txt"])
    }

    private fun testEntry(id: String, name: String, location: String, parent: String): VFile {
        return VFile(
            id = id,
            name = name,
            location = location,
            parentLocation = parent,
            kind = VFileKind.FILE,
            sizeBytes = 10L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = setOf(VFileCapability.RENAME),
        )
    }
}

/**
 * 严格检测目标重名冲突的内存文件重命名测试桩，用于验证两阶段暂存重命名的原子性与正确性。
 */
private class InMemoryRenameFileCommandService(
    initialFiles: Map<String, String>,
    private val failOriginalNames: Set<String> = emptySet(),
    failTargetNames: Set<String> = emptySet(),
    cancelOnTargetNames: Set<String> = emptySet(),
) : FileCommandService {
    val files = LinkedHashMap(initialFiles)
    private val remainingFailTargets = failTargetNames.toMutableSet()
    private val remainingCancelTargets = cancelOnTargetNames.toMutableSet()

    override suspend fun rename(entry: VFile, targetName: String): Result<VFile> {
        if (targetName in remainingCancelTargets && entry.name.startsWith(".onyx-br-")) {
            remainingCancelTargets.remove(targetName)
            throw CancellationException("Simulated user cancellation on $targetName")
        }
        if (entry.name in failOriginalNames) {
            return Result.failure(IllegalStateException("Simulated lock on ${entry.name} -> $targetName"))
        }
        if (targetName in remainingFailTargets) {
            remainingFailTargets.remove(targetName)
            return Result.failure(IllegalStateException("Simulated stage-2 failure on ${entry.name} -> $targetName"))
        }
        val parent = entry.parentLocation ?: ""
        val targetLocation = "$parent/$targetName"
        if (targetLocation in files && targetLocation != entry.location) {
            return Result.failure(IllegalStateException("Target already exists: $targetLocation"))
        }
        val content = files.remove(entry.location)
            ?: return Result.failure(IllegalStateException("Source missing: ${entry.location}"))
        files[targetLocation] = content
        return Result.success(entry.copy(id = targetLocation, name = targetName, location = targetLocation))
    }

    override suspend fun createFile(parentLocation: String, name: String): Result<VFile> =
        Result.failure(UnsupportedOperationException())

    override suspend fun createDirectory(parentLocation: String, name: String): Result<VFile> =
        Result.failure(UnsupportedOperationException())

    override suspend fun delete(entries: List<VFile>): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = Result.failure(UnsupportedOperationException())

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = Result.failure(UnsupportedOperationException())
}
