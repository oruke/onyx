package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** 详情视图状态转换测试。 */
class PaneViewStateExtensionsTest {
    /** 验证拖动分隔线只调整当前列，并使总宽度同步变化。 */
    @Test
    fun resizeDetailsColumnChangesOnlyCurrentColumn() {
        val initial = createDefaultPaneTabState(
            id = "tab-id",
            title = "Workspace",
            location = "C:\\workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val resized = initial.withResizedDetailsColumnState(
            column = DetailsColumn.NAME,
            deltaWeight = 20f,
        )

        assertEquals(320f, resized.detailsColumnWeights.getValue(DetailsColumn.NAME))
        assertEquals(80f, resized.detailsColumnWeights.getValue(DetailsColumn.TYPE))
        assertEquals(680f, resized.detailsColumnWeights.values.sum())
    }

    /** 验证当前列达到最小宽度后不会继续被压缩。 */
    @Test
    fun resizeDetailsColumnClampsAtMinimumWidth() {
        val initial = createDefaultPaneTabState(
            id = "tab-id",
            title = "Workspace",
            location = "C:\\workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val resized = initial.withResizedDetailsColumnState(
            column = DetailsColumn.NAME,
            deltaWeight = 200f,
        )

        assertEquals(500f, resized.detailsColumnWeights.getValue(DetailsColumn.NAME))
        assertEquals(80f, resized.detailsColumnWeights.getValue(DetailsColumn.TYPE))
    }

    /** 验证最后一列也可以通过尾列手柄独立调整。 */
    @Test
    fun resizeDetailsColumnAdjustsLastColumn() {
        val initial = createDefaultPaneTabState(
            id = "tab-id",
            title = "Workspace",
            location = "C:\\workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val resized = initial.withResizedDetailsColumnState(
            column = DetailsColumn.MODIFIED,
            deltaWeight = 25f,
        )

        assertEquals(205f, resized.detailsColumnWeights.getValue(DetailsColumn.MODIFIED))
        assertEquals(300f, resized.detailsColumnWeights.getValue(DetailsColumn.NAME))
    }

    /** 验证最后一列向左拖动时也遵守最小宽度。 */
    @Test
    fun resizeDetailsColumnClampsLastColumnAtMinimumWidth() {
        val initial = createDefaultPaneTabState(
            id = "tab-id",
            title = "Workspace",
            location = "C:\\workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val resized = initial.withResizedDetailsColumnState(
            column = DetailsColumn.MODIFIED,
            deltaWeight = -200f,
        )

        assertEquals(MIN_DETAILS_COLUMN_WIDTH, resized.detailsColumnWeights.getValue(DetailsColumn.MODIFIED))
    }
}
