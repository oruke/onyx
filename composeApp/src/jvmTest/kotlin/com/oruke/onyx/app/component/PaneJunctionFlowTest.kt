package com.oruke.onyx.app.component

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.oruke.onyx.app.filesystem.JvmArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.JvmDesktopExternalOpenService
import com.oruke.onyx.app.filesystem.JvmFileTypeService
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.app.filesystem.SystemFileOpenLauncher
import com.oruke.onyx.app.filesystem.WindowsJunctionFixture
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.ResourceEntryNameSuggestionService
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 通过生产面板、Decompose 生命周期和真实目录联接验证目录列表不会因不可进入条目而消失。 */
class PaneJunctionFlowTest {
    /** 打开断开联接只显示局部错误，随后内联重命名和删除刷新仍能完成。 */
    @Test
    fun offlineJunctionKeepsParentEntriesAndSupportsInlineRename() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        WindowsJunctionFixture().use { fixture ->
            val parent = fixture.directory("listing")
            Files.writeString(parent.resolve("ordinary.txt"), "keep")
            val link = fixture.junction("listing/offline", fixture.testPath("missing-target"))
            val provider = JvmLocalFileProvider()
            withContext(Dispatchers.Main) {
                val lifecycle = LifecycleRegistry()
                try {
                    val pane = createJunctionPane(parent.toString(), provider, lifecycle)
                    lifecycle.resume()
                    val entries = pane.awaitEntries(setOf("ordinary.txt", "offline"))
                    val entry = entries.single { it.name == "offline" }

                    // 从生产意图入口执行打开，验证错误局限于操作反馈而非目录加载状态。
                    pane.dispatch(PaneIntent.OpenEntry(entry))
                    assertEquals(parent.toString(), pane.state.value.location)
                    assertEquals(entries, assertIs<PaneEntriesState.Ready>(pane.state.value.entriesState).entries)
                    val feedback = assertNotNull(pane.state.value.operationFeedback)
                    assertEquals(PaneOperationFeedbackKind.OPEN_FAILED, feedback.kind)
                    assertEquals(MessageKey.MSG_DIRECTORY_UNAVAILABLE, feedback.detail?.key)
                    assertEquals(listOf(link.toString()), feedback.detail?.args)

                    pane.dispatch(PaneIntent.SelectEntry(entry.id))
                    pane.dispatch(PaneIntent.BeginRename)
                    assertNotNull(pane.state.value.inlineEditState)
                    pane.dispatch(PaneIntent.UpdateInlineEditDraft("renamed-offline"))
                    pane.dispatch(PaneIntent.ConfirmInlineEdit)
                    val renamed = pane.awaitEntries(setOf("ordinary.txt", "renamed-offline"))
                        .single { it.name == "renamed-offline" }
                    assertNull(pane.state.value.operationFeedback)
                    assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))

                    provider.delete(listOf(renamed)).getOrThrow()
                    pane.dispatch(PaneIntent.Refresh)
                    pane.awaitEntries(setOf("ordinary.txt"))
                    assertFalse(Files.exists(parent.resolve(renamed.name), LinkOption.NOFOLLOW_LINKS))
                    assertEquals("keep", Files.readString(parent.resolve("ordinary.txt")))
                } finally {
                    lifecycle.destroy()
                }
            }
        }
    }
}

/**
 * 使用生产服务装配真实面板，外部应用启动边界若被错误调用则立即失败。
 *
 * @param location 测试目录位置。
 * @param provider 实际执行文件操作的本地 Provider。
 * @param lifecycle 驱动面板及监听器回收的生命周期。
 * @return 已开始加载测试目录的面板组件。
 */
private fun createJunctionPane(
    location: String,
    provider: JvmLocalFileProvider,
    lifecycle: LifecycleRegistry,
): DefaultPaneComponent {
    val externalOpen = JvmDesktopExternalOpenService(
        fileOpenLauncher = SystemFileOpenLauncher { error("A directory junction must not launch an external app") },
    )
    return DefaultPaneComponent(
        componentContext = DefaultComponentContext(lifecycle),
        paneId = PaneId.PRIMARY,
        initialLocation = location,
        fileRepository = provider,
        fileCommandService = provider,
        textClipboardService = JvmTextClipboardService(),
        externalOpenService = externalOpen,
        pathService = JvmVfsPathService(),
        entryNameSuggestionService = ResourceEntryNameSuggestionService(),
        fileTypeService = JvmFileTypeService(),
        archiveEntryOpenService = JvmArchiveEntryOpenService(ArchiveService(), externalOpen),
    )
}

/**
 * 等待生产面板实际加载出预期条目，避免把意图已发送当作业务完成。
 *
 * @param names 期望观察到的全部文件和联接名称。
 * @return 当前可见的完整目录列表。
 */
private suspend fun DefaultPaneComponent.awaitEntries(names: Set<String>): List<VFile> {
    val loaded = withTimeout(10_000) {
        state.first { current ->
            val ready = current.entriesState as? PaneEntriesState.Ready
            ready?.entries?.map { it.name }?.toSet() == names
        }
    }
    return assertIs<PaneEntriesState.Ready>(loaded.entriesState).entries
}
