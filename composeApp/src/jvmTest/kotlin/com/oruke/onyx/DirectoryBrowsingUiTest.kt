@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootLaunchConfiguration
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.rememberRootApplicationRuntime
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.di.fileModule
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.koin.compose.KoinApplication
import org.koin.compose.getKoin

/** 从生产应用根组件、真实文件命令和 Compose 布局验证目录更新的实际可见位置。 */
class DirectoryBrowsingUiTest {
    /** 通过生产根界面的真实鼠标按住与松开验证详情、画廊新增文件不会挤动交互目标。 */
    @Test
    fun passiveChangesKeepPressedItemInPlaceInBothViews() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val sample = Files.createTempDirectory("onyx-passive-ui-")
        repeat(20) { Files.writeString(sample.resolve("item-${it.toString().padStart(4, '0')}.txt"), "sample-$it") }
        var capturedRoot: RootComponent? = null
        try {
            setContent { DirectoryBrowsingApplication(sample.toString()) { capturedRoot = it } }
            waitUntil(timeoutMillis = 15_000) { capturedRoot?.let { root ->
                root.state.value.sessionRestoreState == SessionRestoreState.Ready &&
                    (root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready)?.entries?.size == 20
            } == true }
            val root = checkNotNull(capturedRoot)
            runOnUiThread {
                root.dispatch(RootIntent.UpdateSettings(root.state.value.settings.copy(sidebarVisible = false, uiScale = 100)))
                root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
            }
            for ((index, mode) in listOf(ViewMode.DETAILS, ViewMode.GALLERY).withIndex()) {
                runOnUiThread { root.primaryPane.dispatch(PaneIntent.SetViewMode(mode)) }
                waitForIdle()
                val selected = (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready).entries
                    .single { it.name == "item-0007.txt" }
                runOnUiThread { root.primaryPane.dispatch(PaneIntent.SelectEntry(selected.id)) }
                waitForIdle()
                val before = rowTop(selected.name)
                onNodeWithText(selected.name, useUnmergedTree = true).performMouseInput { moveTo(center); press() }
                waitUntil { root.primaryPane.state.value.activeTabState.browserState.pointerInteractionActive }
                val added = "000-before-${mode.name}.txt"
                Files.writeString(sample.resolve(added), "passive")
                waitUntil(timeoutMillis = 10_000) {
                    (root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready)?.entries?.size == 21 + index
                }
                waitForIdle()
                assertPixelPosition(before, rowTop(selected.name))
                assertTrue(root.primaryPane.state.value.activeTabState.browserState.directoryOrderDeferred)
                captureBrowsing("passive-pressed-${mode.name.lowercase()}")
                onNodeWithText(selected.name, useUnmergedTree = true).performMouseInput { release() }
                waitUntil {
                    val state = root.primaryPane.state.value
                    val entries = (state.entriesState as? PaneEntriesState.Ready)?.entries
                    !state.activeTabState.browserState.pointerInteractionActive &&
                        !state.activeTabState.browserState.directoryOrderDeferred &&
                        entries != null && entries == entries.sortedBy { it.name }
                }
                assertEquals(setOf(selected.id), root.primaryPane.state.value.selectedEntryIds)
            }
            println("PASSIVE_UI: 详情和画廊真实鼠标按住期间条目像素位置不变，松开后恢复排序")
        } finally {
            mainClock.autoAdvance = false
            setContent { }
            waitForIdle()
            Files.walk(sample).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    /** 在一万项真实目录中验证删除、F5、历史返回和详情/画廊各自的实际视口。 */
    @Test
    fun realApplicationKeepsViewportAcrossMutationsNavigationAndViewModes() = runDesktopComposeUiTest(
        width = 1200, height = 800,
    ) {
        val sample = Files.createTempDirectory("onyx-browsing-ui-")
        val child = Files.createDirectory(sample.resolve("child"))
        repeat(10_000) { Files.writeString(sample.resolve("item-${it.toString().padStart(5, '0')}.txt"), "sample") }
        var capturedRoot: RootComponent? = null
        val contentSize = mutableStateOf(DpSize(1200.dp, 800.dp))
        try {
            setContent {
                Box(Modifier.size(contentSize.value)) {
                    DirectoryBrowsingApplication(sample.toString()) { capturedRoot = it }
                }
            }
            waitUntil(timeoutMillis = 15_000) { capturedRoot?.let { root ->
                root.state.value.sessionRestoreState == SessionRestoreState.Ready &&
                    (root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready)?.entries?.size == 10_001
            } == true }
            val root = checkNotNull(capturedRoot)
            runOnUiThread {
                root.dispatch(RootIntent.UpdateSettings(root.state.value.settings.copy(
                    deleteMode = DeleteMode.PERMANENT, sidebarVisible = false, uiScale = 100, listRowHeightDp = 22)))
                root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS))
            }
            waitForIdle()
            val list = onNodeWithTag("pane-entries-PRIMARY", useUnmergedTree = true)
            list.performScrollToIndex(5001)
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 9f) }
            waitForIdle()
            val before = rowTop("item-05005.txt")
            val entries = assertIs<PaneEntriesState.Ready>(root.primaryPane.state.value.entriesState).entries
            val above = entries.single { it.name == "item-04990.txt" }
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SelectEntry(above.id)) }
            deleteSelection(root)
            assertFalse(Files.exists(sample.resolve(above.name)))
            assertPixelPosition(before, rowTop("item-05005.txt"))

            // 首项被删除后保持其后继的行位置，不能回到顶部或跳到新选择。
            val nextBefore = rowTop("item-05001.txt")
            val first = entries.single { it.name == "item-05000.txt" }
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SelectEntry(first.id)) }
            deleteSelection(root)
            assertPixelPosition(nextBefore, rowTop("item-05002.txt"))
            val restoredTop = rowTop("item-05005.txt")
            val selection = entries.filter { it.name == "item-05007.txt" || it.name == "item-05010.txt" }
                .mapTo(linkedSetOf()) { it.id }
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SelectEntries(selection)) }
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.Refresh) }
            waitUntil(timeoutMillis = 15_000) { !root.primaryPane.state.value.activeTabState.refreshing }
            waitForIdle()
            assertPixelPosition(restoredTop, rowTop("item-05005.txt"))
            assertEquals(selection, root.primaryPane.state.value.selectedEntryIds)
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.OpenDirectory(child.toString())) }
            waitUntil(timeoutMillis = 10_000) { root.primaryPane.state.value.location == child.toString() &&
                root.primaryPane.state.value.entriesState is PaneEntriesState.Ready }
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.GoUp) }
            waitUntil(timeoutMillis = 10_000) { root.primaryPane.state.value.location == sample.toString() &&
                root.primaryPane.state.value.entriesState is PaneEntriesState.Ready }
            waitForIdle()
            assertPixelPosition(restoredTop, rowTop("item-05005.txt"))
            assertEquals(selection, root.primaryPane.state.value.selectedEntryIds)
            captureBrowsing("details-restored")
            println("BROWSING_UI: 详情删除、刷新和历史位置已核对")

            runOnUiThread { root.dispatch(RootIntent.UpdateSettings(root.state.value.settings.copy(
                uiScale = 125, listRowHeightDp = 30))) }
            println("BROWSING_UI: 等待 125% 缩放布局")
            waitForIdle()
            onNodeWithText("item-05005.txt", useUnmergedTree = true).assertIsDisplayed()
            runOnUiThread { root.dispatch(RootIntent.UpdateSettings(root.state.value.settings.copy(
                uiScale = 100, listRowHeightDp = 22))) }
            println("BROWSING_UI: 等待 100% 缩放布局")
            waitForIdle()
            assertPixelPosition(restoredTop, rowTop("item-05005.txt"))

            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.GALLERY)) }
            waitForIdle()
            onNodeWithTag("pane-entries-PRIMARY", useUnmergedTree = true).performScrollToIndex(5000)
            onNodeWithTag("pane-entries-PRIMARY", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 7f) }
            waitForIdle()
            val galleryTop = rowTop("item-05010.txt")
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.Refresh) }
            waitUntil(timeoutMillis = 15_000) { !root.primaryPane.state.value.activeTabState.refreshing }
            waitForIdle()
            assertPixelPosition(galleryTop, rowTop("item-05010.txt"))
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS)) }
            waitForIdle()
            assertPixelPosition(restoredTop, rowTop("item-05005.txt"))
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.GALLERY)) }
            waitForIdle()
            assertPixelPosition(galleryTop, rowTop("item-05010.txt"))
            captureBrowsing("gallery-restored")
            println("BROWSING_UI: 画廊刷新与视图切换位置已核对")
            runOnUiThread { root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS)) }
            waitForIdle()
            verifyDirectoryRetry(root, sample, "directory-retry")
            runOnUiThread { contentSize.value = DpSize(760.dp, 500.dp) }
            waitForIdle()
            verifyDirectoryRetry(root, sample, "directory-retry-narrow")
        } catch (failure: Throwable) {
            println("BROWSING_UI: 失败 ${failure.message}")
            throw failure
        } finally {
            mainClock.autoAdvance = false
            setContent { }
            waitForIdle()
            Files.walk(sample).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/**
 * 从生产 DI 装配真实根组件，以与原生窗口相同的内容入口运行；配置由测试进程隔离。
 * @param location 本测试创建的唯一样本目录。
 * @param onRoot 暴露生产命令入口和结果观察点。
 */
@Composable
private fun DirectoryBrowsingApplication(location: String, onRoot: (RootComponent) -> Unit) {
    KoinApplication(application = { modules(fileModule) }) {
        IntUiTheme(theme = JewelTheme.lightThemeDefinition(), styling = ComponentStyling.default().decoratedWindow(
            titleBarStyle = TitleBarStyle.lightWithLightHeader())) {
            val koin = getKoin()
            val runtime = rememberRootApplicationRuntime()
            val drag = remember { koin.get<ExternalFileDragService>() }
            val launch = remember { RootLaunchConfiguration(initialLocation = location, persistsMainSession = false) }
            val root = rememberRootComponent(runtime, drag, launch)
            SideEffect { onRoot(root) }
            DisposableEffect(drag) { onDispose(drag::dispose) }
            App(root, drag)
        }
    }
}

/**
 * 经生产根命令执行永久删除，等待任务和真实列表同步完成。
 * @param root 当前生产根组件。
 */
private fun DesktopComposeUiTest.deleteSelection(root: RootComponent) {
    val targets = root.primaryPane.state.value.selectedEntryIds
    runOnUiThread {
        root.dispatch(RootIntent.RequestDeleteSelectedInPane(PaneId.PRIMARY))
        root.dispatch(RootIntent.ConfirmDialog)
    }
    waitUntil(timeoutMillis = 15_000) {
        val ready = root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready
        ready != null && ready.entries.none { it.id in targets } &&
            root.state.value.tasks.none { it.status.name in setOf("RUNNING", "QUEUED") }
    }
    waitForIdle()
}

/**
 * 用真实目录暂时离线验证错误反馈与点击重试，保持旧内容、选择和相对列表的实际位置。
 * @param root 当前生产根组件。
 * @param sample 本测试创建并拥有的目录；离线样本始终在退出前移回原位置。
 * @param evidenceName 当前内容尺寸对应的截图名称。
 */
private fun DesktopComposeUiTest.verifyDirectoryRetry(root: RootComponent, sample: Path, evidenceName: String) {
    val unavailable = sample.resolveSibling("${sample.fileName}-unavailable")
    val selection = root.primaryPane.state.value.selectedEntryIds
    val ready = assertIs<PaneEntriesState.Ready>(root.primaryPane.state.value.entriesState)
    val before = rowTop("item-05005.txt") -
        onNodeWithTag("pane-entries-PRIMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
    Files.move(sample, unavailable)
    try {
        runOnUiThread { root.primaryPane.dispatch(PaneIntent.Refresh) }
        waitUntil(timeoutMillis = 15_000) { root.primaryPane.state.value.activeTabState.directoryError != null }
        waitForIdle()
        onNodeWithTag("pane-directory-feedback-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
        val feedback = onNodeWithTag("pane-directory-feedback-PRIMARY", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        onNodeWithTag("pane-directory-retry-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
        val retry = onNodeWithTag("pane-directory-retry-PRIMARY", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(retry.right <= feedback.right && retry.bottom <= feedback.bottom, "重试入口超出反馈条")
        assertEquals(ready.entries, assertIs<PaneEntriesState.Ready>(root.primaryPane.state.value.entriesState).entries)
        assertEquals(selection, root.primaryPane.state.value.selectedEntryIds)
        assertPixelPosition(before, rowTop("item-05005.txt") -
            onNodeWithTag("pane-entries-PRIMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top)
        captureBrowsing(evidenceName)
    } finally {
        Files.move(unavailable, sample)
    }
    onNodeWithTag("pane-directory-retry-PRIMARY", useUnmergedTree = true).performClick()
    waitUntil(timeoutMillis = 15_000) { root.primaryPane.state.value.activeTabState.let {
        !it.refreshing && it.directoryError == null && it.directoryObservationError == null
    } }
    waitForIdle()
    onNodeWithTag("pane-directory-feedback-PRIMARY", useUnmergedTree = true).assertDoesNotExist()
    assertEquals(selection, root.primaryPane.state.value.selectedEntryIds)
    assertPixelPosition(before, rowTop("item-05005.txt") -
        onNodeWithTag("pane-entries-PRIMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top)
    println("BROWSING_UI: 目录离线保留内容、可见错误与点击重试已核对")
}

/**
 * 读取实际文本节点的布局位置，而非组件保存的视口数据。
 * @param name 唯一的样本文件名。
 * @return 文本在根内容中的物理像素纵坐标。
 */
private fun DesktopComposeUiTest.rowTop(name: String): Float =
    onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

/**
 * 验证布局位置误差不超过一个物理像素。
 * @param expected 操作前应保持的位置。
 * @param actual 操作后的真实位置。
 */
private fun assertPixelPosition(expected: Float, actual: Float) {
    assertTrue(abs(expected - actual) <= 1f, "视口偏移：预期 $expected，实际 $actual")
}

/**
 * 保存生产内容截图，供人工核对布局、选中效果与空白闪烁风险。
 * @param name 不含路径的证据名称。
 */
private fun DesktopComposeUiTest.captureBrowsing(name: String) {
    val output = Path.of("build", "browsing-acceptance", "$name.png")
    Files.createDirectories(output.parent)
    assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output.toFile()))
}
