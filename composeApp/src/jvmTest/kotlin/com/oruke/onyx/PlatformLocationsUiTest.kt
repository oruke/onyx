package com.oruke.onyx

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.computerCommandEnabled
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.core.model.volumeLabel
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 使用真实应用入口、宿主设备和鼠标验证此电脑导航及详情、画廊显示。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class PlatformLocationsUiTest {
    /** 中文放大后的左右双面板能在一次点击中激活并选择非活动面板设备。 */
    @Test
    fun scaledDualVerticalComputerKeepsPaneInteraction() =
        verifyComputerLayout(
            AppLocale.SIMPLIFIED_CHINESE,
            PaneLayoutMode.DUAL_VERTICAL,
            125,
            1100,
            720,
            "computer-dual-zh",
        )

    /** 窄窗口与较大缩放下，英文容量与状态仍在面板内展示并允许滚动选择。 */
    @Test
    fun narrowEnglishComputerRemainsUsable() =
        verifyComputerLayout(
            AppLocale.ENGLISH,
            PaneLayoutMode.SINGLE,
            150,
            760,
            540,
            "computer-narrow-en",
        )

    /** 日语在上下双面板中沿用一致的密集布局和独立滚动范围。 */
    @Test
    fun japaneseComputerWorksInDualHorizontalLayout() =
        verifyComputerLayout(
            AppLocale.JAPANESE,
            PaneLayoutMode.DUAL_HORIZONTAL,
            125,
            960,
            720,
            "computer-dual-ja",
        )

    /** 从侧栏打开概览、真实双击进入设备，再从根目录返回概览并保留选择。 */
    @Test
    fun nativeComputerNavigationWorksThroughApplicationUi() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val sample = Files.createTempDirectory("onyx-computer-ui-")
            var captured: RootComponent? = null
            try {
                setContent { DirectoryBrowsingApplication(sample.toString()) { captured = it } }
                waitUntil(timeoutMillis = 15_000) {
                    captured?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                }
                val root = checkNotNull(captured)
                val directoryViewMode = root.primaryPane.state.value.viewMode
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(sidebarVisible = true, uiScale = 100,
                                preferredLocale = AppLocale.SIMPLIFIED_CHINESE)
                        )
                    )
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                }
                waitForIdle()
                onNodeWithTag("sidebar-computer", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 15_000) {
                    (root.primaryPane.state.value.entriesState as? ComputerEntriesState)
                        ?.devices
                        ?.isNotEmpty() == true
                }
                val computer = root.primaryPane.state.value.entriesState as ComputerEntriesState
                val device = computer.devices.first { sample.root.toString() in it.locations }
                val tag = "computer-entry-PRIMARY-${device.id}"
                onNodeWithTag(tag, useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 3_000) {
                    device.id in root.primaryPane.state.value.selectedEntryIds
                }
                onNodeWithTag(tag, useUnmergedTree = true).assertIsSelected()
                assertFalse(
                    computerCommandEnabled(
                        root.primaryPane.state.value,
                        PaneCommand.DELETE_SELECTION,
                    )
                )
                assertFalse(computerCommandEnabled(root.primaryPane.state.value, PaneCommand.PASTE))
                waitUntil(timeoutMillis = 15_000) {
                    (root.primaryPane.state.value.entriesState as? ComputerEntriesState)
                        ?.devices
                        ?.firstOrNull { it.id == device.id }
                        ?.totalBytes != null
                }
                onNodeWithTag(tag, useUnmergedTree = true).assertIsSelected()
                assertEquals(ViewMode.GALLERY, root.primaryPane.state.value.viewMode)
                val places = (root.primaryPane.state.value.entriesState as ComputerEntriesState).visiblePlaces
                assertTrue(places.size >= 2, "真实标准目录至少应提供两个入口以验证横排")
                val firstPlace = onNodeWithTag("computer-entry-PRIMARY-${places[0].location}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val nextPlace = onNodeWithTag("computer-entry-PRIMARY-${places[1].location}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertEquals(firstPlace.top, nextPlace.top)
                assertTrue(nextPlace.left > firstPlace.left, "标准目录应横向排列")
                onNodeWithTag("computer-capacity-PRIMARY-${device.id}", useUnmergedTree = true).assertIsDisplayed()
                saveComputerScreenshot("computer-overview")
                onNodeWithTag(tag, useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    advanceEventTime(80)
                    press()
                    release()
                }
                waitUntil(timeoutMillis = 15_000) {
                    root.primaryPane.state.value.location == sample.root.toString()
                }
                assertEquals(directoryViewMode, root.primaryPane.state.value.viewMode)
                val breadcrumbs = root.buildBreadcrumbs(sample.root.toString())
                assertEquals(sample.root.toString(), breadcrumbs.last().location)
                assertEquals(device.volumeLabel(), breadcrumbs.last().storageLabel)
                saveComputerScreenshot("computer-volume-breadcrumb")
                runOnUiThread { root.primaryPane.dispatch(PaneIntent.GoUp) }
                waitUntil(timeoutMillis = 15_000) {
                    root.primaryPane.state.value.location == COMPUTER_LOCATION &&
                        (root.primaryPane.state.value.entriesState as? ComputerEntriesState)
                            ?.devices
                            ?.isNotEmpty() == true
                }
                assertEquals(setOf(device.id), root.primaryPane.state.value.selectedEntryIds)
                onNodeWithContentDescription("紧凑列表").performClick()
                onNodeWithTag("computer-list-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
                assertEquals(ViewMode.DETAILS, root.primaryPane.state.value.viewMode)
                saveComputerScreenshot("computer-details")
                onNodeWithContentDescription("磁盘概览").performClick()
                waitForIdle()
                onNodeWithTag("computer-grid-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag(tag, useUnmergedTree = true).assertIsSelected()
                saveComputerScreenshot("computer-gallery")
                runOnUiThread { root.primaryPane.dispatch(PaneIntent.CreateTab(COMPUTER_LOCATION)) }
                waitUntil(timeoutMillis = 15_000) {
                    root.primaryPane.tabStates.value.size == 2 &&
                        root.primaryPane.state.value.entriesState is ComputerEntriesState
                }
                assertTrue(
                    root.primaryPane.tabStates.value.any { it.location == COMPUTER_LOCATION }
                )
                println("COMPUTER_UI: 侧栏、真实设备双击、根目录向上、选择恢复、画廊、新标签已核对")
            } finally {
                mainClock.autoAdvance = false
                setContent {}
                waitForIdle()
                Files.deleteIfExists(sample)
            }
        }
}

/**
 * 从生产 App 入口核对指定语言、布局和缩放，实际鼠标选择必须落在所属面板内。
 *
 * @param locale 测试使用的应用语言。
 * @param layout 单面板、左右或上下双面板布局。
 * @param scale 全局 UI 缩放百分比。
 * @param width 测试窗口物理像素宽度。
 * @param height 测试窗口物理像素高度。
 * @param evidence 本次截图名称。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
private fun verifyComputerLayout(
    locale: AppLocale,
    layout: PaneLayoutMode,
    scale: Int,
    width: Int,
    height: Int,
    evidence: String,
) =
    runDesktopComposeUiTest(width = width, height = height) {
        val sample = Files.createTempDirectory("onyx-computer-layout-")
        var captured: RootComponent? = null
        var savedSettings: OnyxSettings? = null
        try {
            setContent { DirectoryBrowsingApplication(sample.toString()) { captured = it } }
            waitUntil(timeoutMillis = 15_000) {
                captured?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
            }
            val root = checkNotNull(captured)
            savedSettings = root.state.value.settings
            runOnUiThread {
                root.dispatch(
                    RootIntent.UpdateSettings(
                        root.state.value.settings.copy(
                            preferredLocale = locale,
                            uiScale = scale,
                            sidebarVisible = true,
                        )
                    )
                )
                root.dispatch(RootIntent.SetLayoutMode(layout))
                root.primaryPane.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
                if (layout != PaneLayoutMode.SINGLE)
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
                root.dispatch(RootIntent.ActivatePane(PaneId.PRIMARY))
            }
            val paneId = if (layout == PaneLayoutMode.SINGLE) PaneId.PRIMARY else PaneId.SECONDARY
            val pane = if (paneId == PaneId.PRIMARY) root.primaryPane else root.secondaryPane
            waitUntil(timeoutMillis = 15_000) {
                (pane.state.value.entriesState as? ComputerEntriesState)?.devices?.any {
                    sample.root.toString() in it.locations && it.totalBytes != null
                } == true
            }
            val device =
                (pane.state.value.entriesState as ComputerEntriesState).devices.first {
                    sample.root.toString() in it.locations
                }
            val places = (pane.state.value.entriesState as ComputerEntriesState).visiblePlaces
            assertTrue(places.size >= 2)
            val firstPlace = onNodeWithTag("computer-entry-$paneId-${places[0].location}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val secondPlace = onNodeWithTag("computer-entry-$paneId-${places[1].location}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(firstPlace.top, secondPlace.top, "当前测试宽度应容纳多列快捷目录")
            assertTrue(secondPlace.left > firstPlace.left)
            val entry = onNodeWithTag("computer-entry-$paneId-${device.id}", useUnmergedTree = true)
            onNodeWithTag("computer-grid-$paneId", useUnmergedTree = true)
                .performScrollToNode(hasTestTag("computer-entry-$paneId-${device.id}"))
            entry.performClick()
            waitUntil(timeoutMillis = 5_000) {
                root.state.value.activePane == paneId &&
                    device.id in pane.state.value.selectedEntryIds
            }
            entry.assertIsDisplayed().assertIsSelected()
            val listBounds =
                onNodeWithTag("computer-grid-$paneId", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val entryBounds = entry.fetchSemanticsNode().boundsInRoot
            assertTrue(
                entryBounds.left >= listBounds.left && entryBounds.right <= listBounds.right,
                "设备条目不能超出当前面板宽度",
            )
            val sectionTitle =
                when (locale) {
                    AppLocale.SIMPLIFIED_CHINESE -> "本地磁盘"
                    AppLocale.JAPANESE -> "ローカルディスク"
                    else -> "Local volumes"
                }
            assertTrue(
                onAllNodesWithText(sectionTitle, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            )
            saveComputerScreenshot(evidence)
        } finally {
            savedSettings?.let { settings ->
                runOnUiThread { captured?.dispatch(RootIntent.UpdateSettings(settings)) }
            }
            mainClock.autoAdvance = false
            setContent {}
            waitForIdle()
            Files.deleteIfExists(sample)
        }
    }

/**
 * 保存应用根内容截图，供核对实际设备列表和平台入口的视觉一致性。
 *
 * @param name 本次验收截图的文件名。
 */
@OptIn(ExperimentalTestApi::class)
internal fun androidx.compose.ui.test.DesktopComposeUiTest.saveComputerScreenshot(name: String) {
    val target = Path.of("build", "storage-acceptance", "$name.png")
    Files.createDirectories(target.parent)
    assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target.toFile()))
}
