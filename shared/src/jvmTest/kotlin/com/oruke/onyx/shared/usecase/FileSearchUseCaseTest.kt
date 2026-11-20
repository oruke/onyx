package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 全局递归搜索用例健壮性回归测试（子目录无权限跳过、根目录失败透传、循环与深度上限防护）。
 */
class FileSearchUseCaseTest {
    /**
     * 遍历遇到单个受保护系统子目录拒绝访问时，应跳过该子目录并继续完成其余目录扫描。
     */
    @Test
    fun skipsInaccessibleSubdirectoryAndContinuesScanningSiblings() = runBlocking {
        val root = "mock://root"
        val protectedDir = "mock://root/System Volume Information"
        val readableDir = "mock://root/Documents"
        val repo = FakeSearchFileRepository(
            listings = mapOf(
                root to Result.success(
                    listOf(
                        directory(protectedDir, "System Volume Information", root),
                        directory(readableDir, "Documents", root),
                    )
                ),
                protectedDir to Result.failure(
                    VfsProviderException(
                        VfsProviderError.PermissionDenied(VfsProtocol.LOCAL, protectedDir, "Access denied"),
                    )
                ),
                readableDir to Result.success(
                    listOf(
                        file("$readableDir/report-2026.txt", "report-2026.txt", readableDir),
                    )
                ),
            ),
        )

        val events = FileSearchUseCase(repo).search(
            FileSearchRequest(rootLocation = root, query = "report"),
        ).toList()

        assertIs<FileSearchEvent.Completed>(events.last())
        val matchedNames = events.filterIsInstance<FileSearchEvent.Results>()
            .last()
            .entries
            .map { it.name }
        assertEquals(listOf("report-2026.txt"), matchedNames)
    }

    /**
     * 搜索根目录本身不可列举时仍必须明确返回失败事件。
     */
    @Test
    fun failsWhenRootDirectoryItselfCannotBeListed(): Unit = runBlocking {
        val root = "mock://unreadable-root"
        val repo = FakeSearchFileRepository(
            listings = mapOf(
                root to Result.failure(
                    VfsProviderException(
                        VfsProviderError.PermissionDenied(VfsProtocol.LOCAL, root, "Access denied"),
                    )
                ),
            ),
        )

        val events = FileSearchUseCase(repo).search(
            FileSearchRequest(rootLocation = root, query = "anything"),
        ).toList()

        assertIs<FileSearchEvent.Failed>(events.single())
    }

    /**
     * 遇到回指祖先目录的循环路径或超过最大深度限制时必须安全终止，不发生无限递归。
     */
    @Test
    fun preventsInfiniteLoopAndRespectsMaxDepth() = runBlocking {
        val root = "mock://cycle-root"
        val child = "mock://cycle-root/a"
        val depth2 = "mock://cycle-root/a/b"
        val depth3 = "mock://cycle-root/a/b/c"
        val repo = FakeSearchFileRepository(
            listings = mapOf(
                root to Result.success(
                    listOf(
                        directory(child, "a", root),
                        // 末尾斜杠变体回指根目录，应被规范化键去重。
                        directory("$root/", "root-loop", root),
                    )
                ),
                child to Result.success(
                    listOf(
                        directory(depth2, "b", child),
                        file("$child/match-depth1.txt", "match-depth1.txt", child),
                    )
                ),
                depth2 to Result.success(
                    listOf(
                        directory(depth3, "c", depth2),
                        file("$depth2/match-depth2.txt", "match-depth2.txt", depth2),
                    )
                ),
                depth3 to Result.success(
                    listOf(
                        file("$depth3/match-depth3.txt", "match-depth3.txt", depth3),
                    )
                ),
            ),
        )

        val events = FileSearchUseCase(repo).search(
            FileSearchRequest(rootLocation = root, query = "match", maxDepth = 1),
        ).toList()

        assertIs<FileSearchEvent.Completed>(events.last())
        val matchedNames = events.filterIsInstance<FileSearchEvent.Results>()
            .last()
            .entries
            .map { it.name }
        assertEquals(listOf("match-depth1.txt"), matchedNames)
        assertTrue(depth3 !in repo.visitedLocations)
    }

    /**
     * 内容搜索遇到单个受锁/无权限文件异常时跳过继续扫描，而遇到协程取消必须向上传播不被吞掉。
     */
    @Test
    fun skipsSingleFileContentErrorAndPropagatesCancellation(): Unit = runBlocking {
        val root = "mock://content-root"
        val lockedFile = file("$root/locked.txt", "locked.txt", root)
        val okFile = file("$root/ok.txt", "ok.txt", root)
        val repo = FakeSearchFileRepository(
            listings = mapOf(root to Result.success(listOf(lockedFile, okFile))),
        )
        val contentService = object : FileContentSearchService {
            override fun supports(entry: VFile): Boolean = entry.kind == VFileKind.FILE
            override suspend fun contains(entry: VFile, query: String, maxBytes: Long): Result<Boolean> {
                return if (entry.name == "locked.txt") {
                    Result.failure(IllegalStateException("File locked by OS"))
                } else {
                    Result.success(true)
                }
            }
        }

        val events = FileSearchUseCase(repo, contentService).search(
            FileSearchRequest(rootLocation = root, query = "content:needle"),
        ).toList()
        assertIs<FileSearchEvent.Completed>(events.last())
        assertEquals(listOf("ok.txt"), events.filterIsInstance<FileSearchEvent.Results>().last().entries.map { it.name })

        // 若单文件明确返回 MSG_SEARCH_CONTENT_UNSUPPORTED 仍必须透传 Failed 而非静默跳过。
        val unsupportedService = object : FileContentSearchService {
            override fun supports(entry: VFile): Boolean = entry.kind == VFileKind.FILE
            override suspend fun contains(entry: VFile, query: String, maxBytes: Long): Result<Boolean> {
                return Result.failure(
                    LocalizedException(I18nMessage(MessageKey.MSG_SEARCH_CONTENT_UNSUPPORTED, entry.location)),
                )
            }
        }
        val unsupportedEvents = FileSearchUseCase(repo, unsupportedService).search(
            FileSearchRequest(rootLocation = root, query = "content:needle"),
        ).toList()
        val unsupportedFailure = assertIs<FileSearchEvent.Failed>(unsupportedEvents.single())
        assertEquals(
            MessageKey.MSG_SEARCH_CONTENT_UNSUPPORTED,
            assertIs<LocalizedException>(unsupportedFailure.failure).userMessage.key,
        )

        // 协程取消进入子目录列举时必须直接向外传播 CancellationException。
        val cancellingDir = "$root/cancelling"
        val cancelRepo = FakeSearchFileRepository(
            listings = mapOf(
                root to Result.success(listOf(directory(cancellingDir, "cancelling", root))),
                cancellingDir to Result.failure(CancellationException("cancelled")),
            ),
        )
        assertFailsWith<CancellationException> {
            FileSearchUseCase(cancelRepo).search(FileSearchRequest(rootLocation = root, query = "anything")).toList()
        }
    }

    /**
     * 校验真实本地临时目录下 `resolveCanonicalSearchDirectoryKey` 通过 `Dispatchers.IO` 统一解析规范路径；
     * 若当前环境允许创建符号链接，同时验证回指祖先的符号链接会被解析为同一规范键。
     */
    @Test
    fun resolvesCanonicalKeyForRealLocalDirectoriesAndSymlinks(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("onyx-search-canonical-")
        try {
            val subDir = Files.createDirectory(tempRoot.resolve("sub"))
            val directKey = resolveCanonicalSearchDirectoryKey(tempRoot.toString())
            val relativeBounce = subDir.resolve("..").toString()
            assertEquals(directKey, resolveCanonicalSearchDirectoryKey(relativeBounce))

            val linkPath = subDir.resolve("loop-to-root")
            val createdLink = runCatching { Files.createSymbolicLink(linkPath, tempRoot) }.isSuccess
            if (createdLink) {
                assertEquals(directKey, resolveCanonicalSearchDirectoryKey(linkPath.toString()))
            }
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    /**
     * 验证双引号包裹的多词内容查询（如 `content:"hello world"`）不会被空白拆分为文件名条件，
     * 能够完整透传多词短语给内容检索服务并命中不含这些词的文件名。
     */
    @Test
    fun quotedMultiWordContentQueryMatchesFileContentWithoutSplittingIntoNameTokens(): Unit = runBlocking {
        val root = "mock://quoted-content"
        val noteFile = file("$root/notes.txt", "notes.txt", root)
        val otherFile = file("$root/other.txt", "other.txt", root)
        val repo = FakeSearchFileRepository(
            listings = mapOf(root to Result.success(listOf(noteFile, otherFile))),
        )
        val seenQueries = mutableListOf<String>()
        val contentService = object : FileContentSearchService {
            override fun supports(entry: VFile): Boolean = entry.kind == VFileKind.FILE
            override suspend fun contains(entry: VFile, query: String, maxBytes: Long): Result<Boolean> {
                seenQueries += query
                return Result.success(entry.name == "notes.txt" && query == "hello world")
            }
        }

        val criteria = FileSearchCriteria.parse("""content:"hello world" type:file""")
        assertEquals("", criteria.nameQuery)
        assertEquals("hello world", criteria.contentQuery)
        assertEquals(VFileKind.FILE, criteria.kind)

        val escapedCriteria = FileSearchCriteria.parse("""contains:"say \"hi\" now"""")
        assertEquals("", escapedCriteria.nameQuery)
        assertEquals("""say "hi" now""", escapedCriteria.contentQuery)

        // 未闭合且以转义引号结尾时，不能把 \" 误当作闭合引号剥离。
        val danglingEscaped = FileSearchCriteria.parse("""content:"hello\"""")
        assertEquals("", danglingEscaped.nameQuery)
        assertEquals("""hello"""", danglingEscaped.contentQuery)

        val events = FileSearchUseCase(repo, contentService).search(
            FileSearchRequest(rootLocation = root, query = """content:"hello world" type:file"""),
        ).toList()
        assertIs<FileSearchEvent.Completed>(events.last())
        assertEquals(listOf("notes.txt"), events.filterIsInstance<FileSearchEvent.Results>().last().entries.map { it.name })
        assertTrue(seenQueries.all { it == "hello world" })
    }

    /**
     * 验证带本地时区偏移的 ISO-8601 时间边界（如东八区 `2026-11-19T00:00:00+08:00`）能精确命中凌晨文件并排除前一日文件。
     */
    @Test
    fun isoOffsetModifiedFilterMatchesLocalMidnightAccurately() {
        val criteria = FileSearchCriteria.parse("modified>=2026-11-19T00:00:00+08:00")
        // 北京时间 2026-11-19 00:25:00+08:00 == UTC 2026-11-18T16:25:00Z
        val todayEarlyMorning = java.time.Instant.parse("2026-11-18T16:25:00Z").toEpochMilli()
        // 北京时间 2026-11-18 23:59:00+08:00 == UTC 2026-11-18T15:59:00Z
        val yesterdayLateNight = java.time.Instant.parse("2026-11-18T15:59:00Z").toEpochMilli()

        val earlyFile = file("mock://tz/early.txt", "early.txt", "mock://tz")
            .copy(modifiedAtEpochMillis = todayEarlyMorning)
        val lateFile = file("mock://tz/late.txt", "late.txt", "mock://tz")
            .copy(modifiedAtEpochMillis = yesterdayLateNight)

        assertTrue(criteria.matchesMetadata(earlyFile), "东八区当天 00:25 修改的文件必须命中 TODAY")
        assertFalse(criteria.matchesMetadata(lateFile), "东八区前一日 23:59 修改的文件必须被 TODAY 排除")
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            Files.deleteIfExists(root)
            return
        }
        Files.list(root).use { stream ->
            stream.forEach { child -> deleteRecursively(child) }
        }
        Files.deleteIfExists(root)
    }

    private fun directory(location: String, name: String, parent: String) = VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = parent,
        kind = VFileKind.DIRECTORY,
        sizeBytes = null,
        modifiedAtEpochMillis = 0L,
        hidden = false,
        capabilities = setOf(VFileCapability.LIST_CHILDREN),
    )

    private fun file(location: String, name: String, parent: String) = VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = parent,
        kind = VFileKind.FILE,
        sizeBytes = 64L,
        modifiedAtEpochMillis = 0L,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT),
    )

    private class FakeSearchFileRepository(
        private val listings: Map<String, Result<List<VFile>>>,
    ) : FileRepository {
        val visitedLocations = mutableListOf<String>()

        override suspend fun list(location: String): Result<List<VFile>> {
            visitedLocations += location
            return listings[location] ?: Result.success(emptyList())
        }

        override fun defaultLocation(): String = listings.keys.firstOrNull().orEmpty()
    }
}
