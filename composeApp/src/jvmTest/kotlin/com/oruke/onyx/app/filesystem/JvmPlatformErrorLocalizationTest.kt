package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.archive.ArchiveService
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** 使用真实临时目录和归档，验证平台校验失败经过统一映射后仍保留可本地化消息。 */
class JvmPlatformErrorLocalizationTest {
    /** 每项场景独立创建并回收的测试文件，避免访问用户目录。 */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 终端入口拒绝实际普通文件，且不会进入终端启动流程。
     *
     * @return 无返回值。
     */
    @Test
    fun terminalRejectsFileWithLocalizedDirectoryRequirement() = runBlocking {
        val file = temporaryFolder.newFile("document.txt").toPath()

        val failure = assertNotNull(JvmTerminalLauncherService().openTerminal(file.toString()).exceptionOrNull())

        assertIs<LocalizedException>(failure)
        assertEquals(
            I18nMessage(MessageKey.MSG_TERMINAL_LOCATION_NOT_DIRECTORY, file.toString()),
            failure.toI18nMessage(),
        )
    }

    /**
     * 真实 ZIP 中不存在或为空的条目，以及非法入口位置，都返回各自消息且不启动外部程序。
     *
     * @return 无返回值。
     */
    @Test
    fun archiveEntryFailuresReturnLocalizedMessagesBeforeExternalOpen() = runBlocking {
        val archive = temporaryFolder.newFile("sample.zip").toPath()
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            output.putNextEntry(ZipEntry("empty.txt"))
            output.closeEntry()
        }
        var externalOpenCount = 0
        val service = JvmArchiveEntryOpenService(
            archiveService = ArchiveService(),
            externalOpenService = object : ExternalOpenService {
                /**
                 * 记录统一平台边界是否被意外调用，不实际启动外部程序。
                 *
                 * @param entry 只有成功提取后才允许抵达此边界的本地条目。
                 * @return 确定性的成功结果。
                 */
                override suspend fun open(entry: VFile): Result<Unit> {
                    externalOpenCount += 1
                    return Result.success(Unit)
                }
            },
        )
        val expectations = mapOf(
            archive.toString() to I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_LOCATION_INVALID, archive.toString()),
            ArchiveService.archiveLocation(archive.toString()) to I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_PATH_EMPTY),
            ArchiveService.archiveLocation(archive.toString(), "missing.txt") to
                I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_NOT_FOUND, "missing.txt"),
            ArchiveService.archiveLocation(archive.toString(), "empty.txt") to
                I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_EMPTY, "empty.txt"),
        )

        expectations.forEach { (location, expectedMessage) ->
            val failure = assertNotNull(service.openArchiveEntry(platformErrorEntry(location)).exceptionOrNull())
            assertIs<LocalizedException>(failure)
            assertEquals(expectedMessage, failure.toI18nMessage())
        }
        assertEquals(0, externalOpenCount)
    }
}

/**
 * 构造仅使用当前测试临时位置的文件条目，驱动真实归档打开入口。
 *
 * @param location 本次场景的本地或归档条目位置。
 * @return 包含错误入口位置的统一文件对象。
 */
private fun platformErrorEntry(location: String): VFile = VFile(
    id = location,
    name = "document.txt",
    location = location,
    parentLocation = null,
    kind = VFileKind.FILE,
    sizeBytes = null,
    modifiedAtEpochMillis = null,
    hidden = false,
    capabilities = emptySet(),
)
