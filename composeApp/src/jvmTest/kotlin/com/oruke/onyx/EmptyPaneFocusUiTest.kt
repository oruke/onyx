@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.ui.BlankAreaClickController
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证空文件夹面板在双面板布局下的焦点切换、边缘点击、窗口坐标右键菜单及双击返回上级行为。
 */
class EmptyPaneFocusUiTest {

    /**
     * 验证空白区点击状态机在不阻塞首次按下的前提下，准确区分单击、双击、三连击、超时与拖拽位移。
     */
    @Test
    fun blankAreaClickControllerRecognizesDoubleClickWithoutBlockingFirstPress() {
        val controller = BlankAreaClickController(doubleClickIntervalProvider = { 500L })
        val dragSlopSquared = 64f
        val doubleClickSlopPx = 12f

        // 1. 首次单击：返回 false，记录连击起点
        controller.onPrimaryPress(pressMillis = 100L, position = Offset(40f, 40f))
        assertFalse(
            controller.onPrimaryRelease(
                releaseMillis = 120L,
                position = Offset(42f, 41f),
                dragSlopSquared = dragSlopSquared,
                doubleClickSlopPx = doubleClickSlopPx,
            ),
        )

        // 2. 阈值内第二次点击：识别为有效双击
        controller.onPrimaryPress(pressMillis = 320L, position = Offset(44f, 43f))
        assertTrue(
            controller.onPrimaryRelease(
                releaseMillis = 340L,
                position = Offset(44f, 43f),
                dragSlopSquared = dragSlopSquared,
                doubleClickSlopPx = doubleClickSlopPx,
            ),
        )

        // 3. 紧随其后的第三次点击：不应再次触发双击（防止三连击连续跳两级目录）
        controller.onPrimaryPress(pressMillis = 480L, position = Offset(44f, 43f))
        assertFalse(
            controller.onPrimaryRelease(
                releaseMillis = 500L,
                position = Offset(44f, 43f),
                dragSlopSquared = dragSlopSquared,
                doubleClickSlopPx = doubleClickSlopPx,
            ),
        )

        // 4. 超过双击间隔的第二次点击：仅视为新单击
        controller.onPrimaryPress(pressMillis = 1_200L, position = Offset(44f, 43f))
        assertFalse(
            controller.onPrimaryRelease(
                releaseMillis = 1_220L,
                position = Offset(44f, 43f),
                dragSlopSquared = dragSlopSquared,
                doubleClickSlopPx = doubleClickSlopPx,
            ),
        )

        // 5. 按下后拖拽位移超过阈值：取消连击链
        controller.onPrimaryPress(pressMillis = 1_400L, position = Offset(44f, 43f))
        assertFalse(
            controller.onPrimaryRelease(
                releaseMillis = 1_430L,
                position = Offset(80f, 80f),
                dragSlopSquared = dragSlopSquared,
                doubleClickSlopPx = doubleClickSlopPx,
            ),
        )
    }

    /**
     * 验证上下双面板布局中，单击空文件夹面板（含中心与边缘内边距区）可立即切换活动面板焦点，
     * 且空文件夹右键菜单锚定在所属面板窗口坐标内、双击空文件夹可激活并返回父目录。
     */
    @Test
    fun clickingEmptyDirectoryPaneSwitchesFocusImmediatelyAndSupportsContextMenuAndDoubleClickUp() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val tempRoot = Files.createTempDirectory("onyx-empty-pane-focus-")
            val emptyScanDir = Files.createDirectory(tempRoot.resolve("扫图"))
            val projectsDir = Files.createDirectory(tempRoot.resolve("Projects"))
            Files.writeString(projectsDir.resolve("onyx-readme.txt"), "sample")

            var capturedRoot: RootComponent? = null
            var savedSettings: OnyxSettings? = null
            try {
                setContent {
                    DirectoryBrowsingApplication(projectsDir.toString()) { capturedRoot = it }
                }
                waitUntil(timeoutMillis = 15_000) {
                    capturedRoot?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                }
                val root = checkNotNull(capturedRoot)
                savedSettings = root.state.value.settings

                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(
                                preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
                                themeMode = OnyxThemeMode.DARK,
                                doubleClickEmptySpaceToNavigateUp = true,
                                uiScale = 100,
                            ),
                        ),
                    )
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.DUAL_HORIZONTAL))
                    root.primaryPane.dispatch(PaneIntent.OpenDirectory(emptyScanDir.toString()))
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(projectsDir.toString()))
                    root.dispatch(RootIntent.ActivatePane(PaneId.SECONDARY))
                }

                waitUntil(timeoutMillis = 10_000) {
                    val primaryReady = root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready
                    val secondaryReady = root.secondaryPane.state.value.entriesState as? PaneEntriesState.Ready
                    root.state.value.activePane == PaneId.SECONDARY &&
                        primaryReady != null && primaryReady.entries.isEmpty() &&
                        secondaryReady != null && secondaryReady.entries.size == 1
                }
                waitForIdle()
                onNodeWithText("文件夹为空", useUnmergedTree = true).assertIsDisplayed()
                captureEmptyPaneEvidence("01-before-click-empty-primary")

                // 1. 单击上面板（PRIMARY）空文件夹中心区域：应立即将焦点从 SECONDARY 切换到 PRIMARY
                onNodeWithTag("pane-empty-entries-PRIMARY", useUnmergedTree = true).performMouseInput {
                    click(center)
                }
                waitForIdle()
                assertEquals(PaneId.PRIMARY, root.state.value.activePane, "单击空文件夹中心区域应立即激活上面板")
                assertEquals(
                    emptyScanDir.toString(),
                    root.primaryPane.state.value.location,
                    "单击空文件夹仅切换焦点，不应误触发返回上级",
                )
                captureEmptyPaneEvidence("02-after-click-empty-primary")

                // 2. 点击下面板文件条目把焦点切回 SECONDARY，再单击上面板空文件夹左上角边缘（原 24.dp padding 盲区）
                onNodeWithText("onyx-readme.txt", useUnmergedTree = true).performMouseInput {
                    click(center)
                }
                waitUntil(timeoutMillis = 5_000) { root.state.value.activePane == PaneId.SECONDARY }

                onNodeWithTag("pane-empty-entries-PRIMARY", useUnmergedTree = true).performMouseInput {
                    click(Offset(8f, 8f))
                }
                waitForIdle()
                assertEquals(PaneId.PRIMARY, root.state.value.activePane, "单击空文件夹边缘内边距区域也应激活上面板")

                // 3. 反向场景：上面板为非空目录并处于活跃态，下面板（SECONDARY）为空目录；
                //    右键点击下面板空目录，应激活 SECONDARY 且菜单弹层位于下半屏而非飘到上面板
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.OpenDirectory(projectsDir.toString()))
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(emptyScanDir.toString()))
                    root.dispatch(RootIntent.ActivatePane(PaneId.PRIMARY))
                }
                waitUntil(timeoutMillis = 10_000) {
                    val secondaryReady = root.secondaryPane.state.value.entriesState as? PaneEntriesState.Ready
                    root.state.value.activePane == PaneId.PRIMARY &&
                        secondaryReady != null && secondaryReady.entries.isEmpty()
                }
                waitForIdle()

                val secondaryBounds = onNodeWithTag("pane-empty-entries-SECONDARY", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                onNodeWithTag("pane-empty-entries-SECONDARY", useUnmergedTree = true).performMouseInput {
                    rightClick(center)
                }
                waitForIdle()
                assertEquals(PaneId.SECONDARY, root.state.value.activePane, "右键空文件夹面板应激活下面板")
                val refreshMenuBounds = onNodeWithText("刷新", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
                assertTrue(
                    refreshMenuBounds.top >= secondaryBounds.top - 4f,
                    "下面板空目录右键菜单应基于窗口坐标定位在下面板内，实际 top=${refreshMenuBounds.top}, secondaryTop=${secondaryBounds.top}",
                )
                captureEmptyPaneEvidence("03-secondary-empty-context-menu")

                // 左键单击空文件夹面板应立即关闭已打开的右键菜单
                onNodeWithTag("pane-empty-entries-SECONDARY", useUnmergedTree = true).performMouseInput {
                    click(Offset(16f, 16f))
                }
                waitForIdle()
                onNodeWithText("刷新", useUnmergedTree = true).assertDoesNotExist()

                // 4. 先切回 PRIMARY，再在下面板空目录执行双击：应同时激活 SECONDARY 并返回上级目录 tempRoot
                runOnUiThread {
                    root.dispatch(RootIntent.ActivatePane(PaneId.PRIMARY))
                }
                waitUntil(timeoutMillis = 5_000) { root.state.value.activePane == PaneId.PRIMARY }
                waitForIdle()

                onNodeWithTag("pane-empty-entries-SECONDARY", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(20L)
                    release()
                    advanceEventTime(180L)
                    press()
                    advanceEventTime(20L)
                    release()
                }
                waitUntil(timeoutMillis = 10_000) {
                    root.state.value.activePane == PaneId.SECONDARY &&
                        normalizePath(root.secondaryPane.state.value.location) == normalizePath(tempRoot.toString())
                }
            } finally {
                val root = capturedRoot
                val original = savedSettings
                if (root != null && original != null) {
                    runOnUiThread { root.dispatch(RootIntent.UpdateSettings(original)) }
                    waitForIdle()
                }
                setContent { }
                waitForIdle()
                Files.walk(tempRoot).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }

    /**
     * 保存双面板空目录焦点与右键菜单验收截图。
     *
     * @param name 截图文件基名。
     */
    private fun DesktopComposeUiTest.captureEmptyPaneEvidence(name: String) {
        val output = Path.of("build", "reports", "tests", "empty-pane-focus", "$name.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", output.toFile()))
    }

    /**
     * 规范化路径分隔符以便跨写法比对目录位置。
     */
    private fun normalizePath(raw: String): String = raw.replace('\\', '/').trimEnd('/')
}
