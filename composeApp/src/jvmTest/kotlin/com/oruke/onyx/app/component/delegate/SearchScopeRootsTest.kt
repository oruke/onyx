package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** 搜索根构造与多根归并去重测试。 */
class SearchScopeRootsTest {
    private val baseRoot = "local:/workspace"
    private val favorites = listOf("local:/favorites-a", "local:/favorites-b")
    private val allRoots = listOf("local:C:\\", "local:D:\\")

    /** 验证当前目录范围只使用活动面板位置。 */
    @Test
    fun currentDirectoryScopeUsesBaseRoot() {
        val roots = buildSearchRoots(SearchScope.CURRENT_DIRECTORY, baseRoot, favorites, allRoots)
        assertEquals(listOf(baseRoot), roots)
    }

    /** 验证收藏范围使用去重后的收藏列表。 */
    @Test
    fun favoritesScopeUsesFavorites() {
        val roots = buildSearchRoots(SearchScope.FAVORITES, baseRoot, favorites, allRoots)
        assertEquals(favorites, roots)
    }

    /** 验证收藏为空时收藏范围回退到当前目录。 */
    @Test
    fun favoritesScopeFallsBackToBaseRootWhenEmpty() {
        val roots = buildSearchRoots(SearchScope.FAVORITES, baseRoot, emptyList(), allRoots)
        assertEquals(listOf(baseRoot), roots)
    }

    /** 验证全部磁盘范围使用文件系统根列表。 */
    @Test
    fun allRootsScopeUsesAllRoots() {
        val roots = buildSearchRoots(SearchScope.ALL_ROOTS, baseRoot, favorites, allRoots)
        assertEquals(allRoots, roots)
    }

    /** 验证全部磁盘范围在文件系统根为空时回退到当前目录。 */
    @Test
    fun allRootsScopeFallsBackToBaseRootWhenEmpty() {
        val roots = buildSearchRoots(SearchScope.ALL_ROOTS, baseRoot, favorites, emptyList())
        assertEquals(listOf(baseRoot), roots)
    }

    /** 验证多根归并保持每个根内顺序并按 ID 去重。 */
    @Test
    fun mergeSearchResultsDeduplicatesByEntryIdPreservingOrder() {
        val shared = file("id-shared", "shared.txt")
        val firstRoot = listOf(file("id-1", "a.txt"), shared, file("id-2", "b.txt"))
        val secondRoot = listOf(shared, file("id-3", "c.txt"), file("id-1", "a.txt"))

        val merged = mergeSearchResults(
            listOf(
                "local:/r1" to firstRoot,
                "local:/r2" to secondRoot,
            )
        )

        assertEquals(
            listOf("id-1", "id-shared", "id-2", "id-3"),
            merged.map { entry -> entry.id },
        )
    }

    /** 构造测试用文件条目。 */
    private fun file(id: String, name: String): VFile {
        return VFile(
            id = id,
            name = name,
            location = "local:/dir/$name",
            parentLocation = "local:/dir",
            kind = VFileKind.FILE,
            sizeBytes = 10L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )
    }
}
