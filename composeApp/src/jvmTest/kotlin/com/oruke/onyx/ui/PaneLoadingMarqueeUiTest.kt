@file:OptIn(ExperimentalTestApi::class)

package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.defaultDetailsColumnWeights
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证文件面板加载态移除居中“正在加载...”胶囊，且顶部不确定进度条以单向跑马灯（从左向右无缝环绕）连续流动。
 */
class PaneLoadingMarqueeUiTest {

    /**
     * 验证跑马灯分段算法在相位单调递增时始终向右推进，越过右边界时分裂为左端驶入段与右端收尾段且总高亮宽度恒定。
     */
    @Test
    fun marqueeSpansAdvanceMonotonicallyAndWrapSeamlessly() {
        assertTrue(computeMarqueeBarSpans(trackWidthPx = 0f, phase = 0.3f).isEmpty())

        val trackWidth = 1000f
        val fraction = 0.28f
        val expectedSegmentWidth = 280f

        val atStart = computeMarqueeBarSpans(trackWidthPx = trackWidth, segmentFraction = fraction, phase = 0f)
        assertEquals(1, atStart.size)
        assertApprox(0f, atStart.single().startPx)
        assertApprox(expectedSegmentWidth, atStart.single().widthPx)

        val atQuarter = computeMarqueeBarSpans(trackWidthPx = trackWidth, segmentFraction = fraction, phase = 0.25f)
        assertEquals(1, atQuarter.size)
        assertApprox(250f, atQuarter.single().startPx)
        assertApprox(expectedSegmentWidth, atQuarter.single().widthPx)

        val atHalf = computeMarqueeBarSpans(trackWidthPx = trackWidth, segmentFraction = fraction, phase = 0.50f)
        assertEquals(1, atHalf.size)
        assertApprox(500f, atHalf.single().startPx)
        assertApprox(expectedSegmentWidth, atHalf.single().widthPx)

        // 右端越界环绕态（phase = 0.85）：右侧剩余 150px 正在驶出，左侧同时驶入 130px，总长仍为 280px
        val atWrap = computeMarqueeBarSpans(trackWidthPx = trackWidth, segmentFraction = fraction, phase = 0.85f)
        assertEquals(2, atWrap.size)
        assertApprox(0f, atWrap[0].startPx)
        assertApprox(130f, atWrap[0].widthPx)
        assertApprox(850f, atWrap[1].startPx)
        assertApprox(150f, atWrap[1].widthPx)
        assertApprox(expectedSegmentWidth, atWrap.sumOf { it.widthPx.toDouble() }.toFloat())

        // 周期重启边界（phase = 0.999 -> phase = 1.0/0.0）无跳变
        val justBeforeRestart = computeMarqueeBarSpans(
            trackWidthPx = trackWidth,
            segmentFraction = fraction,
            phase = 0.999f,
        )
        assertApprox(0f, justBeforeRestart.first().startPx)
        assertApprox(279f, justBeforeRestart.first().widthPx, tolerance = 1.5f)

        val restarted = computeMarqueeBarSpans(trackWidthPx = trackWidth, segmentFraction = fraction, phase = 1.0f)
        assertEquals(1, restarted.size)
        assertApprox(0f, restarted.single().startPx)
        assertApprox(expectedSegmentWidth, restarted.single().widthPx)
    }

    /**
     * 通过桌面端真实 Skia 多帧渲染验证：
     * 1. 文件面板加载态（详情与画廊）不再渲染中间悬浮的“正在加载...”状态胶囊，详情表头保持可点击交互；
     * 2. 顶部进度条按单向跑马灯向右推进、在右边缘无缝环绕至左边缘，并在进入第二周期时继续从左向右流动而非反向弹回。
     */
    @Test
    fun paneEntriesLoadingOmitsCenteredBadgeAndStreamsMarqueeUnidirectionally() =
        runDesktopComposeUiTest(width = 960, height = 420) {
            val reportDir = File("build/reports/ui-verification").apply { mkdirs() }
            val darkSettings = OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                themePreset = OnyxThemePreset.DEFAULT,
            )
            val viewMode = mutableStateOf(ViewMode.DETAILS)
            val toggledColumns = mutableListOf<DetailsColumn>()
            val actions = PaneEntriesActions(
                onActivate = {},
                onOpenEntry = {},
                onToggleSort = { toggledColumns.add(it) },
                onResizeColumn = { _, _ -> },
                onToggleColumnVisibility = {},
                onSelectEntry = { _, _, _ -> },
                onStartFileDrag = { _, _ -> },
                onFileDragPositionChange = {},
                onFileDragEnd = {},
                onFileDropZoneChange = {},
                onShowContextMenu = { _, _, _ -> },
                onDismissContextMenu = {},
                onUpdateInlineEditDraft = {},
                onConfirmInlineEdit = {},
                onCancelInlineEdit = {},
            )

            mainClock.autoAdvance = false
            try {
                setContent {
                    OnyxWindowTheme(settings = darkSettings) {
                        val palette = LocalOnyxPalette.current
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.surface),
                        ) {
                            PaneEntriesLoading(
                                model = PaneEntriesViewModel(
                                    viewMode = viewMode.value,
                                    columns = DetailsColumn.entries,
                                    columnWeights = defaultDetailsColumnWeights(),
                                    hiddenColumns = emptySet(),
                                    sort = DetailsSort(
                                        column = DetailsColumn.NAME,
                                        direction = SortDirection.ASCENDING,
                                    ),
                                    selectedEntryIds = emptySet(),
                                    entriesState = PaneEntriesState.Loading,
                                    paneActive = true,
                                    contextMenuVisible = false,
                                    paneId = PaneId.PRIMARY,
                                    fileDropTarget = null,
                                    inlineEditState = null,
                                    galleryItemSizeDp = 140,
                                ),
                                actions = actions,
                            )
                        }
                    }
                }

                // 推进首帧以初始化 rememberInfiniteTransition 起始时间戳（playTime = 0ms）
                mainClock.advanceTimeByFrame()
                waitForIdle()

                onNodeWithTag("pane-entries-loading-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
                val barNode = onNodeWithTag("pane-entries-loading-bar-PRIMARY", useUnmergedTree = true)
                barNode.assertIsDisplayed()

                // 断言正中央不再渲染任何语言的“正在加载...”胶囊
                onNodeWithText("正在加载...").assertDoesNotExist()
                onNodeWithText("Loading...").assertDoesNotExist()
                onNodeWithText("読み込み中...").assertDoesNotExist()

                // 帧 0（初始帧，相位 ~0.00..0.02）：高亮滑块贴近左端
                ImageIO.write(
                    onRoot().captureToImage().toAwtImage(),
                    "png",
                    File(reportDir, "loading-marquee-frame0-start.png"),
                )
                val barFrame0 = detectHighlightedColumns(barNode.captureToImage().toAwtImage())
                assertTrue(barFrame0.isNotEmpty(), "起始帧跑马灯高亮像素不应为空")
                assertTrue(barFrame0.first() <= 32, "起始帧高亮起点应位于左侧，实际=${barFrame0.first()}")
                assertTrue(barFrame0.last() < 360, "起始帧高亮终点不应越过中段，实际=${barFrame0.last()}")

                // 帧 1（推进 448ms，相位 ~0.41）：滑块向右推进至轨道中部，左右两端均为暗底轨
                mainClock.advanceTimeBy(448L)
                waitForIdle()
                ImageIO.write(
                    onRoot().captureToImage().toAwtImage(),
                    "png",
                    File(reportDir, "loading-marquee-frame1-mid.png"),
                )
                val barFrame1 = detectHighlightedColumns(barNode.captureToImage().toAwtImage())
                assertTrue(
                    barFrame1.first() > barFrame0.first() + 300,
                    "跑马灯应向右推进，frame0Start=${barFrame0.first()}, frame1Start=${barFrame1.first()}",
                )
                assertTrue(barFrame1.last() < 860, "中段帧尚未触达右边缘，实际=${barFrame1.last()}")

                // 帧 2（再推进 480ms，总约 928ms，相位 ~0.85）：滑块右边缘驶出，左边缘同步环绕驶入
                mainClock.advanceTimeBy(480L)
                waitForIdle()
                ImageIO.write(
                    onRoot().captureToImage().toAwtImage(),
                    "png",
                    File(reportDir, "loading-marquee-frame2-wrap.png"),
                )
                val barImageWrap = barNode.captureToImage().toAwtImage()
                val barFrame2 = detectHighlightedColumns(barImageWrap)
                assertTrue(barFrame2.first() <= 16, "环绕帧左边缘应同步驶入高亮，实际最小列=${barFrame2.first()}")
                assertTrue(
                    barFrame2.last() >= barImageWrap.width - 16,
                    "环绕帧右边缘应仍在收尾驶出，实际最大列=${barFrame2.last()}, width=${barImageWrap.width}",
                )
                val midColumnHighlighted = (barImageWrap.width / 2) in barFrame2.toSet()
                assertTrue(!midColumnHighlighted, "环绕帧轨道正中央应为未点亮底轨")

                // 帧 3（再推进 496ms，总约 1424ms > 单周期 1100ms，处于第二周期相位 ~0.30）：
                // 单向 Restart 跑马灯此时已从左侧重新向右行进至左中段（中心约 420px）；
                // 若是旧版 Reverse 来回跑，此处会处于反向回退的右半区（中心约 800px）。
                mainClock.advanceTimeBy(496L)
                waitForIdle()
                ImageIO.write(
                    onRoot().captureToImage().toAwtImage(),
                    "png",
                    File(reportDir, "loading-marquee-frame3-next-cycle.png"),
                )
                val barFrame3 = detectHighlightedColumns(barNode.captureToImage().toAwtImage())
                val centerFrame3 = barFrame3.average()
                assertTrue(
                    centerFrame3 in 260.0..560.0,
                    "第二周期 30% 相位处跑马灯中心应位于左中段（单向重启而非反向弹回），实际中心=$centerFrame3",
                )

                // 验证加载期间详情表头仍保持可点击排序交互
                val nameHeaderText = if (onAllNodesWithText("名称").fetchSemanticsNodes().isNotEmpty()) {
                    "名称"
                } else {
                    "Name"
                }
                onNodeWithText(nameHeaderText).performClick()
                mainClock.advanceTimeByFrame()
                assertEquals(listOf(DetailsColumn.NAME), toggledColumns)

                // 切换至画廊视图验证同样无居中胶囊且保留顶部跑马灯
                runOnUiThread { viewMode.value = ViewMode.GALLERY }
                mainClock.advanceTimeByFrame()
                waitForIdle()
                onNodeWithText("正在加载...").assertDoesNotExist()
                onNodeWithText("Loading...").assertDoesNotExist()
                onNodeWithTag("pane-entries-loading-bar-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
                ImageIO.write(
                    onRoot().captureToImage().toAwtImage(),
                    "png",
                    File(reportDir, "loading-marquee-gallery.png"),
                )
            } finally {
                setContent { }
                mainClock.autoAdvance = true
                waitForIdle()
            }
        }

    /**
     * 扫描进度条位图的每一列，提取蓝色通道显著高于背景灰轨的高亮列索引列表。
     */
    private fun detectHighlightedColumns(image: BufferedImage): List<Int> {
        val midY = (image.height / 2).coerceIn(0, image.height - 1)
        val highlighted = mutableListOf<Int>()
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, midY)
            val red = (rgb shr 16) and 0xFF
            val blue = rgb and 0xFF
            if (blue - red >= 60 && blue >= 120) {
                highlighted.add(x)
            }
        }
        return highlighted
    }

    private fun assertApprox(expected: Float, actual: Float, tolerance: Float = 0.5f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "期望值 $expected，实际值 $actual（容差 $tolerance）",
        )
    }
}
