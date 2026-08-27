package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneChromeState
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.createDefaultPaneTabState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.vfs.api.FileRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** RootSearchDelegate 的 Quick Open 关闭与多根搜索取消测试。 */
class RootSearchDelegateTest {
    /** 验证 Quick Open 打开结果后会关闭一次性浮层。 */
    @Test
    fun openingQuickOpenResultClosesOverlay() = runBlocking {
        val delegateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatchedIntents = mutableListOf<PaneIntent>()
        try {
            val delegate = createDelegate(
                scope = delegateScope,
                fileRepository = EmptyFileRepository,
                dispatchedIntents = dispatchedIntents,
                filesystemRoots = listOf(TEST_ROOT),
            )
            val entry = testFile()

            delegate.showQuickOpen()
            delegate.openSearchResult(entry)

            assertEquals(listOf<PaneIntent>(PaneIntent.OpenEntry(entry)), dispatchedIntents)
            assertFalse(delegate.searchState.value.visible)
        } finally {
            delegateScope.cancel()
        }
    }

    /** 验证局部失败后关闭面板仍会取消尚未完成的搜索根。 */
    @Test
    fun closingFailedMultiRootSearchCancelsRemainingRoots() = runBlocking {
        val delegateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fileRepository = PartiallyFailingFileRepository()
        try {
            val delegate = createDelegate(
                scope = delegateScope,
                fileRepository = fileRepository,
                dispatchedIntents = mutableListOf(),
                filesystemRoots = listOf(FAILING_ROOT, BLOCKING_ROOT),
            )

            delegate.showSearchPanel()
            delegate.updateSearchScope(SearchScope.ALL_ROOTS)
            delegate.updateSearchQuery(TEST_QUERY)
            delegate.executeSearch()
            waitUntilSearchFails(delegate)

            delegate.closeSearchPanel()
            fileRepository.releaseBlockingRoot()

            assertEquals(
                BlockingRootOutcome.CANCELLED,
                withTimeout(SEARCH_WAIT_TIMEOUT_MILLIS) { fileRepository.blockingRootOutcome.await() },
            )
            assertFalse(delegate.searchState.value.visible)
        } finally {
            delegateScope.cancel()
        }
    }

    /**
     * 构造可记录面板导航意图的搜索委托。
     *
     * @param scope 搜索任务使用的协程作用域。
     * @param fileRepository 测试搜索使用的文件仓储。
     * @param dispatchedIntents 记录发往搜索来源面板的意图。
     * @param filesystemRoots 当前测试可用的文件系统根。
     * @return 已连接测试依赖的搜索委托。
     */
    private fun createDelegate(
        scope: CoroutineScope,
        fileRepository: FileRepository,
        dispatchedIntents: MutableList<PaneIntent>,
        filesystemRoots: List<String>,
    ): RootSearchDelegate {
        val pane = recordingPane(dispatchedIntents)
        return RootSearchDelegate(
            scope = scope,
            fileSearchUseCase = FileSearchUseCase(fileRepository),
            fileCollectionUseCase = FileCollectionUseCase(InMemoryFileCollectionRepository()),
            paneState = { testPaneState() },
            activatePane = {},
            paneComponent = { pane },
            getActivePane = { PaneId.PRIMARY },
            settings = { OnyxSettings() },
            filesystemRoots = { filesystemRoots },
            onRecordSearchHistory = {},
        )
    }

    /**
     * 等待多根搜索中的失败根将界面状态发布为失败。
     *
     * @param delegate 待观察状态的搜索委托。
     */
    private suspend fun waitUntilSearchFails(delegate: RootSearchDelegate) {
        withTimeout(SEARCH_WAIT_TIMEOUT_MILLIS) {
            while (delegate.searchState.value.status != SearchStatus.FAILED) {
                delay(SEARCH_POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * 创建只记录 dispatch 调用的 PaneComponent 代理。
     *
     * 委托只会通过 PaneComponent 的统一意图 API 发起导航，测试无需实现标签栈等不相关能力。
     *
     * @param dispatchedIntents 记录分发的面板意图。
     * @return 可供搜索委托使用的测试面板。
     */
    private fun recordingPane(dispatchedIntents: MutableList<PaneIntent>): PaneComponent {
        return PaneComponent::class.java.cast(
            Proxy.newProxyInstance(
                PaneComponent::class.java.classLoader,
                arrayOf(PaneComponent::class.java),
            ) { _, method, arguments ->
                if (method.name == "dispatch") {
                    dispatchedIntents += arguments?.single() as PaneIntent
                    null
                } else {
                    error("测试未预期调用 PaneComponent.${method.name}")
                }
            },
        )
    }

    /**
     * 构造用于显示 Quick Open 的最小面板状态。
     *
     * @return 当前目录位于测试根的面板状态。
     */
    private fun testPaneState(): PaneState {
        val tab = createDefaultPaneTabState(
            id = TEST_TAB_ID,
            title = TEST_TAB_ID,
            location = TEST_ROOT,
            defaultViewMode = ViewMode.DETAILS,
        )
        return PaneState(
            paneId = PaneId.PRIMARY,
            chromeState = PaneChromeState(activeTabId = TEST_TAB_ID),
            activeTabState = tab.tabState,
        )
    }

    /**
     * 构造可通过统一面板意图打开的测试文件。
     *
     * @return 位于测试根中的普通文件条目。
     */
    private fun testFile(): VFile {
        return VFile(
            id = TEST_FILE_NAME,
            name = TEST_FILE_NAME,
            location = "$TEST_ROOT/$TEST_FILE_NAME",
            parentLocation = TEST_ROOT,
            kind = VFileKind.FILE,
            sizeBytes = 1L,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }

    /** 测试中阻塞搜索根的最终状态。 */
    private enum class BlockingRootOutcome {
        COMPLETED,
        CANCELLED,
    }

    /** 为空搜索和 Quick Open 提供稳定测试根的仓储。 */
    private object EmptyFileRepository : FileRepository {
        /**
         * 返回空目录结果。
         *
         * @param location 待读取的测试位置。
         * @return 空文件列表。
         */
        override suspend fun list(location: String): Result<List<VFile>> = Result.success(emptyList())

        /**
         * 返回测试默认位置。
         *
         * @return 测试根位置。
         */
        override fun defaultLocation(): String = TEST_ROOT
    }

    /**
     * 一个根立即失败、另一个根持续等待释放信号的测试仓储。
     *
     * 失败根会等待阻塞根实际进入挂起状态，确保测试覆盖“局部失败但仍有后台根在运行”的场景。
     */
    private class PartiallyFailingFileRepository : FileRepository {
        /** 阻塞根已开始读取时完成。 */
        private val blockingRootStarted = CompletableDeferred<Unit>()

        /** 允许阻塞根继续返回结果的测试信号。 */
        private val releaseBlockingRootSignal = CompletableDeferred<Unit>()

        /** 对外暴露阻塞根实际完成或被取消的结果。 */
        val blockingRootOutcome = CompletableDeferred<BlockingRootOutcome>()

        /**
         * 按测试根返回失败或阻塞的目录读取结果。
         *
         * @param location 待读取的测试根位置。
         * @return 失败根返回明确失败，阻塞根等待测试信号后返回空结果。
         */
        override suspend fun list(location: String): Result<List<VFile>> {
            return when (location) {
                FAILING_ROOT -> {
                    blockingRootStarted.await()
                    Result.failure(IllegalStateException("测试失败根"))
                }

                BLOCKING_ROOT -> awaitBlockingRoot()
                else -> error("未知测试根: $location")
            }
        }

        /**
         * 返回默认测试根。
         *
         * @return 立即失败的测试根位置。
         */
        override fun defaultLocation(): String = FAILING_ROOT

        /** 释放阻塞根，允许其在未被取消时返回正常空结果。 */
        fun releaseBlockingRoot() {
            releaseBlockingRootSignal.complete(Unit)
        }

        /**
         * 在可取消的挂起点等待阻塞根释放，并记录最终路径。
         *
         * @return 未取消时返回空目录结果。
         */
        private suspend fun awaitBlockingRoot(): Result<List<VFile>> {
            blockingRootStarted.complete(Unit)
            return try {
                releaseBlockingRootSignal.await()
                blockingRootOutcome.complete(BlockingRootOutcome.COMPLETED)
                Result.success(emptyList())
            } catch (failure: CancellationException) {
                blockingRootOutcome.complete(BlockingRootOutcome.CANCELLED)
                throw failure
            }
        }
    }

    private companion object {
        /** 搜索委托异步状态等待上限（毫秒）。 */
        const val SEARCH_WAIT_TIMEOUT_MILLIS = 3_000L

        /** 搜索委托状态轮询间隔（毫秒）。 */
        const val SEARCH_POLL_INTERVAL_MILLIS = 10L

        /** Quick Open 与空搜索使用的测试根。 */
        const val TEST_ROOT = "test://root"

        /** 局部失败场景中的立即失败根。 */
        const val FAILING_ROOT = "test://failing"

        /** 局部失败场景中的阻塞根。 */
        const val BLOCKING_ROOT = "test://blocking"

        /** 测试面板标签标识。 */
        const val TEST_TAB_ID = "test-tab"

        /** 测试文件名称。 */
        const val TEST_FILE_NAME = "result.txt"

        /** 多根搜索使用的非空查询。 */
        const val TEST_QUERY = "result"
    }
}
