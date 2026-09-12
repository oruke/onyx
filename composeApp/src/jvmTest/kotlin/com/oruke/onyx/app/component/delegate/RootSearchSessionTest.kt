package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.SearchPanelMode
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SearchScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 使用真实文件扫描验证搜索会话隔离、后台状态和集合快照的完整业务链路。 */
class RootSearchSessionTest {
    /** 验证完整搜索和快速打开同时运行时，关闭快速打开只取消自身。 */
    @Test
    fun closingQuickOpenDoesNotCancelFullSearch() = runBlocking {
        val fixture = RootSearchSessionFixture(this)
        try {
            val fullGate = SessionSearchReadGate()
            val quickGate = SessionSearchReadGate()
            fixture.repository.gates[fixture.firstRoot] = fullGate
            fixture.repository.gates[fixture.secondRoot] = quickGate
            val full = fixture.delegate()
            full.showSearchPanel()
            full.updateSearchQuery("report")
            full.executeSearch()
            withTimeout(5_000L) { fullGate.started.await() }

            fixture.activeRoot = fixture.secondRoot
            val quick = fixture.delegate(SearchPanelMode.QUICK_OPEN)
            quick.showQuickOpen()
            quick.updateSearchQuery("beta")
            quick.executeSearch()
            withTimeout(5_000L) { quickGate.started.await() }
            quick.closeSearchPanel()
            withTimeout(5_000L) { quickGate.exited.await() }

            assertEquals(SearchStatus.CANCELLED, quick.searchState.value.status)
            assertFalse(quick.searchState.value.visible)
            assertEquals(SearchStatus.RUNNING, full.searchState.value.status)
            assertEquals("report", full.searchState.value.query)
            fullGate.release.complete(Unit)
            val completed = full.awaitSessionState { it.status == SearchStatus.COMPLETED }
            assertEquals(setOf("report-alpha.txt", "report-beta.txt"), completed.results.map { it.name }.toSet())
            assertTrue(completed.visible)
        } finally {
            fixture.dispose()
        }
    }

    /** 验证隐藏窗口继续收到结果，并在多根结果前插时按条目标识保留选中项。 */
    @Test
    fun hiddenWindowRetainsSelectionWhenEarlierRootFinishes() = runBlocking {
        val fixture = RootSearchSessionFixture(this)
        try {
            val gate = SessionSearchReadGate()
            fixture.repository.gates[fixture.firstRoot] = gate
            val delegate = fixture.delegate()
            delegate.showSearchPanel()
            delegate.updateSearchScope(SearchScope.ALL_ROOTS)
            delegate.updateSearchQuery("report")
            delegate.executeSearch()
            val partial = delegate.awaitSessionState { it.results.size == 2 && it.status == SearchStatus.RUNNING }
            delegate.setSelectedIndex(1)
            val selectedId = partial.results[1].id
            delegate.setAdvancedFiltersExpanded(true)
            delegate.closeSearchPanel()
            gate.release.complete(Unit)

            val completed = delegate.awaitSessionState { it.status == SearchStatus.COMPLETED }
            assertEquals(4, completed.results.size)
            assertEquals(selectedId, completed.results[completed.selectedIndex].id)
            assertEquals(3, completed.selectedIndex)
            assertFalse(completed.visible)
            assertTrue(completed.advancedFiltersExpanded)
            delegate.setSelectedIndex(Int.MAX_VALUE)
            assertEquals(3, delegate.searchState.value.selectedIndex)
        } finally {
            fixture.dispose()
        }
    }

    /** 验证再次显示不随面板目录漂移，显式采用当前目录才清结果并重新绑定来源。 */
    @Test
    fun reopeningRetainsSourceUntilCurrentDirectoryIsExplicitlyAdopted() = runBlocking {
        val fixture = RootSearchSessionFixture(this)
        try {
            val delegate = fixture.delegate()
            delegate.showSearchPanel()
            delegate.updateSearchQuery("report")
            delegate.executeSearch()
            val original = delegate.awaitSessionState { it.status == SearchStatus.COMPLETED }
            delegate.setSelectedIndex(1)
            delegate.setAdvancedFiltersExpanded(true)
            delegate.closeSearchPanel()
            fixture.activeRoot = fixture.secondRoot
            fixture.activePane = PaneId.SECONDARY
            delegate.showSearchPanel()

            val reopened = delegate.searchState.value
            assertEquals(fixture.firstRoot, reopened.rootLocation)
            assertEquals(PaneId.PRIMARY, reopened.paneId)
            assertEquals(original.results, reopened.results)
            assertEquals(original.activationRequest + 1L, reopened.activationRequest)
            assertEquals(1, reopened.selectedIndex)
            assertTrue(reopened.advancedFiltersExpanded)
            delegate.useCurrentDirectory()

            val rebound = delegate.searchState.value
            assertEquals(fixture.secondRoot, rebound.rootLocation)
            assertEquals(PaneId.SECONDARY, rebound.paneId)
            assertEquals("report", rebound.query)
            assertEquals(SearchScope.CURRENT_DIRECTORY, rebound.scope)
            assertEquals(SearchStatus.IDLE, rebound.status)
            assertTrue(rebound.results.isEmpty())
            assertEquals(0, rebound.selectedIndex)
        } finally {
            fixture.dispose()
        }
    }

    /** 验证停止保留部分结果，后续搜索及多次固定均不会改变已保存集合。 */
    @Test
    fun cancellingAndSearchingAgainPreservesIndependentCollectionSnapshots() = runBlocking {
        val fixture = RootSearchSessionFixture(this)
        try {
            val gate = SessionSearchReadGate()
            fixture.repository.gates[fixture.firstRoot] = gate
            val delegate = fixture.delegate()
            delegate.showSearchPanel()
            delegate.updateSearchScope(SearchScope.ALL_ROOTS)
            delegate.updateSearchQuery("report")
            delegate.executeSearch()
            withTimeout(5_000L) { gate.started.await() }
            val partial = delegate.awaitSessionState { it.results.size == 2 && it.status == SearchStatus.RUNNING }
            delegate.cancelSearch()
            withTimeout(5_000L) { gate.exited.await() }
            assertEquals(SearchStatus.CANCELLED, delegate.searchState.value.status)
            assertEquals(partial.results, delegate.searchState.value.results)
            delegate.openSearchResultsAsCollection()
            val firstLocation = (fixture.navigation.last() as PaneIntent.CreateTab).location
            val firstSnapshot = assertNotNull(fixture.collections.findByLocation(assertNotNull(firstLocation)))

            // 旧读取已退出，再启动新查询；检查集合仓储而非仅检查内存调用次数。
            gate.release.complete(Unit)
            delegate.updateSearchQuery("beta")
            assertTrue(delegate.searchState.value.results.isEmpty())
            delegate.executeSearch()
            val completed = delegate.awaitSessionState { it.status == SearchStatus.COMPLETED }
            assertEquals(2, completed.results.size)
            assertTrue(completed.results.all { it.name == "report-beta.txt" })
            delegate.openSearchResultsAsCollection()
            val secondLocation = (fixture.navigation.last() as PaneIntent.CreateTab).location
            assertNotEquals(firstLocation, secondLocation)
            assertEquals(partial.results, fixture.collections.findById(firstSnapshot.id)?.entries)
            assertEquals(completed.results, fixture.collections.findByLocation(assertNotNull(secondLocation))?.entries)
        } finally {
            fixture.dispose()
        }
    }

    /** 验证完整搜索的网络授权，以及快速打开可直接扫描用户已打开的当前网络目录。 */
    @Test
    fun remoteRootRequiresExplicitAuthorizationBeforeRepositoryAccess() = runBlocking {
        val fixture = RootSearchSessionFixture(this)
        try {
            val remoteRoot = "smb://storage.example.invalid/share"
            fixture.repository.aliases[remoteRoot] = fixture.firstRoot
            fixture.activeRoot = remoteRoot
            val delegate = fixture.delegate()
            delegate.showSearchPanel()
            delegate.updateSearchQuery("report")
            delegate.executeSearch()
            assertEquals(SearchStatus.FAILED, delegate.searchState.value.status)
            assertEquals(MessageKey.MSG_SEARCH_NO_ELIGIBLE_ROOTS, delegate.searchState.value.error?.key)
            assertTrue(fixture.repository.requestedLocations.isEmpty())

            delegate.setIncludeRemoteLocations(true)
            delegate.executeSearch()
            val completed = delegate.awaitSessionState { it.status == SearchStatus.COMPLETED }
            assertEquals(listOf(remoteRoot), fixture.repository.requestedLocations.toList())
            assertEquals(setOf("report-alpha.txt", "report-beta.txt"), completed.results.map { it.name }.toSet())

            val quick = fixture.delegate(SearchPanelMode.QUICK_OPEN)
            quick.showQuickOpen()
            quick.updateSearchQuery("beta")
            quick.executeSearch()
            val quickCompleted = quick.awaitSessionState { it.status == SearchStatus.COMPLETED }
            assertEquals(SearchScope.CURRENT_DIRECTORY, quickCompleted.scope)
            assertEquals(remoteRoot, quickCompleted.rootLocation)
            assertEquals(listOf("report-beta.txt"), quickCompleted.results.map { it.name })
            assertEquals(completed.results, delegate.searchState.value.results)
        } finally {
            fixture.dispose()
        }
    }
}
