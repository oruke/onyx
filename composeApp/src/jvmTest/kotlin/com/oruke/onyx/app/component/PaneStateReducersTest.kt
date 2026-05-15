package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.PaneInlineEditMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面板状态 reducer 与扩展函数测试。
 */
class PaneStateReducersTest {
    /**
     * 校验导航状态会维护历史栈并在跳转时进入加载态。
     *
     * @return 无返回值。
     */
    @Test
    fun navigationKeepsHistoryAndEntersLoadingState() {
        val tab = tab(location = "local://home")

        val opened = tab.navigateToState(
            location = "local://home/docs",
            title = "docs",
            recordHistory = true,
        )
        val back = opened.navigateBackState(previousTitle = "home")
        val forward = back.navigateForwardState(nextTitle = "docs")

        assertEquals(listOf("local://home"), opened.backStack)
        assertEquals("local://home/docs", opened.location)
        assertEquals(PaneEntriesState.Loading, opened.entriesState)
        assertEquals("local://home", back.location)
        assertEquals(listOf("local://home/docs"), back.forwardStack)
        assertEquals("local://home/docs", forward.location)
        assertTrue(forward.canGoBack)
    }

    /**
     * 校验选择状态支持单选、范围选择和键盘移动。
     *
     * @return 无返回值。
     */
    @Test
    fun selectionReducerHandlesRangeAndKeyboardMove() {
        val entries = listOf(file("a"), file("b"), file("c"), file("d"))
        val loaded = tab().withLoadedEntries(entries, focusEntry = null)

        val selected = loaded.selectEntryState(
            entries = entries,
            entryId = "b",
            additive = false,
            range = false,
        )
        val ranged = selected.selectEntryState(
            entries = entries,
            entryId = "d",
            additive = false,
            range = true,
        )
        val moved = ranged.moveSelectionState(
            entries = entries,
            offset = -1,
            extendSelection = false,
        )

        assertEquals(setOf("b"), selected.selectedEntryIds)
        assertEquals(setOf("b", "c", "d"), ranged.selectedEntryIds)
        assertEquals("b", ranged.selectionAnchorId)
        assertEquals("d", ranged.selectionFocusId)
        assertEquals(setOf("c"), moved.selectedEntryIds)
        assertEquals("c", moved.selectionFocusId)
    }

    /**
     * 校验过滤、排序和视图设置只更新面板展示状态。
     *
     * @return 无返回值。
     */
    @Test
    fun viewReducersNormalizeFilterAndClampGallerySize() {
        val updated = tab()
            .withFilterQueryState("  report  ")
            .withToggledSortState(DetailsColumn.SIZE)
            .withToggledColumnVisibilityState(DetailsColumn.TYPE)
            .withGalleryItemSizeState(999)
            .withViewModeState(ViewMode.GALLERY)

        assertEquals("report", updated.filterQuery)
        assertEquals(DetailsColumn.SIZE, updated.detailsSort.column)
        assertEquals(setOf(DetailsColumn.TYPE), updated.hiddenColumns)
        assertEquals(320, updated.galleryItemSizeDp)
        assertEquals(ViewMode.GALLERY, updated.viewMode)
    }

    /**
     * 校验内联编辑会产生命令语义并在无效输入时清理状态。
     *
     * @return 无返回值。
     */
    @Test
    fun inlineEditProducesOperationAndClearsInvalidDraft() {
        val entry = file("old.txt")
        val editing = tab()
            .withLoadedEntries(listOf(entry), focusEntry = null)
            .beginRenameInlineEdit(entry)
            .withInlineEditDraft(" new.txt ")

        val result = editing.confirmInlineEditState(listOf(entry))
        val operation = assertIs<InlineEditOperation.Rename>(result.operation)
        val blankResult = editing.withInlineEditDraft(" ").confirmInlineEditState(listOf(entry))

        assertEquals(entry, operation.entry)
        assertEquals("new.txt", operation.targetName)
        assertNull(blankResult.operation)
        assertNull(blankResult.tab.inlineEditState)
    }

    /**
     * 校验标签栈 reducer 会保持活动标签与顺序一致。
     *
     * @return 无返回值。
     */
    @Test
    fun tabStackReducersKeepActiveTabAndOrder() {
        val first = tab(id = "first")
        val second = tab(id = "second")
        val third = tab(id = "third")

        val created = listOf(first).withCreatedTab(second)
        val moved = listOf(first, second, third).withMovedTab(
            activeTabId = "second",
            tabId = "first",
            targetIndex = 2,
        )
        val closed = listOf(first, second, third).withClosedTab(
            activeTabId = "first",
            tabId = "first",
        )
        val attached = listOf(first, third).withAttachedTab(second, targetIndex = 1)

        assertEquals("second", created.activeTab.id)
        assertEquals(listOf("second", "first", "third"), moved?.tabs?.map { tab -> tab.id })
        assertEquals("second", moved?.activeTab?.id)
        assertEquals("second", closed?.activeTab?.id)
        assertEquals(listOf("first", "second", "third"), attached.tabs.map { tab -> tab.id })
    }

    private companion object {
        /**
         * 构造测试用面板标签状态。
         *
         * @param id 标签 ID。
         * @param location 标签位置。
         * @return 面板标签状态。
         */
        fun tab(
            id: String = "tab",
            location: String = "local://home",
        ): PaneTabState {
            return createDefaultPaneTabState(
                id = id,
                title = id,
                location = location,
                defaultViewMode = ViewMode.DETAILS,
            )
        }

        /**
         * 构造测试用文件条目。
         *
         * @param name 文件名。
         * @return 文件条目。
         */
        fun file(name: String): VFile {
            return VFile(
                id = name,
                name = name,
                location = "local://home/$name",
                parentLocation = "local://home",
                kind = VFileKind.FILE,
                sizeBytes = 12L,
                modifiedAtEpochMillis = null,
                hidden = false,
                capabilities = setOf(VFileCapability.READ_CONTENT),
            )
        }
    }
}
