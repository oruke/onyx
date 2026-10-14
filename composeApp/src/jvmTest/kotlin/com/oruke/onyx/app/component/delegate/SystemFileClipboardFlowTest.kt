package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.app.platform.FileTransferable
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 使用真实系统剪贴板、统一 Provider 和完整后台任务链验证文件粘贴及旧剪切保护。 */
class SystemFileClipboardFlowTest {
    /**
     * 外部独立进程写入 Windows 原生文件列表，首次粘贴实际复制文件并保留源。
     * @return 无返回值，断言目标内容和源文件均正确。
     */
    @Test
    fun nativeExternalCopyCreatesTargetAndPreservesSource() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        withFileClipboard {
            val source = file("中文-日本語.txt", "native-copy-content")
            publishNativeClipboard(source, isCut = false)
            manager.refresh().getOrThrow()
            assertTrue(manager.canPaste)
            assertNull(manager.clipboard.value)
            pasteCurrent()
            assertEquals("native-copy-content", Files.readString(target.resolve(source.fileName)))
            assertEquals("native-copy-content", Files.readString(source))
        }
    }

    /**
     * 读取 Windows 原生 Preferred DropEffect，外部剪切实际移动文件并清空已消费载荷。
     * @return 无返回值，断言文件移动结果与剪贴板终态正确。
     */
    @Test
    fun nativeExternalCutMovesSourceAndClearsConsumedClipboard() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        withFileClipboard {
            val source = file("external-cut.txt", "native-cut-content")
            publishNativeClipboard(source, isCut = true)
            val payload = assertNotNull(manager.consume().getOrThrow())
            assertEquals(ClipboardManager.ClipboardOperation.CUT, payload.operation)
            pasteCurrent()
            assertFalse(Files.exists(source))
            assertEquals("native-cut-content", Files.readString(target.resolve(source.fileName)))
            assertNull(manager.consume().getOrThrow())
            assertFalse(manager.canPaste)
        }
    }

    /**
     * 内部先剪切 A、外部随后复制 B，最终只复制 B，不能移动旧的 A。
     * @return 无返回值，断言新复制准确且旧剪切源保留。
     */
    @Test
    fun externalCopyReplacesInternalCutWithoutMovingOldFile() = runBlocking {
        withFileClipboard {
            val oldSource = file("old-cut.txt", "old-content")
            val newSource = file("new-copy.txt", "new-content")
            manager.stageCut(listOf(entry(oldSource))).getOrThrow()
            writeTestClipboard(FileTransferable(listOf(newSource.toFile())))
            pasteCurrent()
            assertEquals("old-content", Files.readString(oldSource))
            assertEquals("new-content", Files.readString(newSource))
            assertEquals("new-content", Files.readString(target.resolve(newSource.fileName)))
            assertFalse(Files.exists(target.resolve(oldSource.fileName)))
        }
    }

    /**
     * 外部对同一路径重新执行复制，也必须覆盖旧的内部剪切操作类型。
     * @return 无返回值，断言按复制处理且源文件内容保留。
     */
    @Test
    fun copyingSamePathExternallyDoesNotReuseInternalCutFlag() = runBlocking {
        withFileClipboard {
            val source = file("same-path.txt", "same-path-content")
            manager.stageCut(listOf(entry(source))).getOrThrow()
            writeTestClipboard(FileTransferable(listOf(source.toFile())))
            assertEquals(ClipboardManager.ClipboardOperation.COPY, manager.consume().getOrThrow()?.operation)
            pasteCurrent()
            assertEquals("same-path-content", Files.readString(source))
            assertEquals("same-path-content", Files.readString(target.resolve(source.fileName)))
        }
    }

    /**
     * 外部文本重写剪贴板后禁用文件粘贴，旧剪切载荷立即失效。
     * @return 无返回值，断言旧文件载荷失效且未执行文件传输。
     */
    @Test
    fun externalTextInvalidatesPreviouslyCutFiles() = runBlocking {
        withFileClipboard {
            val source = file("keep.txt", "keep-content")
            manager.stageCut(listOf(entry(source))).getOrThrow()
            writeTestClipboard(StringSelection("current-text"))
            manager.refresh().getOrThrow()
            assertFalse(manager.canPaste)
            assertNull(manager.clipboard.value)
            assertNull(manager.consume().getOrThrow())
            assertEquals("keep-content", Files.readString(source))
            assertTrue(Files.list(target).use { it.findAny().isEmpty })
        }
    }

    /**
     * 外部文件列表含已删除文件时整批失败，不能回退到旧内部剪切或静默复制剩余条目。
     * @return 无返回值，断言整批拒绝且所有实际源文件保留。
     */
    @Test
    fun missingExternalFileRejectsWholePayloadAndPreservesOldCut() = runBlocking {
        withFileClipboard {
            val oldSource = file("old-cut.txt", "old-content")
            val valid = file("valid.txt", "valid-content")
            manager.stageCut(listOf(entry(oldSource))).getOrThrow()
            writeTestClipboard(FileTransferable(listOf(valid.toFile(), root.resolve("missing.txt").toFile())))
            assertTrue(manager.consume().isFailure)
            assertNull(manager.clipboard.value)
            assertEquals("old-content", Files.readString(oldSource))
            assertEquals("valid-content", Files.readString(valid))
            assertTrue(Files.list(target).use { it.findAny().isEmpty })
        }
    }

    /**
     * 旧剪切请求停留在真实冲突对话状态期间复制新文件，旧任务完成不得清空新载荷。
     * @return 无返回值，断言旧移动成功且后续复制载荷完整保留。
     */
    @Test
    fun completingPendingCutKeepsLaterClipboardCopy() = runBlocking {
        withFileClipboard {
            val oldSource = file("pending-cut.txt", "moved-content")
            val newSource = file("later-copy.txt", "later-content")
            Files.writeString(target.resolve(oldSource.fileName), "previous-target")
            manager.stageCut(listOf(entry(oldSource))).getOrThrow()
            val payload = assertNotNull(manager.consume().getOrThrow())
            withContext(Dispatchers.Main) {
                transfer.requestTransferEntriesToDirectory(payload.entries, target.toString(),
                    FileTransferOperation.MOVE, clearClipboardOnSuccess = true)
            }
            withTimeout(10_000) { dialog.first { it is RootDialogState.ConflictResolution } }
            manager.stageCopy(listOf(entry(newSource))).getOrThrow()
            withContext(Dispatchers.Main) { transfer.resolveConflict(TransferConflictStrategy.OVERWRITE, true) }
            awaitTransfer()
            assertFalse(Files.exists(oldSource))
            assertEquals("moved-content", Files.readString(target.resolve(oldSource.fileName)))
            val current = assertNotNull(manager.consume().getOrThrow())
            assertEquals(ClipboardManager.ClipboardOperation.COPY, current.operation)
            assertEquals(listOf(newSource.toString()), current.entries.map { it.location })
            assertEquals("later-content", Files.readString(newSource))
            assertEquals(listOf(newSource.toFile()),
                Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.javaFileListFlavor))
        }
    }

    /**
     * 内部正常剪切也必须从系统唯一标识恢复完整载荷，最终移动内容准确且不能再次粘贴。
     * @return 无返回值，断言移动成功且载荷已消费。
     */
    @Test
    fun internalCutStillMovesFileAndClearsBothClipboards() = runBlocking {
        withFileClipboard {
            val source = file("internal-cut.txt", "internal-cut-content")
            manager.stageCut(listOf(entry(source))).getOrThrow()
            pasteCurrent()
            assertFalse(Files.exists(source))
            assertEquals("internal-cut-content", Files.readString(target.resolve(source.fileName)))
            assertNull(manager.consume().getOrThrow())
            assertFalse(manager.canPaste)
        }
    }
}

/** 承载每个业务场景独有的真实文件、系统剪贴板管理器和传输任务环境。 */
private class ClipboardFlowFixture {
    /** 仅包含本测试样本的临时根目录，清理范围不允许超出此目录。 */
    val root: Path = Files.createTempDirectory("onyx-clipboard-flow-")
    /** 原始文件存放目录。 */
    val source: Path = Files.createDirectory(root.resolve("source"))
    /** 本场景实际文件传输目标目录。 */
    val target: Path = Files.createDirectory(root.resolve("target"))
    /** 实际执行列表、复制和移动的生产本地 Provider。 */
    val provider = JvmLocalFileProvider()
    /** 驱动状态观察与传输任务的实际主线程作用域。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 本场景真实系统剪贴板管理器，不使用 Mock 或替代文件数据。 */
    val manager = ClipboardManager(provider, JvmVfsPathService())
    /** 真实冲突对话业务状态。 */
    val dialog = MutableStateFlow<RootDialogState?>(null)
    /** 验证实际任务最终状态的生产任务编排器。 */
    val tasks = TaskOrchestrator(scope)
    /** 完整生产文件传输命令链。 */
    val transfer = FileTransferDelegate(scope, provider, provider, tasks, manager, dialog,
        JvmVfsPathService(), VfsProviderRegistry(listOf(LocalVfsProvider(provider))), onDirectoryChange = {})

    /**
     * 创建本场景独有的真实文本文件。
     * @param name 源目录内的文件名。
     * @param content 可识别的业务内容。
     * @return 已写入的真实文件路径。
     */
    fun file(name: String, content: String): Path = source.resolve(name).also { Files.writeString(it, content) }

    /**
     * 从真实 Provider 读取准备复制或剪切的条目。
     * @param path 本测试源目录内的真实文件路径。
     * @return Provider 返回的完整 VFile。
     */
    suspend fun entry(path: Path): VFile = provider.list(source.toString()).getOrThrow()
        .single { it.name == path.fileName.toString() }

    /**
     * 经生产剪贴板及后台任务链执行实际粘贴，核对最终任务成功状态。
     * @return 无返回值，任何读取失败或业务任务失败直接使场景失败。
     */
    suspend fun pasteCurrent() {
        val payload = assertNotNull(manager.consume().getOrThrow())
        withContext(Dispatchers.Main) {
            transfer.requestTransferEntriesToDirectory(payload.entries, target.toString(),
                if (payload.operation == ClipboardManager.ClipboardOperation.CUT) {
                    FileTransferOperation.MOVE
                } else FileTransferOperation.COPY,
                clearClipboardOnSuccess = payload.operation == ClipboardManager.ClipboardOperation.CUT)
        }
        awaitTransfer()
    }

    /**
     * 等待实际后台任务终态；文件内容核对由各业务场景分别执行。
     * @return 无返回值，仅接受 SUCCEEDED，任务失败或超时直接中止场景。
     */
    suspend fun awaitTransfer() {
        val completed = withTimeout(15_000) {
            tasks.tasks.first { all -> all.any { it.status == BackgroundTaskStatus.SUCCEEDED ||
                it.status == BackgroundTaskStatus.FAILED } }
        }.single()
        assertEquals(BackgroundTaskStatus.SUCCEEDED, completed.status, completed.detail.toString())
    }
}

/**
 * 隔离真实样本并在每个场景结束后恢复用户原系统剪贴板与回收任务资源。
 * @param block 本次完整业务场景。
 * @return 无返回值，业务断言或资源释放失败继续向测试框架传播。
 */
private suspend fun withFileClipboard(block: suspend ClipboardFlowFixture.() -> Unit) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val previous = clipboard.getContents(null)
    val fixture = ClipboardFlowFixture()
    try {
        fixture.manager.bind(fixture.scope)
        fixture.block()
    } finally {
        checkNotNull(fixture.scope.coroutineContext[Job]).cancelAndJoin()
        writeTestClipboard(previous ?: StringSelection(""))
        Files.walk(fixture.root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

/**
 * 发布或恢复测试系统载荷，只重试 Windows 原生剪贴板的短暂占用，不改变业务断言。
 * @param contents 本次外部样本或进入场景前保存的用户载荷。
 * @return 无返回值，持续不可访问时保留异常并使测试失败。
 */
private suspend fun writeTestClipboard(contents: Transferable) {
    var result = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(contents, null) }
    var retries = 0
    while (result.exceptionOrNull() is IllegalStateException && retries < 10) {
        delay(50)
        retries++
        result = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(contents, null) }
    }
    result.getOrThrow()
}

/**
 * 在独立 Windows 进程通过 WinForms 发布原生文件列表与 Shell 操作格式，验证真实跨进程互操作。
 * @param source 本场景临时源文件，不将任何真实用户路径拼接到命令文本。
 * @param isCut 是否发布 Windows Shell 移动偏好。
 * @return 无返回值，外部进程失败或超时直接使测试失败。
 */
private suspend fun publishNativeClipboard(source: Path, isCut: Boolean) = withContext(Dispatchers.IO) {
    val script = """
        Add-Type -AssemblyName System.Windows.Forms
        ${'$'}paths = [System.Collections.Specialized.StringCollection]::new()
        [void]${'$'}paths.Add([Environment]::GetEnvironmentVariable('ONYX_TEST_CLIPBOARD_SOURCE'))
        ${'$'}data = [System.Windows.Forms.DataObject]::new()
        ${'$'}data.SetFileDropList(${'$'}paths)
        ${'$'}effect = [int][Environment]::GetEnvironmentVariable('ONYX_TEST_CLIPBOARD_EFFECT')
        ${'$'}stream = [System.IO.MemoryStream]::new([BitConverter]::GetBytes(${'$'}effect))
        ${'$'}data.SetData('Preferred DropEffect', ${'$'}stream)
        [System.Windows.Forms.Clipboard]::SetDataObject(${'$'}data, ${'$'}true)
    """.trimIndent()
    val builder = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-STA", "-Command", script)
        .redirectErrorStream(true)
    builder.environment()["ONYX_TEST_CLIPBOARD_SOURCE"] = source.toString()
    builder.environment()["ONYX_TEST_CLIPBOARD_EFFECT"] = if (isCut) "2" else "1"
    val process = builder.start()
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("Native clipboard publisher timed out")
    }
    assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
}
