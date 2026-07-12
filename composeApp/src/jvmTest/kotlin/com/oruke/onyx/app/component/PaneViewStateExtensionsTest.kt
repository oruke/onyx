package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** 详情视图状态转换测试。 */
class PaneViewStateExtensionsTest {
    /** 验证拖动分隔线会等量调整相邻两列。 */
    @Test
    fun resizeDetailsColumnPreservesAdjacentTotalWidth() {
        val initial = createDefaultPaneTabState(
            id = "tab-id",
            title = "Workspace",
            location = "C:\\workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val resized = initial.withResizedDetailsColumnState(
            column = DetailsColumn.NAME,
            nextColumn = DetailsColumn.TYPE,
            deltaWeight = 20f,
        )

        assertEquals(320f, resized.detailsColumnWeights.getValue(DetailsColumn.NAME))
        assertEquals(60f, resized.detailsColumnWeights.getValue(DetailsColumn.TYPE))
    }

    /** 验证相邻列达到最小宽度后不会继续被压缩。 */
    @Test
    fun resizeDetailsColumnClampsAtAdjacentMinimumWidth() {
        val initial = createDefaultPaneTabState(
            id = "tab-id",
            title = "Workspace",
            location = "C:\\workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val resized = initial.withResizedDetailsColumnState(
            column = DetailsColumn.NAME,
            nextColumn = DetailsColumn.TYPE,
            deltaWeight = 200f,
        )

        assertEquals(340f, resized.detailsColumnWeights.getValue(DetailsColumn.NAME))
        assertEquals(MIN_DETAILS_COLUMN_WIDTH, resized.detailsColumnWeights.getValue(DetailsColumn.TYPE))
    }
}
