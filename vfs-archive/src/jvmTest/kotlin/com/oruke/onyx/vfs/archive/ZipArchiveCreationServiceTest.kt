package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZIP 压缩包创建服务测试。
 */
class ZipArchiveCreationServiceTest {
    /**
     * 校验创建 ZIP 时会递归写入目录、保留空目录并上传到目标 provider。
     *
     * @return 无返回值。
     */
    @Test
    fun createsZipFromFilesAndDirectoriesThroughVfsStreams() = runBlocking {
        val project = directory("$SOURCE_PREFIX/project", "project")
        val documents = directory("$SOURCE_PREFIX/project/documents", "documents")
        val empty = directory("$SOURCE_PREFIX/project/empty", "empty")
        val note = file("$SOURCE_PREFIX/project/note.txt", "note.txt")
        val guide = file("$SOURCE_PREFIX/project/documents/guide.txt", "guide.txt")
        val todo = file("$SOURCE_PREFIX/todo.txt", "todo.txt")
        val fileRepository = FakeFileRepository(
            entriesByLocation = mapOf(
                project.location to listOf(documents, empty, note),
                documents.location to listOf(guide),
                empty.location to emptyList(),
            )
        )
        val sourceContentService = FakeContentService(SOURCE_PREFIX).apply {
            seed(note.location, "note")
            seed(guide.location, "guide")
            seed(todo.location, "todo")
        }
        val targetContentService = FakeContentService(TARGET_PREFIX)
        val progressEvents = mutableListOf<ZipArchiveCreationProgress>()
        val service = ZipArchiveCreationService(
            fileRepository = fileRepository,
            contentServices = listOf(sourceContentService, targetContentService),
        )

        val result = service.create(
            request = ZipArchiveCreationRequest(
                entries = listOf(project, todo),
                targetDirectoryLocation = "$TARGET_PREFIX/archives",
                archiveName = "backup",
            ),
            progressSink = { progress -> progressEvents += progress },
        )

        val creation = result.getOrThrow()
        val archiveBytes = targetContentService.writtenFile("$TARGET_PREFIX/archives", "backup.zip")
            ?: error("目标内容服务没有收到 ZIP 文件")
        val entries = archiveBytes.readZipEntries()
        assertEquals("backup.zip", creation.archiveName)
        assertEquals(6, creation.entryCount)
        assertEquals(3, progressEvents.last().writtenFileCount)
        assertEquals("todo.txt", progressEvents.last().currentFileName)
        assertTrue("project/" in entries)
        assertTrue("project/documents/" in entries)
        assertTrue("project/empty/" in entries)
        assertContentEquals("note".encodeToByteArray(), entries.getValue("project/note.txt"))
        assertContentEquals("guide".encodeToByteArray(), entries.getValue("project/documents/guide.txt"))
        assertContentEquals("todo".encodeToByteArray(), entries.getValue("todo.txt"))
    }

    /**
     * 校验名称校验会拒绝空名称和包含路径分隔符的非法名称。
     *
     * @return 无返回值。
     */
    @Test
    fun validatesZipArchiveNames() {
        assertEquals(ZipArchiveNameValidation.Empty, ZipArchiveCreationService.validateArchiveName("  "))
        assertEquals(ZipArchiveNameValidation.Invalid, ZipArchiveCreationService.validateArchiveName("backup/name"))
        assertEquals(
            ZipArchiveNameValidation.Valid("backup.zip"),
            ZipArchiveCreationService.validateArchiveName("backup"),
        )
    }

    /**
     * 校验目标位置没有可写内容服务时会明确失败，且不会产生归档写入。
     *
     * @return 无返回值。
     */
    @Test
    fun failsWhenTargetLocationHasNoContentService() = runBlocking {
        val sourceFile = file("$SOURCE_PREFIX/note.txt", "note.txt")
        val sourceContentService = FakeContentService(SOURCE_PREFIX).apply {
            seed(sourceFile.location, "note")
        }
        val service = ZipArchiveCreationService(
            fileRepository = FakeFileRepository(emptyMap()),
            contentServices = listOf(sourceContentService),
        )

        val result = service.create(
            ZipArchiveCreationRequest(
                entries = listOf(sourceFile),
                targetDirectoryLocation = "$TARGET_PREFIX/archives",
                archiveName = "backup.zip",
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("不支持写入压缩包") == true)
    }

    /**
     * 创建测试文件条目。
     *
     * @param location 文件 VFS 位置。
     * @param name 文件显示名称。
     * @return 测试文件条目。
     */
    private fun file(location: String, name: String): VFile {
        return VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/'),
            kind = VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }

    /**
     * 创建测试目录条目。
     *
     * @param location 目录 VFS 位置。
     * @param name 目录显示名称。
     * @return 测试目录条目。
     */
    private fun directory(location: String, name: String): VFile {
        return VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/'),
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN),
        )
    }

}

/** 测试源内容服务位置前缀。 */
private const val SOURCE_PREFIX = "source://"

/** 测试目标内容服务位置前缀。 */
private const val TARGET_PREFIX = "target://"

/**
 * 使用内存目录映射模拟 VFS 目录枚举的测试仓库。
 *
 * @param entriesByLocation 目录位置到直接子项的映射。
 */
private class FakeFileRepository(
    private val entriesByLocation: Map<String, List<VFile>>,
) : FileRepository {
    /**
     * 返回指定测试目录的直接子项。
     *
     * @param location 待读取目录位置。
     * @return 目录存在时返回子项列表，否则返回失败结果。
     */
    override suspend fun list(location: String): Result<List<VFile>> {
        return entriesByLocation[location]?.let { entries -> Result.success(entries) }
            ?: Result.failure(IllegalArgumentException("未配置测试目录: $location"))
    }

    /**
     * 返回测试默认位置。
     *
     * @return 固定测试目录位置。
     */
    override fun defaultLocation(): String = SOURCE_PREFIX
}

/**
 * 使用内存字节数组模拟可读写 VFS 内容服务。
 *
 * @param prefix 该服务支持的位置前缀。
 */
private class FakeContentService(
    private val prefix: String,
) : RoutableVfsContentService {
    /** 预置的源文件内容。 */
    private val sourceFiles = mutableMapOf<String, ByteArray>()

    /** 通过写入接口生成的目标归档内容。 */
    private val writtenFiles = mutableMapOf<String, ByteArray>()

    /**
     * 预置可读取的测试文件。
     *
     * @param location 文件 VFS 位置。
     * @param content 文件文本内容。
     * @return 无返回值。
     */
    fun seed(location: String, content: String) {
        sourceFiles[location] = content.encodeToByteArray()
    }

    /**
     * 读取已写入的测试归档。
     *
     * @param parentLocation 写入归档的父目录位置。
     * @param name 归档文件名称。
     * @return 已写入的归档字节；不存在时返回 `null`。
     */
    fun writtenFile(parentLocation: String, name: String): ByteArray? {
        return writtenFiles["${parentLocation.trimEnd('/')}/$name"]
    }

    /**
     * 判断测试内容服务是否处理指定位置。
     *
     * @param location 待判断 VFS 位置。
     * @return 位置以前缀开头时返回 `true`。
     */
    override fun supports(location: String): Boolean = location.startsWith(prefix)

    /**
     * 返回预置测试文件的字节流。
     *
     * @param entry 待读取文件条目。
     * @return 文件存在时返回内容源，否则返回失败结果。
     */
    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        val bytes = sourceFiles[entry.location]
            ?: return Result.failure(IllegalArgumentException("未配置测试文件: ${entry.location}"))
        return Result.success(
            VfsContentSource(
                name = entry.name,
                sizeBytes = bytes.size.toLong(),
                chunks = flowOf(bytes),
            )
        )
    }

    /**
     * 收集目标归档字节流并保存到内存。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 目标文件名称。
     * @param chunks 待写入字节流。
     * @param conflictStrategy 名称冲突策略；测试服务不需要区分处理。
     * @return 表示成功写入的目标文件条目。
     */
    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> {
        val output = ByteArrayOutputStream()
        chunks.collect { chunk -> output.write(chunk) }
        val location = "${parentLocation.trimEnd('/')}/$name"
        writtenFiles[location] = output.toByteArray()
        return Result.success(file(location, name))
    }

    /**
     * 创建写入成功后返回的测试文件条目。
     *
     * @param location 文件 VFS 位置。
     * @param name 文件显示名称。
     * @return 文件条目。
     */
    private fun file(location: String, name: String): VFile {
        return VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/'),
            kind = VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }
}

/**
 * 读取 ZIP 字节中的全部条目。
 *
 * @return ZIP 路径到对应文件内容的映射，目录内容固定为空数组。
 */
private fun ByteArray.readZipEntries(): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(this)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            entries[entry.name] = if (entry.isDirectory) ByteArray(0) else input.readBytes()
            input.closeEntry()
        }
    }
    return entries
}
