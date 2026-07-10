package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.TabSnapshot
import com.oruke.onyx.core.model.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 面板标签会话恢复测试。
 */
class PaneTabSessionRestoreTest {
    /**
     * 校验旧会话中的百分号中文路径会在恢复时统一规范化。
     *
     * @return 无返回值。
     */
    @Test
    fun normalizesEncodedRemoteLocationsWhenRestoringSession() {
        val encodedDirectory = "smb://host/share/%E7%BB%BC%E8%89%BA/"
        val snapshot = TabSnapshot(
            id = "tab-id",
            location = "$encodedDirectory%E9%9F%B3%E4%B9%90/",
            detailsColumns = defaultDetailsColumns(),
            detailsColumnWeights = defaultDetailsColumnWeights(),
            detailsSort = DetailsSort(
                column = defaultDetailsColumns().first(),
                direction = SortDirection.ASCENDING,
            ),
            showHiddenItems = false,
            viewMode = ViewMode.DETAILS,
            filterQuery = "",
            backStack = listOf(encodedDirectory),
            forwardStack = listOf("${encodedDirectory}%E7%94%B5%E5%BD%B1/"),
        )

        val restored = snapshot.toPaneTabState(JvmVfsPathService())

        assertEquals("smb://host/share/综艺/音乐/", restored.location)
        assertEquals(listOf("smb://host/share/综艺/"), restored.backStack)
        assertEquals(listOf("smb://host/share/综艺/电影/"), restored.forwardStack)
    }
}
