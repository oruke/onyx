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
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
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
                currentUserSidProvider = { "S-1-5-21-1001" },
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
                currentUserSidProvider = { "S-1-5-21-2002" },
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
     * 验证按当前用户 SID 直达读取回收站，严格隔离其他用户 SID 目录，并兼容顶层 FAT32/exFAT 结构的 $I 文件。
     */
    @Test
    fun windowsRecycleBinUserSidIsolationAndTopLevelFatFiles() {
        val tempRoot = Files.createTempDirectory("onyx-test-win-sid-isolation-")
        try {
            val recycleBin = tempRoot.resolve("\$Recycle.Bin")
            val userSidDir = recycleBin.resolve("S-1-5-21-1001")
            val otherSidDir = recycleBin.resolve("S-1-5-21-9999")
            Files.createDirectories(userSidDir)
            Files.createDirectories(otherSidDir)

            val userOriginal = "C:\\Users\\User1\\doc.txt"
            val userInfo = userSidDir.resolve("\$IUSER1.txt")
            val userContent = userSidDir.resolve("\$RUSER1.txt")
            Files.write(userInfo, buildWindowsInfoV1(userOriginal, 1024L, 133500000000000000L))
            Files.writeString(userContent, "user1 doc")

            val otherOriginal = "C:\\Users\\Other\\secret.txt"
            val otherInfo = otherSidDir.resolve("\$IOTHER.txt")
            val otherContent = otherSidDir.resolve("\$ROTHER.txt")
            Files.write(otherInfo, buildWindowsInfoV1(otherOriginal, 2048L, 133500000000000000L))
            Files.writeString(otherContent, "other secret")

            val fatOriginal = "E:\\fat-file.txt"
            val fatInfo = recycleBin.resolve("\$IFAT01.txt")
            val fatContent = recycleBin.resolve("\$RFAT01.txt")
            Files.write(fatInfo, buildWindowsInfoV1(fatOriginal, 512L, 133500000000000000L))
            Files.writeString(fatContent, "fat payload")

            val storage = WindowsRecycleBinStorageService(
                rootsProvider = { listOf(tempRoot) },
                currentUserSidProvider = { "S-1-5-21-1001" },
            )
            val entries = storage.listEntries()

            assertEquals(2, entries.size, "应仅包含当前用户 SID 目录与根目录顶层 FAT 文件")
            val displayNames = entries.map { it.item.displayName }.toSet()
            assertTrue(displayNames.contains("doc.txt"))
            assertTrue(displayNames.contains("fat-file.txt"))
            assertFalse(displayNames.contains("secret.txt"), "严禁泄漏其他用户的 SID 回收站文件")
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    /**
     * 验证 Version 1 固定缓冲区在空终止符后包含非零脏字节时，仍能正确截断原路径。
     */
    @Test
    fun windowsRecycleBinV1DirtyBytesAfterNullTerminator() {
        val tempRoot = Files.createTempDirectory("onyx-test-win-v1-dirty-")
        try {
            val sidDir = tempRoot.resolve("\$Recycle.Bin").resolve("S-1-5-21-1001")
            Files.createDirectories(sidDir)

            val originalPath = "C:\\Work\\CleanPath.txt"
            val infoPath = sidDir.resolve("\$IDIRTY.txt")
            val contentPath = sidDir.resolve("\$RDIRTY.txt")

            val cleanPathBytes = (originalPath + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
            val dirtyBytes = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x55.toByte(), 0x12.toByte(), 0x34.toByte())
            val buffer = ByteBuffer.allocate(24 + cleanPathBytes.size + dirtyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putLong(1L)
            buffer.putLong(100L)
            buffer.putLong(133500000000000000L)
            buffer.put(cleanPathBytes)
            buffer.put(dirtyBytes)
            Files.write(infoPath, buffer.array())
            Files.writeString(contentPath, "clean content")

            val record = WindowsRecycleBinMetadataService.parseInfo(infoPath)
            assertTrue(record != null, "元数据解析必须成功")
            assertEquals(originalPath, record.originalLocation, "路径必须在空字符处截断，不得包含脏字节")
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    /**
     * 验证 WindowsRecycleBinMetadataService.findRecord 能按指定 SID 准确命中记录，并在 SID 不匹配时返回 null。
     */
    @Test
    fun windowsRecycleBinFindRecordMatchesBySid() {
        val tempRoot = Files.createTempDirectory("onyx-test-win-find-record-")
        try {
            val recycleBin = tempRoot.resolve("\$Recycle.Bin")
            val targetSidDir = recycleBin.resolve("S-1-5-21-3003")
            Files.createDirectories(targetSidDir)

            val originalPath = tempRoot.resolve("deleted-file.txt")
            val movedAtMillis = System.currentTimeMillis()
            val infoPath = targetSidDir.resolve("\$IFIND01.txt")
            val contentPath = targetSidDir.resolve("\$RFIND01.txt")

            Files.write(
                infoPath,
                buildWindowsInfoV2(
                    originalPath.normalize().toAbsolutePath().toString(),
                    4096L,
                    (movedAtMillis + 11_644_473_600_000L) * 10_000L,
                ),
            )
            Files.writeString(contentPath, "found content")

            val matched = WindowsRecycleBinMetadataService.findRecord(
                originalPath = originalPath,
                movedAtMillis = movedAtMillis,
                currentUserSid = "S-1-5-21-3003",
                recycleRoot = recycleBin,
            )
            assertTrue(matched != null, "指定对应 SID 时应命中回收站记录")
            assertEquals(contentPath.toAbsolutePath(), matched.contentPath.toAbsolutePath())
            assertEquals(infoPath.toAbsolutePath(), matched.infoPath.toAbsolutePath())

            val mismatched = WindowsRecycleBinMetadataService.findRecord(
                originalPath = originalPath,
                movedAtMillis = movedAtMillis,
                currentUserSid = "S-1-5-21-9999",
                recycleRoot = recycleBin,
            )
            assertNull(mismatched, "SID 不匹配时不应命中记录")
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    /**
     * Windows 宿主只读健康检查：验证用户 SID 解析正确，并对真实回收站进行只读枚举诊断。
     */
    @Test
    fun windowsHostLiveRecycleBinReadOnlyHealthCheck() {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (!isWindows) return

        val sid = WindowsProcessUserSidResolver.currentUserSid
        assertTrue(sid != null && sid.startsWith("S-1-"), "Windows 宿主应解析出合法的用户 SID (S-1-...)，当前为: $sid")

        val userHome = System.getProperty("user.home").orEmpty()
        val userHomeOwner = runCatching {
            Files.getOwner(Path.of(userHome), LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()

        println("=== Windows Host Recycle Bin Diagnostic ===")
        println("Resolved Current User SID: $sid")
        println("user.home Path: $userHome")
        println("user.home Owner Principal: $userHomeOwner")

        val roots = FileSystems.getDefault().rootDirectories.toList()
        for (root in roots) {
            val localOrRemovable = WindowsProcessUserSidResolver.isLocalOrRemovableSystemRoot(root)
            val readable = Files.isReadable(root)
            println("Drive Root: $root | isLocalOrRemovable=$localOrRemovable | readable=$readable")
        }

        val storage = WindowsRecycleBinStorageService()
        val entries = storage.listEntries()
        println("Discovered Live Recycle Bin Entries Count: ${entries.size}")
        for (entry in entries.take(10)) {
            println("  - [${entry.item.displayName}] original: ${entry.item.originalLocation} size: ${entry.item.sizeBytes}")
        }
        println("===========================================")

        for (entry in entries) {
            assertTrue(entry.item.id.isNotBlank(), "条目 ID 不得为空")
            assertTrue(entry.item.displayName.isNotBlank(), "条目显示名称不得为空")
            assertTrue(
                Files.exists(entry.contentPath, LinkOption.NOFOLLOW_LINKS),
                "发现的回收站条目 contentPath 必须真实存在: ${entry.contentPath}",
            )
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
