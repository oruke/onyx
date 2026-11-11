@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.defaultDetailsColumnWeights
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneInlineEditMode
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证文件条目点击、高速/标准桌面双击打开与慢速点击文件名重命名状态机及真实详情/画廊 UI 交互。
 */
class FileEntryClickAndRenameUiTest {

    /** 验证计算内联编辑初始选区在不同文件扩展名、无扩展名文件、点文件与目录下的选区范围。 */
    @Test
    fun initialInlineEditSelectionExcludesFileExtensionAndKeepsDirectoryAndDotfileNames() {
        assertEquals(TextRange(0, 5), initialInlineEditSelection("notes.txt", selectWithoutExtension = true))
        assertEquals(TextRange(0, 18), initialInlineEditSelection("archive.backup.tar.gz", selectWithoutExtension = true))
        assertEquals(TextRange(0, 8), initialInlineEditSelection("Makefile", selectWithoutExtension = true))
        assertEquals(TextRange(0, 10), initialInlineEditSelection(".gitignore", selectWithoutExtension = true))
        assertEquals(TextRange(0, 9), initialInlineEditSelection(".eslintrc.json", selectWithoutExtension = true))
        assertEquals(TextRange(0, 7), initialInlineEditSelection("README.", selectWithoutExtension = true))
        assertEquals(TextRange(0, 15), initialInlineEditSelection("2.5次元的诱惑 (2024)", selectWithoutExtension = false))
        assertEquals(TextRange(0, 2), initialInlineEditSelection("TV", selectWithoutExtension = false))
    }

    /** 验证极速鼠标双击（< 40ms）与标准桌面双击（300~500ms）均准确打开条目且绝不误触重命名。 */
    @Test
    fun controllerRecognizesFastAndDesktopDoubleClicksWithoutTriggeringRename() {
        val controller = FileEntryClickController(doubleClickIntervalProvider = { 500L })

        // 1. 未选中条目上的 20ms 极速双击（覆盖原 combinedClickable 40ms 下限盲区）
        controller.onPrimaryPress(pressAt(0L, selectedBefore = false, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(10L, inLabel = true)))
        controller.onPrimaryPress(pressAt(25L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK,
            controller.onPrimaryRelease(releaseAt(35L, inLabel = true)),
        )

        // 2. 已选中条目上的 380ms 桌面标准双击（覆盖原 combinedClickable 300ms 超时盲区）
        controller.onPrimaryPress(pressAt(1_200L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.SCHEDULE_RENAME,
            controller.onPrimaryRelease(releaseAt(1_230L, inLabel = true)),
        )
        controller.onPrimaryPress(pressAt(1_580L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK,
            controller.onPrimaryRelease(releaseAt(1_610L, inLabel = true)),
        )
    }

    /** 验证极速三连击与四连击被完全抑制且不触发打开或改名，停顿后恢复正常双击与慢速重命名。 */
    @Test
    fun controllerSuppressesTripleAndQuadrupleClicksThenRecoversAfterPause() {
        val controller = FileEntryClickController(doubleClickIntervalProvider = { 500L })

        // 1. 第 1 击选中，第 2 击双击打开
        controller.onPrimaryPress(pressAt(0L, selectedBefore = false, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(20L, inLabel = true)))
        controller.onPrimaryPress(pressAt(180L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK,
            controller.onPrimaryRelease(releaseAt(200L, inLabel = true)),
        )

        // 2. 紧随其后的第 3 击与第 4 击（快速连击）：直接返回 NONE，不二次打开也不改名
        controller.onPrimaryPress(pressAt(320L, selectedBefore = true, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(340L, inLabel = true)))
        controller.onPrimaryPress(pressAt(460L, selectedBefore = true, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(480L, inLabel = true)))

        // 3. 停顿 >= 700ms 后慢速单击已选中项的文件名：恢复 SCHEDULE_RENAME
        controller.onPrimaryPress(pressAt(1_500L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.SCHEDULE_RENAME,
            controller.onPrimaryRelease(releaseAt(1_530L, inLabel = true)),
        )

        // 4. 紧接第 2 击（在双击时间窗口内）：恢复 OPEN_DOUBLE_CLICK
        controller.onPrimaryPress(pressAt(1_700L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK,
            controller.onPrimaryRelease(releaseAt(1_720L, inLabel = true)),
        )
    }

    /** 验证失败双击临界区、非文件名区域、长按与手抖微移取消均不会误触内联重命名。 */
    @Test
    fun controllerRejectsNearMissDoubleClickNonLabelClickLongHoldAndDraggedFirstClick() {
        val controller = FileEntryClickController(doubleClickIntervalProvider = { 500L })

        // 1. 刚选中后间隔 560ms 的“慢半拍双击”（处于 500~700ms 缓冲带）：保持选中，不误触重命名
        controller.onPrimaryPress(pressAt(0L, selectedBefore = false, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(30L, inLabel = true)))
        controller.onPrimaryPress(pressAt(560L, selectedBefore = true, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(590L, inLabel = true)))

        // 2. 点击非文件名文本区域（图标、空白列、缩略图）：不重命名，但双击仍可打开
        controller.onPrimaryPress(pressAt(2_000L, selectedBefore = true, inLabel = false))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(2_030L, inLabel = false)))
        controller.onPrimaryPress(pressAt(2_250L, selectedBefore = true, inLabel = false))
        assertEquals(
            FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK,
            controller.onPrimaryRelease(releaseAt(2_280L, inLabel = false)),
        )

        // 3. 长按超过 350ms 后原地松开：不触发重命名
        controller.onPrimaryPress(pressAt(4_000L, selectedBefore = true, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(4_420L, inLabel = true)))

        // 4. 第 1 击手抖转入拖拽取消，120ms 后第 2 击落在已选中文件名上：因处于保护期内不误触重命名
        controller.onPrimaryPress(pressAt(6_000L, selectedBefore = false, inLabel = true))
        controller.onPointerDraggedOrCancelled()
        controller.onPrimaryPress(pressAt(6_120L, selectedBefore = true, inLabel = true))
        assertEquals(FileEntryReleaseOutcome.NONE, controller.onPrimaryRelease(releaseAt(6_150L, inLabel = true)))

        // 5. 经过充足稳定期（>= 700ms）后刻意短按已选中条目的文件名：正常触发慢速重命名
        controller.onPrimaryPress(pressAt(7_000L, selectedBefore = true, inLabel = true))
        assertEquals(
            FileEntryReleaseOutcome.SCHEDULE_RENAME,
            controller.onPrimaryRelease(releaseAt(7_040L, inLabel = true)),
        )
    }

    /** 在真实详情列表 UI 中验证高速/标准双击、右侧空白区点击不误触改名，以及慢速点击文件名正常改名。 */
    @Test
    fun detailsViewDoubleClickNeverTriggersRenameWhileSlowLabelClickEntersRename() =
        runDesktopComposeUiTest(width = 960, height = 420) {
            val harness = EntryInteractionHarness(ViewMode.DETAILS)
            mainClock.autoAdvance = false
            try {
                setContent { harness.Content() }
                mainClock.advanceTimeBy(100)
                waitForIdle()

                // 1. 对未选中短目录名 "TV" 执行 20ms 极速双击：立即打开且等待 1.5s 后绝不进入重命名
                onNodeWithText("TV", useUnmergedTree = true).performMouseInput {
                    timedDoubleClick(center, gapMillis = 20L)
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertEquals(listOf("dir-tv"), harness.openedIds)
                assertNull(harness.inlineEditState)

                // 2. 对已选中的 "TV" 文件名执行 380ms 桌面标准双击：取消挂起改名并正常打开目录
                onNodeWithText("TV", useUnmergedTree = true).performMouseInput {
                    timedDoubleClick(center, gapMillis = 380L)
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertEquals(listOf("dir-tv", "dir-tv"), harness.openedIds)
                assertNull(harness.inlineEditState)

                // 3. 在已选中行右侧空白元数据区域单击：不触发重命名；在该空白区域双击：正常打开
                val tvBounds = onNodeWithText("TV", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val blankRowOffset = Offset(x = tvBounds.right + 160f, y = tvBounds.center.y)
                onRoot().performMouseInput {
                    moveTo(blankRowOffset)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertNull(harness.inlineEditState)

                onRoot().performMouseInput {
                    timedDoubleClick(blankRowOffset, gapMillis = 180L)
                }
                mainClock.advanceTimeBy(1_200)
                waitForIdle()
                assertEquals(3, harness.openedIds.size)
                assertNull(harness.inlineEditState)

                // 4. 先单击选中 "notes.txt"，停顿 900ms 后再次单击其文件名文本并等待 1.1s：进入重命名
                onNodeWithText("notes.txt", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(900)
                waitForIdle()
                onNodeWithText("notes.txt", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(1_100)
                waitForIdle()
                assertEquals("file-notes", harness.inlineEditState?.targetEntryId)
                val notesNode = onNodeWithText("notes.txt", useUnmergedTree = true)
                notesNode.assertIsDisplayed()
                assertEquals(
                    TextRange(0, 5),
                    notesNode.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
                )
                saveScreenshot(onRoot().captureToImage().toAwtImage(), "double-click-rename-details.png")
                notesNode.performTextInput("memo")
                mainClock.advanceTimeBy(100)
                waitForIdle()
                onNodeWithText("memo.txt", useUnmergedTree = true).performKeyInput {
                    pressKey(Key.Enter)
                }
                mainClock.advanceTimeBy(200)
                waitForIdle()
                assertEquals("file-notes" to "memo.txt", harness.lastConfirmedRename)
            } finally {
                mainClock.autoAdvance = true
                setContent { }
                waitForIdle()
            }
        }

    /** 在真实画廊视图 UI 中验证双击与点击缩略图不误触重命名，慢速点击底部文件名正常进入重命名。 */
    @Test
    fun galleryViewDoubleClickAndThumbnailClickNeverTriggerRenameWhileLabelClickDoes() =
        runDesktopComposeUiTest(width = 960, height = 420) {
            val harness = EntryInteractionHarness(ViewMode.GALLERY)
            mainClock.autoAdvance = false
            try {
                setContent { harness.Content() }
                mainClock.advanceTimeBy(100)
                waitForIdle()

                // 1. 对画廊项 "TV" 执行 360ms 标准双击：打开目录且等待 1.5s 后绝不进入重命名
                onNodeWithText("TV", useUnmergedTree = true).performMouseInput {
                    timedDoubleClick(center, gapMillis = 360L)
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertEquals(listOf("dir-tv"), harness.openedIds)
                assertNull(harness.inlineEditState)

                // 2. 在已选中画廊项上方缩略图图标区域慢速单击：不触发重命名
                val labelBounds = onNodeWithText("TV", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val thumbnailCenter = Offset(x = labelBounds.center.x, y = labelBounds.top - 55f)
                onRoot().performMouseInput {
                    moveTo(thumbnailCenter)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertNull(harness.inlineEditState)

                // 3. 在已选中画廊项底部文件名文本上慢速单击并等待 1.1s：准确进入内联重命名
                onNodeWithText("TV", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(1_100)
                waitForIdle()
                assertEquals("dir-tv", harness.inlineEditState?.targetEntryId)
                val tvNode = onNodeWithText("TV", useUnmergedTree = true)
                val tvBounds = tvNode.fetchSemanticsNode().boundsInRoot
                assertTrue(tvBounds.height < 48f)
                assertTrue(tvBounds.top >= labelBounds.top - 12f)
                assertEquals(
                    TextRange(0, 2),
                    tvNode.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
                )
                tvNode.performKeyInput { pressKey(Key.Escape) }
                mainClock.advanceTimeBy(100)
                waitForIdle()
                assertNull(harness.inlineEditState)

                // 4. 对画廊中的文件 "notes.txt" 单击选中、停顿 900ms 后慢速单击其文件名进入重命名
                onNodeWithText("notes.txt", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(900)
                waitForIdle()
                onNodeWithText("notes.txt", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(1_100)
                waitForIdle()
                assertEquals("file-notes", harness.inlineEditState?.targetEntryId)
                val notesNode = onNodeWithText("notes.txt", useUnmergedTree = true)
                val notesBounds = notesNode.fetchSemanticsNode().boundsInRoot
                assertTrue(notesBounds.height < 48f)
                assertEquals(
                    TextRange(0, 5),
                    notesNode.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
                )
                saveScreenshot(onRoot().captureToImage().toAwtImage(), "double-click-rename-gallery.png")
                notesNode.performTextInput("clip")
                mainClock.advanceTimeBy(100)
                waitForIdle()
                onNodeWithText("clip.txt", useUnmergedTree = true).performKeyInput {
                    pressKey(Key.Enter)
                }
                mainClock.advanceTimeBy(200)
                waitForIdle()
                assertEquals("file-notes" to "clip.txt", harness.lastConfirmedRename)
            } finally {
                mainClock.autoAdvance = true
                setContent { }
                waitForIdle()
            }
        }

    /** 验证双面板模式下点击非活动面板中的单选条目仅激活面板而不触发重命名，而双击仍能正常打开。 */
    @Test
    fun inactivePaneClickActivatesWithoutRenamingWhileDoubleClickStillOpens() =
        runDesktopComposeUiTest(width = 960, height = 420) {
            val harness = EntryInteractionHarness(ViewMode.DETAILS)
            harness.selectedIds = setOf("dir-tv")
            harness.paneActive = false
            mainClock.autoAdvance = false
            try {
                setContent { harness.Content() }
                mainClock.advanceTimeBy(100)
                waitForIdle()

                // 1. 单击非活跃面板中已单选的 "TV"：激活面板，但不应在 1500ms 后误触内联重命名
                onNodeWithText("TV", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                    press()
                    advanceEventTime(25L)
                    release()
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertTrue(harness.paneActive)
                assertNull(harness.inlineEditState)

                // 2. 再次重置为非活跃态，并在 "TV" 上执行双击：激活面板并直接打开目录，不触发重命名
                harness.paneActive = false
                onNodeWithText("TV", useUnmergedTree = true).performMouseInput {
                    timedDoubleClick(center, gapMillis = 220L)
                }
                mainClock.advanceTimeBy(1_500)
                waitForIdle()
                assertTrue(harness.paneActive)
                assertEquals(listOf("dir-tv"), harness.openedIds)
                assertNull(harness.inlineEditState)
            } finally {
                mainClock.autoAdvance = true
                setContent { }
                waitForIdle()
            }
        }

    private fun pressAt(millis: Long, selectedBefore: Boolean, inLabel: Boolean): FileEntryPressRequest =
        FileEntryPressRequest(
            pressMillis = millis,
            position = Offset(20f, 10f),
            wasSingleSelectedBeforePress = selectedBefore,
            additiveOrRange = false,
            inRenameLabel = inLabel,
            doubleClickSlopPx = 12f,
        )

    private fun releaseAt(millis: Long, inLabel: Boolean): FileEntryReleaseRequest =
        FileEntryReleaseRequest(
            releaseMillis = millis,
            inRenameLabel = inLabel,
            singleClickToOpen = false,
            canStillRename = true,
        )

    private fun MouseInjectionScope.timedDoubleClick(target: Offset, gapMillis: Long) {
        moveTo(target)
        press()
        advanceEventTime(20L)
        release()
        advanceEventTime(gapMillis)
        press()
        advanceEventTime(20L)
        release()
    }

    private fun saveScreenshot(image: java.awt.image.BufferedImage, fileName: String) {
        val dir = File("build/reports/tests/double-click-rename").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, fileName))
    }
}

/** 承载真实 `PaneEntriesContent` 选择、打开与内联重命名状态流转的测试夹具。 */
private class EntryInteractionHarness(private val viewMode: ViewMode) {
    val entries = listOf(
        VFile(
            id = "dir-hash",
            name = "#",
            location = "D:/video/#",
            parentLocation = "D:/video",
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 1_762_785_480_000L,
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN, VFileCapability.RENAME),
        ),
        VFile(
            id = "dir-tv",
            name = "TV",
            location = "D:/video/TV",
            parentLocation = "D:/video",
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 1_762_785_780_000L,
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN, VFileCapability.RENAME),
        ),
        VFile(
            id = "file-notes",
            name = "notes.txt",
            location = "D:/video/notes.txt",
            parentLocation = "D:/video",
            kind = VFileKind.FILE,
            sizeBytes = 2_048L,
            modifiedAtEpochMillis = 1_762_786_000_000L,
            hidden = false,
            capabilities = setOf(VFileCapability.RENAME),
        ),
    )

    var selectedIds by mutableStateOf(emptySet<String>())
    var paneActive by mutableStateOf(true)
    var inlineEditState by mutableStateOf<PaneInlineEditState?>(null)
    var lastConfirmedRename: Pair<String, String>? = null
    val openedIds = mutableListOf<String>()

    private val darkSettings = OnyxSettings(themeMode = OnyxThemeMode.DARK)

    @androidx.compose.runtime.Composable
    fun Content() {
        OnyxWindowTheme(settings = darkSettings) {
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
                        inlineEditState = inlineEditState,
                        galleryItemSizeDp = 140,
                        tabId = "tab-1",
                    ),
                    actions = buildActions(),
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

    private fun buildActions(): PaneEntriesActions = PaneEntriesActions(
        onActivate = { paneActive = true },
        onOpenEntry = { entry ->
            inlineEditState = null
            openedIds += entry.id
        },
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
        onUpdateInlineEditDraft = { draft ->
            inlineEditState = inlineEditState?.copy(draftName = draft)
        },
        onConfirmInlineEdit = {
            inlineEditState?.let { state ->
                state.targetEntryId?.let { id ->
                    lastConfirmedRename = id to state.draftName
                }
            }
            inlineEditState = null
        },
        onCancelInlineEdit = { inlineEditState = null },
        onBeginRename = {
            val targetId = selectedIds.singleOrNull()
            val target = entries.firstOrNull { it.id == targetId }
            if (target != null && inlineEditState == null) {
                inlineEditState = PaneInlineEditState(
                    mode = PaneInlineEditMode.RENAME,
                    targetEntryId = target.id,
                    draftName = target.name,
                )
            }
        },
        onSelectEntries = { selectedIds = it },
    )
}
