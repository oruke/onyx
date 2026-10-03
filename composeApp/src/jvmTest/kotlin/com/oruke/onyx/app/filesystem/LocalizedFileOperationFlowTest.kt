package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.shared.filesystem.FileCollectionVfsProvider
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchEvent
import com.oruke.onyx.shared.usecase.FileSearchRequest
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 使用真实本地文件和集合仓储验证业务错误能够保持可本地化语义。 */
class LocalizedFileOperationFlowTest {
    /** 未注册协议的目录请求应保留原位置，并在统一反馈入口生成可本地化消息。 */
    @Test
    fun unsupportedLocationReportsLocalizedFailureFromRegistry() = runBlocking {
        val registry = VfsProviderRegistry(listOf(LocalVfsProvider(JvmLocalFileProvider())))
        val location = "unsupported://storage.example.invalid/documents"
        val failure = requireNotNull(registry.list(location).exceptionOrNull())
        assertEquals(I18nMessage(MessageKey.MSG_VFS_LOCATION_UNSUPPORTED, location), failure.toI18nMessage())
    }

    /** 文件校验和危险目标拒绝应返回业务消息，同时保持原文件内容及目录结构。 */
    @Test
    fun invalidLocalOperationsPreserveFilesAndReportLocalizedMessages() = runBlocking {
        val root = Files.createTempDirectory("onyx-localized-operations-")
        try {
            val provider = JvmLocalFileProvider()
            val source = Files.writeString(root.resolve("payload.txt"), "payload")
            val entry = provider.list(root.toString()).getOrThrow().single()

            val directoryFailure = provider.createDirectory(root.toString(), "../outside").exceptionOrNull()
            assertEquals(
                I18nMessage(MessageKey.MSG_DIRECTORY_PATH_INVALID_SEGMENTS),
                requireNotNull(directoryFailure).toI18nMessage(),
            )
            val renameFailure = provider.rename(entry, "nested/name.txt").exceptionOrNull()
            assertEquals(
                I18nMessage(MessageKey.MSG_FILE_NAME_PATH_SEPARATOR),
                requireNotNull(renameFailure).toI18nMessage(),
            )
            val copyFailure = provider.copyWithProgress(
                entries = listOf(entry),
                targetDirectoryLocation = root.toString(),
                conflictStrategy = TransferConflictStrategy.OVERWRITE,
                progressSink = FileTransferProgressSink.NoOp,
            ).exceptionOrNull()
            assertEquals(
                I18nMessage(MessageKey.MSG_TRANSFER_SAME_PATH),
                requireNotNull(copyFailure).toI18nMessage(),
            )
            assertEquals("payload", Files.readString(source))
            assertEquals(listOf("payload.txt"), provider.list(root.toString()).getOrThrow().map { it.name })
        } finally {
            deleteTestDirectory(root)
        }
    }

    /** 不支持内容读取的真实搜索入口应返回本地化失败，随后名称搜索仍能完成。 */
    @Test
    fun unsupportedContentSearchReportsLocalizedFailureAndNameSearchStillWorks() = runBlocking {
        val root = Files.createTempDirectory("onyx-localized-search-")
        try {
            Files.writeString(root.resolve("payload.txt"), "payload")
            val useCase = FileSearchUseCase(JvmLocalFileProvider())

            val contentEvents = useCase.search(FileSearchRequest(root.toString(), "content:payload")).toList()
            val failure = assertIs<FileSearchEvent.Failed>(contentEvents.single())
            assertEquals(0, failure.scannedEntryCount)
            assertEquals(
                I18nMessage(MessageKey.MSG_SEARCH_CONTENT_UNSUPPORTED, root.toString()),
                failure.failure.toI18nMessage(),
            )

            val nameEvents = useCase.search(FileSearchRequest(root.toString(), "payload")).toList()
            assertIs<FileSearchEvent.Completed>(nameEvents.last())
            assertTrue(nameEvents.filterIsInstance<FileSearchEvent.Results>()
                .any { event -> event.entries.any { it.name == "payload.txt" } })
        } finally {
            deleteTestDirectory(root)
        }
    }

    /** 真实集合保存和读取应保留文件，失效的标签位置由两个读取入口报告同一结构化错误。 */
    @Test
    fun expiredCollectionReportsLocalizedNotFoundFromBothReadEntrypoints() = runBlocking {
        val root = Files.createTempDirectory("onyx-localized-collection-")
        try {
            Files.writeString(root.resolve("payload.txt"), "payload")
            val entries = JvmLocalFileProvider().list(root.toString()).getOrThrow()
            val repository = InMemoryFileCollectionRepository()
            val useCase = FileCollectionUseCase(repository)
            val location = useCase.saveSearchResults("saved-results", "payload", entries).location
            assertEquals(entries, FileCollectionVfsProvider(repository).list(location).getOrThrow())

            val expiredRepository = InMemoryFileCollectionRepository()
            val expected = I18nMessage(MessageKey.MSG_VFS_NOT_FOUND, VfsProtocol.COLLECTION, location)
            assertEquals(expected, requireNotNull(
                FileCollectionUseCase(expiredRepository).list(location).exceptionOrNull(),
            ).toI18nMessage())
            assertEquals(expected, requireNotNull(
                FileCollectionVfsProvider(expiredRepository).list(location).exceptionOrNull(),
            ).toI18nMessage())
            assertEquals("payload", Files.readString(root.resolve("payload.txt")))
        } finally {
            deleteTestDirectory(root)
        }
    }

    /**
     * 清理当前测试自行创建的临时目录。
     *
     * @param directory 本次测试的临时根目录。
     */
    private fun deleteTestDirectory(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
