package com.oruke.onyx

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.FileCollectionKind
import com.oruke.onyx.core.model.SearchScope
import java.awt.Frame
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 从实际原生搜索窗口验证真实目录搜索、导航、结果集合与后台生命周期。 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SearchWindowTest {
    /** 各用例使用独立文件目录，并在用例结束后由 JUnit 回收。 */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 通过窗口输入查询并搜索真实文件，确认单击选择、键盘导航和固定结果的可观察结果。
     *
     * @return 无返回值。
     */
    @Test
    fun searchingSelectingNavigatingAndPinningUseRealResults() = runDesktopComposeUiTest {
        val fixture = SearchWindowFixture(temporaryFolder.newFolder("search-documents").toPath())
        try {
            setContent { SearchWindowTestApplication(fixture) }
            runOnUiThread { fixture.delegate.showSearchPanel() }
            awaitSearchWindowTag("search-query")
            assertEquals(SearchScope.CURRENT_DIRECTORY, fixture.delegate.searchState.value.scope)
            assertFalse(fixture.delegate.searchState.value.includeRemoteLocations)
            assertFalse(fixture.delegate.searchState.value.advancedFiltersExpanded)

            enterSearchWindowQuery("report")
            assertEquals("report", fixture.delegate.searchState.value.query)
            clickSearchWindowTag("search-execute")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                fixture.delegate.searchState.value.status == SearchStatus.COMPLETED
            }
            val results = fixture.delegate.searchState.value.results
            assertEquals(3, results.size)
            assertEquals(
                setOf("report-alpha.txt", "report-beta-with-a-long-descriptive-name-for-window-layout.txt", "report-gamma.txt"),
                results.map { entry -> entry.name }.toSet(),
            )
            assertEquals(4, fixture.delegate.searchState.value.scannedEntryCount)

            clickSearchWindowTag("search-result-${results[1].id}")
            assertEquals(1, fixture.delegate.searchState.value.selectedIndex)
            assertTrue(fixture.navigation.isEmpty(), "Single click must only select the result")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                runOnUiThread {
                    visibleSearchWindows().single().searchWindowNode("search-result-${results[1].id}")
                        ?.config?.getOrNull(SemanticsProperties.Selected) == true
                }
            }
            pressSearchWindowEnter()
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) { fixture.navigation.size == 1 }
            assertEquals(PaneIntent.OpenEntry(results[1]), fixture.navigation.single())
            assertTrue(fixture.delegate.searchState.value.visible)
            pressSearchWindowEnter(locate = true)
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) { fixture.navigation.size == 2 }
            assertEquals(
                PaneIntent.OpenDirectoryAndSelect(assertNotNull(results[1].parentLocation), results[1].name),
                fixture.navigation.last(),
            )

            clickSearchWindowTag("search-pin-results")
            val createTab = fixture.navigation.last() as PaneIntent.CreateTab
            val saved = assertNotNull(fixture.collections.findByLocation(assertNotNull(createTab.location)))
            assertEquals(FileCollectionKind.SEARCH_RESULT, saved.kind)
            assertEquals("report", saved.name)
            assertEquals(results, saved.entries)
            captureSearchWindow("search-results.png")
        } finally {
            runOnUiThread {
                fixture.delegate.closeSearchPanel()
                fixture.scope.cancel()
            }
            waitForIdle()
        }
    }

    /**
     * 关闭真实窗口后受控目录读取继续，重新打开保留查询、结果、选中项与唯一窗口实例。
     *
     * @return 无返回值。
     */
    @Test
    fun hiddenWindowKeepsSearchRunningAndReopensWithoutDuplicates() = runDesktopComposeUiTest {
        val releaseNested = CompletableDeferred<Unit>()
        val fixture = SearchWindowFixture(temporaryFolder.newFolder("background-search").toPath(), releaseNested)
        try {
            setContent { SearchWindowTestApplication(fixture) }
            runOnUiThread { fixture.delegate.showSearchPanel() }
            val originalWindow = awaitSearchWindow()
            enterSearchWindowQuery("report")
            clickSearchWindowTag("search-execute")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                fixture.nestedStarted.isCompleted && fixture.delegate.searchState.value.results.size == 2
            }
            assertEquals(SearchStatus.RUNNING, fixture.delegate.searchState.value.status)
            clickSearchWindowTag("search-cancel")
            assertEquals(SearchStatus.CANCELLED, fixture.delegate.searchState.value.status)
            assertEquals(2, fixture.delegate.searchState.value.results.size)
            assertTrue(fixture.delegate.searchState.value.visible)
            clickSearchWindowTag("search-execute")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                fixture.delegate.searchState.value.status == SearchStatus.RUNNING &&
                    fixture.delegate.searchState.value.results.size == 2
            }
            clickSearchWindowTag("search-result-${fixture.delegate.searchState.value.results[1].id}")
            clickSearchWindowTag("search-window-close")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) { runOnUiThread { visibleSearchWindows().isEmpty() } }
            assertFalse(fixture.delegate.searchState.value.visible)
            assertEquals(SearchStatus.RUNNING, fixture.delegate.searchState.value.status)

            releaseNested.complete(Unit)
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                fixture.delegate.searchState.value.status == SearchStatus.COMPLETED
            }
            assertEquals(3, fixture.delegate.searchState.value.results.size)
            assertFalse(fixture.delegate.searchState.value.visible, "Background results must not reopen the window")
            runOnUiThread { fixture.delegate.showSearchPanel() }
            awaitSearchWindowTag("search-result-${fixture.delegate.searchState.value.results[1].id}")
            assertEquals("report", fixture.delegate.searchState.value.query)
            assertEquals(1, fixture.delegate.searchState.value.selectedIndex)
            val reopened = awaitSearchWindow()
            assertSame(originalWindow, reopened)
            runOnUiThread { reopened.extendedState = Frame.ICONIFIED }
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                runOnUiThread { reopened.extendedState and Frame.ICONIFIED != 0 }
            }
            runOnUiThread { fixture.delegate.showSearchPanel() }
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                runOnUiThread { reopened.extendedState and Frame.ICONIFIED == 0 }
            }
            assertSame(reopened, awaitSearchWindow())
            assertEquals(1, runOnUiThread { visibleSearchWindows().size })
            assertEquals(3L, fixture.delegate.searchState.value.activationRequest)
            captureSearchWindow("search-reopened.png")
        } finally {
            runOnUiThread {
                fixture.delegate.closeSearchPanel()
                fixture.scope.cancel()
            }
            waitForIdle()
        }
    }

    /**
     * 高级条件和网络扫描只能显式启用，200% 缩放的最小窗口保持关键操作可达。
     *
     * @return 无返回值。
     */
    @Test
    fun advancedFiltersAndScaledMinimumWindowRemainUsable() = runDesktopComposeUiTest {
        val root = temporaryFolder.newFolder("scaled-search").toPath()
        val fixture = SearchWindowFixture(root)
        repeat(60) { index -> Files.writeString(root.resolve("report-additional-$index.txt"), "scrollable search result") }
        try {
            setContent { SearchWindowTestApplication(fixture) }
            runOnUiThread { fixture.delegate.showSearchPanel() }
            val window = awaitSearchWindow()
            clickSearchWindowTag("search-advanced")
            assertTrue(fixture.delegate.searchState.value.advancedFiltersExpanded)
            clickSearchWindowTag("search-include-remote")
            assertTrue(fixture.delegate.searchState.value.includeRemoteLocations)
            clickSearchWindowTag("search-include-remote")
            assertFalse(fixture.delegate.searchState.value.includeRemoteLocations)
            enterSearchWindowQuery("report")
            clickSearchWindowTag("search-execute")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                fixture.delegate.searchState.value.status == SearchStatus.COMPLETED
            }
            assertEquals(63, fixture.delegate.searchState.value.results.size)
            runOnUiThread { fixture.preferences.value = fixture.preferences.value.copy(uiScale = 200) }
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) {
                runOnUiThread { window.minimumSize.width == 1280 }
            }
            runOnUiThread { window.size = window.minimumSize }
            captureSearchWindow("search-scaled-minimum.png")
            listOf(
                "search-window-close", "search-pin-results", "search-execute",
                "search-advanced", "search-include-remote", "search-use-current-directory",
            ).forEach { tag -> assertSearchWindowControlIsVisible(tag) }
            val previousScroll = scrollSearchWindowResults(700f)
            clickSearchWindowTag("search-window-close")
            waitUntil(timeoutMillis = SEARCH_WINDOW_WAIT_MILLIS) { runOnUiThread { visibleSearchWindows().isEmpty() } }
            runOnUiThread { fixture.delegate.showSearchPanel() }
            awaitSearchWindowTag("search-window-close")
            assertSame(window, awaitSearchWindow())
            runOnUiThread { window.renderImmediately() }
            val reopenedScroll = runOnUiThread {
                assertNotNull(window.searchWindowNode("search-results")
                    ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
            }
            assertEquals(previousScroll, reopenedScroll, absoluteTolerance = 1f)
            assertTrue(fixture.delegate.searchState.value.advancedFiltersExpanded)
            assertFalse(fixture.delegate.searchState.value.includeRemoteLocations)
        } finally {
            runOnUiThread {
                fixture.delegate.closeSearchPanel()
                fixture.scope.cancel()
            }
            waitForIdle()
        }
    }
}
