package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 通过真实本地 Provider 验证断开和在线 Junction 的列项、重命名、删除及冲突处理。 */
class LocalJunctionFlowTest {
    /** 本组验证依赖 Windows 的真实目录联接机制，其他平台使用各自的文件层测试。 */
    @Before
    fun requireWindowsJunctionSupport() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
    }

    /** 断开的联接应与普通文件一起列出，并可重命名和删除入口而不报告假成功或假失败。 */
    @Test
    fun offlineJunctionCanBeListedRenamedAndDeleted() = runBlocking {
        WindowsJunctionFixture().use { fixture ->
            val provider = JvmLocalFileProvider()
            val directory = fixture.directory("listing")
            Files.writeString(directory.resolve("ordinary.txt"), "keep")
            val link = fixture.junction("listing/offline", fixture.testPath("missing-target"))
            assertFalse(Files.exists(link))
            val entries = provider.list(directory.toString()).getOrThrow()
            assertEquals(setOf("ordinary.txt", "offline"), entries.map { it.name }.toSet())
            val entry = entries.single { it.name == "offline" }
            assertEquals(VFileKind.DIRECTORY, entry.kind)
            assertNotNull(entry.modifiedAtEpochMillis)
            assertFalse(VFileCapability.LIST_CHILDREN in entry.capabilities)
            assertTrue(VFileCapability.RENAME in entry.capabilities)
            assertTrue(VFileCapability.DELETE in entry.capabilities)
            assertFalse(VFileCapability.READ_CONTENT in entry.capabilities)
            val openFailure = assertIs<VfsProviderException>(provider.list(link.toString()).exceptionOrNull())
            assertIs<VfsProviderError.NotFound>(openFailure.error)

            val renamed = provider.rename(entry, "renamed-offline").getOrThrow()
            assertEquals(VFileKind.DIRECTORY, renamed.kind)
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(directory.resolve(renamed.name), LinkOption.NOFOLLOW_LINKS))
            provider.delete(listOf(renamed)).getOrThrow()
            assertFalse(Files.exists(directory.resolve(renamed.name), LinkOption.NOFOLLOW_LINKS))
            assertEquals(listOf("ordinary.txt"), provider.list(directory.toString()).getOrThrow().map { it.name })
            assertEquals("keep", Files.readString(directory.resolve("ordinary.txt")))
        }
    }

    /** 在线联接仍可进入；重命名和删除入口之后，其目标目录和内容必须保持完整。 */
    @Test
    fun onlineJunctionRemainsBrowsableAndDeletingItPreservesTarget() = runBlocking {
        WindowsJunctionFixture().use { fixture ->
            val provider = JvmLocalFileProvider()
            val target = fixture.directory("target/nested")
            Files.writeString(target.resolve("protected.txt"), "target-content")
            val parent = fixture.directory("listing")
            val link = fixture.junction("listing/online", target.parent)
            val entry = provider.list(parent.toString()).getOrThrow().single()
            assertTrue(VFileCapability.LIST_CHILDREN in entry.capabilities)
            assertEquals(listOf("nested"), provider.list(link.toString()).getOrThrow().map { it.name })
            val renamed = provider.rename(entry, "renamed-online").getOrThrow()
            assertTrue(VFileCapability.LIST_CHILDREN in renamed.capabilities)
            provider.delete(listOf(renamed)).getOrThrow()
            assertTrue(provider.list(parent.toString()).getOrThrow().isEmpty())
            assertEquals("target-content", Files.readString(target.resolve("protected.txt")))
        }
    }

    /** 删除普通目录树时，任何层级的在线、断开和循环联接都必须作为叶子处理。 */
    @Test
    fun recursiveDeletionStopsAtNestedJunctions() = runBlocking {
        WindowsJunctionFixture().use { fixture ->
            val provider = JvmLocalFileProvider()
            val target = fixture.directory("outside-target")
            Files.writeString(target.resolve("protected.txt"), "target-content")
            val tree = fixture.directory("to-delete/nested")
            Files.writeString(tree.resolve("ordinary.txt"), "remove")
            fixture.junction("to-delete/nested/online", target)
            fixture.junction("to-delete/nested/offline", fixture.testPath("missing-target"))
            fixture.junction("to-delete/nested/cycle", tree.parent)
            val entry = provider.list(fixture.root.toString()).getOrThrow().single { it.name == "to-delete" }
            provider.delete(listOf(entry)).getOrThrow()
            assertFalse(Files.exists(tree.parent, LinkOption.NOFOLLOW_LINKS))
            assertEquals("target-content", Files.readString(target.resolve("protected.txt")))
        }
    }

    /** 同名断开联接仍构成冲突，保留两者或覆盖入口时不得误操作目标内容。 */
    @Test
    fun brokenJunctionParticipatesInRenameCreateAndCopyConflicts() = runBlocking {
        WindowsJunctionFixture().use { fixture ->
            val provider = JvmLocalFileProvider()
            val sourceDirectory = fixture.directory("source")
            val destination = fixture.directory("destination")
            Files.writeString(sourceDirectory.resolve("entry.txt"), "new-content")
            Files.writeString(destination.resolve("ordinary.txt"), "ordinary-content")
            fixture.junction("destination/entry.txt", fixture.testPath("missing-target"))
            val ordinary = provider.list(destination.toString()).getOrThrow().single { it.name == "ordinary.txt" }
            assertAlreadyExists(provider.rename(ordinary, "entry.txt"))
            assertAlreadyExists(provider.createFile(destination.toString(), "entry.txt"))
            assertAlreadyExists(provider.createDirectory(destination.toString(), "entry.txt"))
            val source = provider.list(sourceDirectory.toString()).getOrThrow().single()
            provider.copyWithProgress(
                listOf(source), destination.toString(), TransferConflictStrategy.KEEP_BOTH, FileTransferProgressSink.NoOp,
            ).getOrThrow()
            assertTrue(Files.exists(destination.resolve("entry.txt"), LinkOption.NOFOLLOW_LINKS))
            assertEquals("new-content", Files.readString(destination.resolve("entry copy.txt")))
            provider.copyWithProgress(
                listOf(source), destination.toString(), TransferConflictStrategy.OVERWRITE, FileTransferProgressSink.NoOp,
            ).getOrThrow()
            assertEquals("new-content", Files.readString(destination.resolve("entry.txt")))
            assertEquals("ordinary-content", Files.readString(destination.resolve("ordinary.txt")))
        }
    }
}

/**
 * 从生产命令结果检查是否保留真实的目标冲突错误。
 *
 * @param result 已通过本地 Provider 执行的创建或重命名结果。
 */
private fun assertAlreadyExists(result: Result<VFile>) {
    val failure = assertIs<VfsProviderException>(result.exceptionOrNull())
    assertIs<VfsProviderError.AlreadyExists>(failure.error)
}
