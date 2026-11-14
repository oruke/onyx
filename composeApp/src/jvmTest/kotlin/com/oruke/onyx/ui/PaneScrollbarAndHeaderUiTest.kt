@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.defaultDetailsColumnWeights
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证详情表头全宽铺满、垂直滚动条顶部紧贴表头下沿，以及详情/画廊滚动条滑块与轨道手势绝不误触框选或清空选择。
 */
class PaneScrollbarAndHeaderUiTest {

    /** 验证宽面板下详情表头铺满整行宽度，且右侧垂直滚动条顶部紧贴表头底边无留白。 */
    @Test
    fun detailsHeaderSpansFullWidthAndScrollbarStartsFlushBelowHeader() =
        runDesktopComposeUiTest(width = 1100, height = 360) {
            val harness = ScrollbarInteractionHarness(viewMode = ViewMode.DETAILS, entryCount = 80)
            try {
                setContent { harness.Content() }
                waitForIdle()

                val headerBounds = onNodeWithTag("pane-details-header", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val scrollbarBounds = onNodeWithTag("pane-vertical-scrollbar-PRIMARY", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot

                assertEquals(0f, headerBounds.left, 0.5f, "详情表头左边界应贴齐面板左边缘")
                assertEquals(1100f, headerBounds.right, 0.5f, "详情表头右边界应铺满整个宽面板")
                assertTrue(
                    abs(scrollbarBounds.top - headerBounds.bottom) <= 1f,
                    "垂直滚动条顶部(${scrollbarBounds.top})应紧贴表头下沿(${headerBounds.bottom})",
                )
                assertEquals(360f, scrollbarBounds.bottom, 1f, "无横向滚动条时垂直滚动条底部应贴齐面板底边")

                val image = onRoot().captureToImage().toAwtImage()
                saveScreenshot(image, "details-header-fullwidth-and-top-thumb.png")

                val expectedHeaderRgb = harness.capturedHeaderBackground.toArgb() and 0x00FFFFFF
                val surfaceRgb = harness.capturedSurfaceBackground.toArgb() and 0x00FFFFFF
                val rightHeaderBlankRgb = image.getRGB(950, (headerBounds.height / 2f).toInt()) and 0x00FFFFFF
                val aboveScrollbarHeaderRgb = image.getRGB(1096, (headerBounds.height / 2f).toInt()) and 0x00FFFFFF
                assertEquals(expectedHeaderRgb, rightHeaderBlankRgb, "表头右侧空白延伸区必须填充 headerBackground")
                assertEquals(expectedHeaderRgb, aboveScrollbarHeaderRgb, "滚动条正上方的表头区域必须填充 headerBackground")

                // 列表初始位于顶部时，滑块紧贴表头下沿渲染，颜色应明显区别于列表底色
                val topThumbRgb = image.getRGB(1096, (headerBounds.bottom + 4f).toInt()) and 0x00FFFFFF
                assertNotEquals(surfaceRgb, topThumbRgb, "初始顶部状态下滚动条滑块应紧贴表头正下方渲染")
            } finally {
                setContent { }
                waitForIdle()
            }
        }

    /** 验证拖拽滚动条滑块、按住滚动条轨道滑动及双击轨道翻页均正常滚动，绝不触发框选、清空选择或返回上级。 */
    @Test
    fun detailsScrollbarDragAndTrackInteractionsNeverTriggerRubberBandOrClearSelection() =
        runDesktopComposeUiTest(width = 1100, height = 360) {
            val harness = ScrollbarInteractionHarness(viewMode = ViewMode.DETAILS, entryCount = 80)
            val initialSelection = setOf("file-001", "file-002")
            harness.selectedIds = initialSelection
            harness.paneActive = false
            try {
                setContent { harness.Content() }
                waitForIdle()

                val scrollbarBounds = onNodeWithTag("pane-vertical-scrollbar-PRIMARY", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val scrollbarCenterX = (scrollbarBounds.left + scrollbarBounds.right) / 2f

                // 1. 按住顶部滑块向下拖拽到底部（中途故意横向偏移到列表区再拉回，模拟真实手势抖动）
                onRoot().performMouseInput {
                    moveTo(Offset(scrollbarCenterX, scrollbarBounds.top + 8f))
                    press()
                    advanceEventTime(16L)
                    moveTo(Offset(scrollbarCenterX - 30f, scrollbarBounds.top + 120f))
                    advanceEventTime(16L)
                    moveTo(Offset(scrollbarCenterX - 12f, scrollbarBounds.bottom - 10f))
                    advanceEventTime(16L)
                }
                waitForIdle()

                // 按住拖拽尚未松开时：严禁出现蓝色框选矩形，已选文件严禁被清空或篡改
                onNodeWithTag("pane-rubber-band-PRIMARY", useUnmergedTree = true).assertDoesNotExist()
                assertTrue(harness.paneActive, "操作非活跃面板滚动条应立即激活面板")
                assertEquals(initialSelection, harness.selectedIds, "拖拽滑块途中不应改动已选文件集合")
                onNodeWithText("00000079.png", useUnmergedTree = true).assertIsDisplayed()
                saveScreenshot(onRoot().captureToImage().toAwtImage(), "details-scrollbar-thumb-dragged-bottom.png")

                onRoot().performMouseInput {
                    release()
                }
                waitForIdle()
                assertEquals(initialSelection, harness.selectedIds, "松开滚动条滑块后不应触发空白单击清空选择")

                // 2. 滑块位于底部时，在上方轨道空白处按下并向下滑动（复现原截图中轨道滑动触发蓝色框选的场景）
                onRoot().performMouseInput {
                    moveTo(Offset(scrollbarCenterX, scrollbarBounds.top + 16f))
                    press()
                    advanceEventTime(16L)
                    moveTo(Offset(scrollbarCenterX - 3f, scrollbarBounds.top + 140f))
                    advanceEventTime(16L)
                    moveTo(Offset(scrollbarCenterX - 3f, scrollbarBounds.bottom - 20f))
                    advanceEventTime(16L)
                }
                waitForIdle()
                onNodeWithTag("pane-rubber-band-PRIMARY", useUnmergedTree = true).assertDoesNotExist()
                assertEquals(initialSelection, harness.selectedIds, "在滚动条轨道上按住滑动绝不能触发框选")
                saveScreenshot(onRoot().captureToImage().toAwtImage(), "details-scrollbar-track-drag-no-rubberband.png")

                onRoot().performMouseInput {
                    release()
                }
                waitForIdle()
                assertEquals(initialSelection, harness.selectedIds, "松开滚动条轨道手势后仍应保留原选择")

                // 3. 在滚动条轨道上连续快速点击两次翻页：绝不能触发双击空白返回上一级目录
                onRoot().performMouseInput {
                    val trackPoint = Offset(scrollbarCenterX, scrollbarBounds.top + 20f)
                    click(trackPoint)
                    advanceEventTime(120L)
                    click(trackPoint)
                }
                waitForIdle()
                assertEquals(0, harness.navigateUpCount, "双击滚动条轨道翻页绝不能触发返回上一级目录")
                assertEquals(initialSelection, harness.selectedIds, "双击滚动条轨道翻页不应清空当前选中项")
                assertEquals(0, harness.blankContextMenuCount, "左键操作滚动条不应触发菜单")

                // 4. 对照验证：在真正的列表右侧空白区按下拖拽时，框选依然正常工作
                onRoot().performMouseInput {
                    moveTo(Offset(880f, scrollbarBounds.top + 20f))
                    press()
                    advanceEventTime(16L)
                    moveTo(Offset(200f, scrollbarBounds.top + 90f))
                    advanceEventTime(16L)
                }
                waitForIdle()
                onNodeWithTag("pane-rubber-band-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
                assertTrue(harness.selectedIds.isNotEmpty() && harness.selectedIds != initialSelection)
                onRoot().performMouseInput { release() }
                waitForIdle()
            } finally {
                setContent { }
                waitForIdle()
            }
        }

    /** 验证详情表头右侧全宽延伸区不会穿透触发框选、清空选择或双击返回上级，且右键正常弹出列菜单。 */
    @Test
    fun detailsHeaderRightBlankAreaIsolatesSelectionAndOpensColumnVisibilityMenu() =
        runDesktopComposeUiTest(width = 1100, height = 360) {
            val harness = ScrollbarInteractionHarness(viewMode = ViewMode.DETAILS, entryCount = 20)
            val initialSelection = setOf("file-001")
            harness.selectedIds = initialSelection
            try {
                setContent { harness.Content() }
                waitForIdle()

                val headerRightBlank = Offset(960f, 12f)

                // 1. 在表头右侧空白延伸区按下拖拽：不触发框选、不清空选中
                onRoot().performMouseInput {
                    moveTo(headerRightBlank)
                    press()
                    advanceEventTime(16L)
                    moveTo(Offset(820f, 18f))
                    advanceEventTime(16L)
                    release()
                }
                waitForIdle()
                onNodeWithTag("pane-rubber-band-PRIMARY", useUnmergedTree = true).assertDoesNotExist()
                assertEquals(initialSelection, harness.selectedIds)

                // 2. 在表头右侧空白延伸区双击：不触发返回上级目录、不清空选中
                onRoot().performMouseInput {
                    click(headerRightBlank)
                    advanceEventTime(100L)
                    click(headerRightBlank)
                }
                waitForIdle()
                assertEquals(0, harness.navigateUpCount)
                assertEquals(initialSelection, harness.selectedIds)

                // 3. 在表头右侧空白延伸区右键点击：不触发列表空白区菜单，不清空选中
                onRoot().performMouseInput {
                    rightClick(headerRightBlank)
                }
                waitForIdle()
                assertEquals(0, harness.blankContextMenuCount)
                assertEquals(initialSelection, harness.selectedIds)
            } finally {
                setContent { }
                waitForIdle()
            }
        }

    /** 验证画廊视图右侧垂直滚动条拖拽同样正常滚动且不误触框选。 */
    @Test
    fun galleryScrollbarDragScrollsWithoutTriggeringRubberBandSelection() =
        runDesktopComposeUiTest(width = 960, height = 360) {
            val harness = ScrollbarInteractionHarness(viewMode = ViewMode.GALLERY, entryCount = 60)
            val initialSelection = setOf("file-000")
            harness.selectedIds = initialSelection
            try {
                setContent { harness.Content() }
                waitForIdle()

                val scrollbarBounds = onNodeWithTag("pane-vertical-scrollbar-PRIMARY", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val scrollbarCenterX = (scrollbarBounds.left + scrollbarBounds.right) / 2f
                assertEquals(0f, scrollbarBounds.top, 0.5f, "画廊视图无表头时垂直滚动条顶部应贴齐容器顶边")

                onRoot().performMouseInput {
                    moveTo(Offset(scrollbarCenterX, scrollbarBounds.top + 10f))
                    press()
                    advanceEventTime(16L)
                    moveTo(Offset(scrollbarCenterX - 20f, scrollbarBounds.bottom - 12f))
                    advanceEventTime(16L)
                }
                waitForIdle()
                onNodeWithTag("pane-rubber-band-PRIMARY", useUnmergedTree = true).assertDoesNotExist()
                assertEquals(initialSelection, harness.selectedIds)
                onNodeWithText("00000059.png", useUnmergedTree = true).assertIsDisplayed()
                saveScreenshot(onRoot().captureToImage().toAwtImage(), "gallery-scrollbar-dragged-bottom.png")

                onRoot().performMouseInput { release() }
                waitForIdle()
                assertEquals(initialSelection, harness.selectedIds)
            } finally {
                setContent { }
                waitForIdle()
            }
        }

    private fun saveScreenshot(image: BufferedImage, fileName: String) {
        val dir = File("build/reports/tests/scrollbar-and-header").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, fileName))
    }
}

/**
 * 承载真实 `PaneEntriesContent` 列表/画廊渲染、滚动条与框选交互状态的测试夹具。
 */
private class ScrollbarInteractionHarness(
    private val viewMode: ViewMode,
    entryCount: Int,
) {
    val entries: List<VFile> = List(entryCount) { index ->
        val padded = index.toString().padStart(3, '0')
        val displayName = "${index.toString().padStart(8, '0')}.png"
        VFile(
            id = "file-$padded",
            name = displayName,
            location = "Z:/gallery/$displayName",
            parentLocation = "Z:/gallery",
            kind = VFileKind.FILE,
            sizeBytes = 2_097_152L,
            modifiedAtEpochMillis = 1_718_020_680_000L + index * 1_000L,
            hidden = false,
            capabilities = setOf(VFileCapability.RENAME),
        )
    }

    var selectedIds by mutableStateOf(emptySet<String>())
    var paneActive by mutableStateOf(true)
    var navigateUpCount = 0
    var blankContextMenuCount = 0
    var capturedHeaderBackground: Color = Color.Unspecified
    var capturedSurfaceBackground: Color = Color.Unspecified

    private val darkSettings = OnyxSettings(themeMode = OnyxThemeMode.DARK)

    @Composable
    fun Content() {
        OnyxWindowTheme(settings = darkSettings) {
            capturedHeaderBackground = LocalOnyxPalette.current.headerBackground
            capturedSurfaceBackground = LocalOnyxPalette.current.surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalOnyxPalette.current.surface),
            ) {
                PaneEntriesContent(
                    model = PaneEntriesViewModel(
                        viewMode = viewMode,
                        columns = DetailsColumn.entries,
                        columnWeights = defaultDetailsColumnWeights(),
                        hiddenColumns = emptySet(),
                        sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING),
                        selectedEntryIds = selectedIds,
                        entriesState = PaneEntriesState.Ready(entries),
                        paneActive = paneActive,
                        contextMenuVisible = false,
                        paneId = PaneId.PRIMARY,
                        fileDropTarget = null,
                        inlineEditState = null,
                        galleryItemSizeDp = 140,
                        tabId = "tab-scrollbar-test",
                    ),
                    actions = PaneEntriesActions(
                        onActivate = { paneActive = true },
                        onOpenEntry = {},
                        onToggleSort = {},
                        onResizeColumn = { _, _ -> },
                        onToggleColumnVisibility = {},
                        onSelectEntry = { id, additive, _ ->
                            selectedIds = if (additive) {
                                if (id in selectedIds) selectedIds - id else selectedIds + id
                            } else {
                                setOf(id)
                            }
                        },
                        onStartFileDrag = { _, _ -> },
                        onFileDragPositionChange = {},
                        onFileDragEnd = {},
                        onFileDropZoneChange = {},
                        onShowContextMenu = { _, _, _ -> },
                        onDismissContextMenu = {},
                        onUpdateInlineEditDraft = {},
                        onConfirmInlineEdit = {},
                        onCancelInlineEdit = {},
                        onSelectEntries = { selectedIds = it },
                        onBlankAreaContextMenu = { blankContextMenuCount += 1 },
                        onNavigateUp = { navigateUpCount += 1 },
                    ),
                    thumbnails = GalleryThumbnailServices(
                        loadThumbnail = { _, _ -> null },
                        loadArchiveThumbnail = { _, _ -> null },
                        isImageFileName = { false },
                        isArchiveFileName = { false },
                    ),
                )
            }
        }
    }
}
