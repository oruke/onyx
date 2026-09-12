package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** 搜索根构造与多根归并去重测试。 */
class SearchScopeRootsTest {
    /** 当前会话绑定的脱敏本地目录。 */
    private val baseRoot = "local:/workspace"

    /** 正常收藏范围中的本地位置。 */
    private val favorites = listOf("local:/favorites-a", "local:/favorites-b")

    /** 正常全部磁盘范围中的本地根。 */
    private val allRoots = listOf("local:C:\\", "local:D:\\")

    /** 统一 VFS 支持的网络位置，以及可间接访问网络的路径形式。 */
    private val remoteRoots = listOf(
        "smb://example.invalid/share",
        "SMB://example.invalid/share/space name",
        "webdav://example.invalid/documents",
        "webdavs://example.invalid/documents",
        "s3://example.invalid/bucket",
        "\\\\example.invalid\\share",
        "//example.invalid/share",
        "file://example.invalid/share",
        "File://example.invalid/share",
        "file:////example.invalid/share",
        "FILE:////example.invalid/share",
        "archive://smb://example.invalid/share/sample.zip!/inside",
        "archive://webdavs://example.invalid/sample.zip!/inside",
        "archive://s3://example.invalid/bucket/sample.zip!/inside",
        "archive://\\\\example.invalid\\share\\sample.zip!/inside",
        "archive://file://example.invalid/share/sample.zip!/inside",
        "archive://File:////example.invalid/share/sample.zip!/inside",
    )

    /** 验证当前目录范围只使用会话显式绑定的位置。 */
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

    /** 验证收藏为空时保留空范围，不扫描会话当前目录。 */
    @Test
    fun favoritesScopeRemainsEmptyWhenNoFavoritesExist() {
        val roots = buildSearchRoots(SearchScope.FAVORITES, baseRoot, emptyList(), allRoots)
        assertEquals(emptyList(), roots)
    }

    /** 验证全部磁盘范围使用文件系统根列表。 */
    @Test
    fun allRootsScopeUsesAllRoots() {
        val roots = buildSearchRoots(SearchScope.ALL_ROOTS, baseRoot, favorites, allRoots)
        assertEquals(allRoots, roots)
    }

    /** 验证文件系统根为空时保留空范围，不扫描会话当前目录。 */
    @Test
    fun allRootsScopeRemainsEmptyWhenNoRootsExist() {
        val roots = buildSearchRoots(SearchScope.ALL_ROOTS, baseRoot, favorites, emptyList())
        assertEquals(emptyList(), roots)
    }

    /** 验证收藏根先规范化并去重，再依据显式授权过滤远程位置。 */
    @Test
    fun favoritesScopeFiltersRemoteRootsUnlessExplicitlyIncluded() {
        val mixedFavorites = listOf(" ", " $baseRoot ", baseRoot) + remoteRoots + remoteRoots

        assertEquals(
            listOf(baseRoot),
            buildSearchRoots(SearchScope.FAVORITES, baseRoot, mixedFavorites, allRoots),
        )
        assertEquals(
            listOf(baseRoot) + remoteRoots,
            buildSearchRoots(SearchScope.FAVORITES, baseRoot, mixedFavorites, allRoots, true),
        )
        assertEquals(
            emptyList(),
            buildSearchRoots(SearchScope.FAVORITES, baseRoot, remoteRoots, allRoots),
        )
    }

    /** 验证全部磁盘范围中可识别的网络根同样需要显式授权。 */
    @Test
    fun allRootsScopeFiltersRemoteRootsUnlessExplicitlyIncluded() {
        val mixedRoots = allRoots + remoteRoots

        assertEquals(allRoots, buildSearchRoots(SearchScope.ALL_ROOTS, baseRoot, favorites, mixedRoots))
        assertEquals(mixedRoots, buildSearchRoots(SearchScope.ALL_ROOTS, baseRoot, favorites, mixedRoots, true))
    }

    /** 验证即使当前目录是网络位置，也必须显式允许远程搜索。 */
    @Test
    fun remoteCurrentDirectoryRequiresExplicitInclusion() {
        remoteRoots.forEach { remoteRoot ->
            assertEquals(
                emptyList(),
                buildSearchRoots(SearchScope.CURRENT_DIRECTORY, remoteRoot, favorites, allRoots),
                remoteRoot,
            )
            assertEquals(
                listOf(remoteRoot),
                buildSearchRoots(SearchScope.CURRENT_DIRECTORY, remoteRoot, favorites, allRoots, true),
                remoteRoot,
            )
        }
    }

    /** 验证本地路径、大小写不同的 file URI 和本地压缩包不会被远程过滤误删。 */
    @Test
    fun localLocationsRemainEligibleWithoutRemoteInclusion() {
        val localRoots = listOf(
            baseRoot,
            "/workspace",
            "C:\\workspace",
            "file:///workspace",
            "file:///C:/workspace",
            "File:///C:/workspace",
            "FILE:///C:/workspace",
            "archive:///workspace/sample.zip!/inside",
            "archive://C:/workspace/sample.zip!/inside",
            "archive://File:///C:/workspace/sample.zip!/inside",
        )

        assertEquals(localRoots, buildSearchRoots(SearchScope.FAVORITES, baseRoot, localRoots, allRoots))
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

    /**
     * 构造只包含脱敏位置的归并测试条目。
     *
     * @param id 用于跨搜索根去重的条目标识。
     * @param name 用于核对根内排序的文件名。
     * @return 不具备文件操作能力的测试文件。
     */
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
