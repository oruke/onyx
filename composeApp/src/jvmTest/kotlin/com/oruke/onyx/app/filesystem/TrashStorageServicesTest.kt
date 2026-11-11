package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.local.LocalPathDeletion
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 跨平台回收站底层存储服务单元测试。
 *
 * 覆盖 Windows $I v1/v2 二进制元数据解析、FreeDesktop RFC 2396 规范解码、
 * macOS 系统文件过滤与会话原始路径跟踪、自动重建父目录还原与同名冲突防护、以及不穿透符号链接的安全永久删除。
 */
class TrashStorageServicesTest {

    /**
     * 验证 Windows 回收站版本 1 元数据解析：正确剥离长路径前缀、配对 $R 内容文件，并忽略无配对内容的孤立 $I 文件。
     */
    @Test
    fun windowsRecycleBinV1ParsingAndOrphanFiltering() {
        val tempRoot = Files.createTempDirectory("onyx-test-win-v1-")
        try {
            val sidDir = tempRoot.resolve("\$Recycle.Bin").resolve("S-1-5-21-1001")
            Files.createDirectories(sidDir)

            val originalPath = "\\\\?\\C:\\Users\\OnyxUser\\Documents\\report.docx"
            val infoPath = sidDir.resolve("\$IAB1234.docx")
            val contentPath = sidDir.resolve("\$RAB1234.docx")
            val orphanInfoPath = sidDir.resolve("\$IORPHAN.txt")

            Files.write(infoPath, buildWindowsInfoV1(originalPath, 2048L, 133500000000000000L))
            Files.writeString(contentPath, "fake docx content")
            Files.write(orphanInfoPath, buildWindowsInfoV1("C:\\orphan.txt", 100L, 133500000000000000L))

            val storage = WindowsRecycleBinStorageService(
                rootsProvider = { listOf(tempRoot) },
                currentUserOwnerProvider = { null },
            )
            val entries = storage.listEntries()

            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals("report.docx", entry.item.displayName)
            assertEquals("C:\\Users\\OnyxUser\\Documents\\report.docx", entry.item.originalLocation)
            assertEquals("C:\\Users\\OnyxUser\\Documents", entry.item.originalParentLocation)
            assertEquals(2048L, entry.item.sizeBytes)
            assertEquals(contentPath.toAbsolutePath(), entry.contentPath.toAbsolutePath())
            assertEquals(infoPath.toAbsolutePath(), entry.metadataPath?.toAbsolutePath())
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    /**
     * 验证 Windows 回收站版本 2 元数据解析：正确读取变长字符数量并规范化 UNC 共享扩展前缀。
     */
    @Test
    fun windowsRecycleBinV2ParsingAndUncPrefixStripping() {
        val tempRoot = Files.createTempDirectory("onyx-test-win-v2-")
        try {
            val sidDir = tempRoot.resolve("\$Recycle.Bin").resolve("S-1-5-21-2002")
            Files.createDirectories(sidDir)

            val originalUnc = "\\\\?\\UNC\\fileserver\\shared\\photo.png"
            val infoPath = sidDir.resolve("\$IV2FILE.png")
            val contentPath = sidDir.resolve("\$RV2FILE.png")

            Files.write(infoPath, buildWindowsInfoV2(originalUnc, 8192L, 133600000000000000L))
            Files.writeString(contentPath, "fake image bytes")

            val storage = WindowsRecycleBinStorageService(
                rootsProvider = { listOf(tempRoot) },
                currentUserOwnerProvider = { null },
            )
            val entries = storage.listEntries()

            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals("photo.png", entry.item.displayName)
            assertEquals("\\\\fileserver\\shared\\photo.png", entry.item.originalLocation)
            assertEquals(8192L, entry.item.sizeBytes)
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    /**
     * 验证 FreeDesktop .trashinfo RFC 2396 百分号 UTF-8 解码：保留字面量加号并严格拒绝包含点点的路径逃逸。
     */
    @Test
    fun freedesktopRfc2396DecodingAndDirectoryTraversalRejection() {
        val tempTopDir = Files.createTempDirectory("onyx-test-xdg-top-").normalize().toAbsolutePath()
        try {
            val validEncoded = "/home/onyx/my+c%2B%2B%20project/%E6%96%87%E6%A1%A3.txt"
            assertEquals(
                "/home/onyx/my+c++ project/文档.txt",
                FreedesktopTrashStorageService.decodeRfc2396Path(validEncoded),
            )

            val relativeEncoded = "projects/my+c%2B%2B%20project/%E6%96%87%E6%A1%A3.txt"
            val resolvedRelative = FreedesktopTrashStorageService.resolveOriginalPath(relativeEncoded, tempTopDir)
            assertEquals(
                tempTopDir.resolve("projects").resolve("my+c++ project").resolve("文档.txt").normalize(),
                resolvedRelative,
            )

            val traversalEncoded = "/home/onyx/../../etc/shadow"
            val resolvedTraversal = FreedesktopTrashStorageService.resolveOriginalPath(traversalEncoded, tempTopDir)
            assertNull(resolvedTraversal)

            val relativeTraversal = "a/b/../../../etc/passwd"
            val resolvedRelativeTraversal = FreedesktopTrashStorageService.resolveOriginalPath(relativeTraversal, tempTopDir)
            assertNull(resolvedRelativeTraversal)

            val trashRoot = tempTopDir.resolve("Trash")
            val filesDir = trashRoot.resolve("files")
            val infoDir = trashRoot.resolve("info")
            Files.createDirectories(filesDir)
            Files.createDirectories(infoDir)

            val sampleContent = filesDir.resolve("hello+world.txt")
            val sampleInfo = infoDir.resolve("hello+world.txt.trashinfo")
            Files.writeString(sampleContent, "content")
            Files.writeString(
                sampleInfo,
                """
                [Trash Info]
                Path=docs/hello+world%20%E6%96%87%E6%A1%A3.txt
                DeletionDate=2026-11-11T12:00:00
                """.trimIndent(),
            )

            val service = FreedesktopTrashStorageService { trashRoot }
            val entries = service.listEntries()
            val expectedOriginal = tempTopDir.resolve("docs").resolve("hello+world 文档.txt").normalize()
            assertEquals(1, entries.size)
            assertEquals("hello+world 文档.txt", entries.single().item.displayName)
            assertEquals(expectedOriginal.toString(), entries.single().item.originalLocation)
        } finally {
            deleteRecursively(tempTopDir)
        }
    }

    /**
     * 验证 macOS 回收站忽略 .DS_Store 与 .localized 且能正确绑定本会话移入条目的原始路径。
     */
    @Test
    fun macOsTrashIgnoresSystemMetadataAndTracksSessionOrigin() {
        val tempDir = Files.createTempDirectory("onyx-test-macos-trash-").normalize().toAbsolutePath()
        try {
            val tempTrash = Files.createDirectories(tempDir.resolve("Trash"))
            Files.writeString(tempTrash.resolve(".DS_Store"), "ds-store")
            Files.writeString(tempTrash.resolve(".localized"), "")
            val contentFile = tempTrash.resolve("presentation.key")
            Files.writeString(contentFile, "keynote")

            val service = MacOsTrashStorageService { tempTrash }
            val originalPath = tempDir.resolve("Documents").resolve("presentation.key").normalize().toAbsolutePath()
            service.recordMovedEntry(contentFile, originalPath.toString(), 1700000000000L)

            val entries = service.listEntries()
            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals("presentation.key", entry.item.displayName)
            assertEquals(originalPath.toString(), entry.item.originalLocation)
            assertEquals(originalPath.parent.toString(), entry.item.originalParentLocation)
            assertEquals(1700000000000L, entry.item.deletedAtEpochMillis)

            entry.onRemoved?.invoke()
            val afterRemovalEntries = service.listEntries()
            assertEquals(1, afterRemovalEntries.size)
            assertNull(afterRemovalEntries.single().item.originalLocation)
        } finally {
            deleteRecursively(tempDir)
        }
    }

    /**
     * 验证通过 JvmDesktopTrashService 还原条目时能自动重建缺失的原父目录，且在遇到同名目标时抛出 AlreadyExists。
     */
    @Test
    fun restoreRebuildsMissingParentDirectoryAndGuardsAgainstCollision(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("onyx-test-restore-").normalize().toAbsolutePath()
        try {
            val trashDir = tempDir.resolve("trash-store")
            val originalBase = tempDir.resolve("original-base")
            Files.createDirectories(trashDir)

            val trashedFile = trashDir.resolve("restored.txt")
            val trashedMeta = trashDir.resolve("restored.txt.trashinfo")
            Files.writeString(trashedFile, "payload to restore")
            Files.writeString(trashedMeta, "metadata")

            val deeplyNestedTarget = originalBase.resolve("deep").resolve("subfolder").resolve("restored.txt")
            val entryId = encodeTrashEntryId(trashedFile)
            val entry = TrashStorageEntry(
                item = TrashItem(
                    id = entryId,
                    displayName = "restored.txt",
                    originalLocation = deeplyNestedTarget.toString(),
                    originalParentLocation = deeplyNestedTarget.parent.toString(),
                    kind = VFileKind.FILE,
                    sizeBytes = 18L,
                    deletedAtEpochMillis = 1000L,
                ),
                contentPath = trashedFile,
                metadataPath = trashedMeta,
            )

            val mockStorage = object : PlatformTrashStorageService {
                override fun listEntries(): List<TrashStorageEntry> = listOf(entry)
            }
            val trashService = JvmDesktopTrashService(storageOverride = mockStorage)

            assertFalse(Files.exists(deeplyNestedTarget.parent))
            val outcome = trashService.restoreItems(setOf(entryId)).getOrThrow()

            assertTrue(Files.exists(deeplyNestedTarget))
            assertEquals("payload to restore", Files.readString(deeplyNestedTarget))
            assertFalse(Files.exists(trashedFile))
            assertFalse(Files.exists(trashedMeta))
            assertEquals(setOf(deeplyNestedTarget.parent.toString()), outcome.affectedParentLocations)

            Files.writeString(trashedFile, "another payload")
            val collisionResult = trashService.restoreItems(setOf(entryId))
            assertTrue(collisionResult.isFailure)
            val exception = collisionResult.exceptionOrNull()
            assertIs<VfsProviderException>(exception)
            assertIs<VfsProviderError.AlreadyExists>(exception.error)
            Unit
        } finally {
            deleteRecursively(tempDir)
        }
    }

    /**
     * 验证永久删除与清空回收站正常清除内容与元数据，且删除符号链接或目录联接时不穿透破坏外部原始文件。
     */
    @Test
    fun permanentDeleteAndEmptyTrashDoNotFollowSymlinks(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("onyx-test-perm-del-").normalize().toAbsolutePath()
        try {
            val externalDir = tempDir.resolve("external")
            val trashDir = tempDir.resolve("trash-box")
            Files.createDirectories(externalDir)
            Files.createDirectories(trashDir)

            val outsideTarget = externalDir.resolve("keep-safe.txt")
            Files.writeString(outsideTarget, "do not delete me!")

            val contentLink = trashDir.resolve("link-item")
            val canCreateLink = runCatching {
                Files.createSymbolicLink(contentLink, externalDir)
            }.isSuccess || createWindowsJunctionIfSupported(contentLink, externalDir)

            val metadataPath = trashDir.resolve("link-item.trashinfo")
            Files.writeString(metadataPath, "meta-link")

            val linkEntry = TrashStorageEntry(
                item = TrashItem(
                    id = encodeTrashEntryId(contentLink),
                    displayName = "link-item",
                    originalLocation = externalDir.toString(),
                    originalParentLocation = tempDir.toString(),
                    kind = VFileKind.DIRECTORY,
                    sizeBytes = null,
                    deletedAtEpochMillis = 2000L,
                ),
                contentPath = contentLink,
                metadataPath = metadataPath,
            )

            val normalContent = trashDir.resolve("normal.txt")
            val normalMeta = trashDir.resolve("normal.txt.trashinfo")
            Files.writeString(normalContent, "normal content")
            Files.writeString(normalMeta, "normal meta")
            val normalEntry = TrashStorageEntry(
                item = TrashItem(
                    id = encodeTrashEntryId(normalContent),
                    displayName = "normal.txt",
                    originalLocation = tempDir.resolve("normal.txt").toString(),
                    originalParentLocation = tempDir.toString(),
                    kind = VFileKind.FILE,
                    sizeBytes = 14L,
                    deletedAtEpochMillis = 2001L,
                ),
                contentPath = normalContent,
                metadataPath = normalMeta,
            )

            var storageEntries = if (canCreateLink) listOf(linkEntry, normalEntry) else listOf(normalEntry)
            val mockStorage = object : PlatformTrashStorageService {
                override fun listEntries(): List<TrashStorageEntry> = storageEntries
            }
            val trashService = JvmDesktopTrashService(storageOverride = mockStorage)

            if (canCreateLink) {
                val deleted = trashService.deletePermanently(setOf(linkEntry.item.id)).getOrThrow()
                assertEquals(1, deleted)
                assertFalse(Files.exists(contentLink))
                assertFalse(Files.exists(metadataPath))
                assertTrue(Files.exists(outsideTarget), "符号链接或联接永久删除严禁穿透删除外部真实目标！")
                assertEquals("do not delete me!", Files.readString(outsideTarget))
                storageEntries = listOf(normalEntry)
            }

            val emptied = trashService.emptyTrash().getOrThrow()
            assertEquals(storageEntries.size, emptied)
            assertFalse(Files.exists(normalContent))
            assertFalse(Files.exists(normalMeta))
            assertTrue(Files.exists(outsideTarget))
        } finally {
            deleteRecursively(tempDir)
        }
    }

    private fun createWindowsJunctionIfSupported(linkPath: Path, targetDir: Path): Boolean {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return false
        return runCatching {
            ProcessBuilder("cmd", "/c", "mklink", "/J", linkPath.toString(), targetDir.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0 && Files.exists(linkPath)
        }.getOrDefault(false)
    }

    private fun buildWindowsInfoV1(
        originalPath: String,
        sizeBytes: Long,
        fileTime: Long,
    ): ByteArray {
        val pathBytes = (originalPath + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
        val buffer = ByteBuffer.allocate(24 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(1L)
        buffer.putLong(sizeBytes)
        buffer.putLong(fileTime)
        buffer.put(pathBytes)
        return buffer.array()
    }

    private fun buildWindowsInfoV2(
        originalPath: String,
        sizeBytes: Long,
        fileTime: Long,
    ): ByteArray {
        val pathWithNull = originalPath + "\u0000"
        val pathBytes = pathWithNull.toByteArray(StandardCharsets.UTF_16LE)
        val buffer = ByteBuffer.allocate(28 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(2L)
        buffer.putLong(sizeBytes)
        buffer.putLong(fileTime)
        buffer.putInt(pathWithNull.length)
        buffer.put(pathBytes)
        return buffer.array()
    }

    private fun deleteRecursively(path: Path) {
        runCatching { LocalPathDeletion.delete(path) }
    }
}
