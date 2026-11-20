package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneContextMenuCommand
import com.oruke.onyx.app.component.PaneContextMenuIcon
import com.oruke.onyx.app.component.PaneContextMenuModel
import com.oruke.onyx.app.component.PaneContextMenuNode
import com.oruke.onyx.app.component.PaneContextMenuText
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneRemovalContext
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.PaneTabState
import com.oruke.onyx.app.component.PaneTabTransfer
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchModifiedPreset
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.TabComponent
import com.oruke.onyx.app.component.TabConfig
import com.oruke.onyx.app.component.createDefaultPaneTabState
import com.oruke.onyx.app.component.toPaneState
import com.oruke.onyx.app.component.toTabConfig
import com.oruke.onyx.app.component.toTabSnapshot
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.TabDropZone
import kotlinx.coroutines.flow.StateFlow
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.app.platform.JvmApplicationLocaleController
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_focus_address_bar
import onyx.composeapp.generated.resources.action_retry_task
import onyx.composeapp.generated.resources.label_search_advanced_filters
import onyx.composeapp.generated.resources.search_filter_modified
import onyx.composeapp.generated.resources.search_filter_week
import org.jetbrains.compose.resources.getString
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Batch 2 UI 交互与 Skia 实景渲染验收测试。
 *
 * 覆盖：
 * - `F-04` 隐藏列后内容行与表头严格对齐（含实景截图）
 * - `F-09` 详情行撑满视口宽度，右侧空白点击仍命中行而不误清空/返回上级（含实景截图）
 * - `SEARCH-004` 高级搜索修改时间「本周」选中高亮在持续重组下稳定保持（含实景截图）
 * - `F-06` 右键菜单键盘导航与防穿透
 * - `F-11` 小键盘 `Key.NumPadEnter` 全局生效
 * - `F-12` 地址栏按 Tab 重置、失焦保草稿、`Ctrl+L` 聚焦与 Tab 自动补全
 * - `OPS-004` 确认对话框与冲突对话框 `Tab` / `Shift+Tab` 循环切焦与 `NumPadEnter` 确认
 * - `OPS-008` 任务中心仅对 `canRetry == true` 的失败任务渲染重试图标
 * - `SEARCH-002` Quick Open 防抖空结果按回车立即发起搜索
 * - `SEARCH-003` Command Palette 键盘移动自动滚动并支持 NumPadEnter
 */
@OptIn(ExperimentalTestApi::class)
class Batch2UiAndVisualVerificationTest {
    /** 验证隐藏 `TYPE` 列后内容行不渲染类型列，且详情行撑满视口宽度可点击右侧选中并输出实景截图。 */
    @Test
    fun hiddenColumnAlignedAndDetailsRowsFillViewportWidth() = runDesktopComposeUiTest(
        width = 900,
        height = 420,
    ) {
        val entries = listOf(
            VFile(
                id = "dir-1",
                name = "DesignAssets",
                location = "C:\\work\\DesignAssets",
                parentLocation = "C:\\work",
                kind = VFileKind.DIRECTORY,
                sizeBytes = null,
                modifiedAtEpochMillis = 1_763_470_000_000L,
                hidden = false,
                capabilities = setOf(VFileCapability.LIST_CHILDREN),
            ),
            VFile(
                id = "file-1",
                name = "release-notes.md",
                location = "C:\\work\\release-notes.md",
                parentLocation = "C:\\work",
                kind = VFileKind.FILE,
                sizeBytes = 18_432L,
                modifiedAtEpochMillis = 1_763_472_000_000L,
                hidden = false,
                capabilities = emptySet(),
            ),
            VFile(
                id = "file-2",
                name = "architecture.pdf",
                location = "C:\\work\\architecture.pdf",
                parentLocation = "C:\\work",
                kind = VFileKind.FILE,
                sizeBytes = 2_457_600L,
                modifiedAtEpochMillis = 1_763_475_000_000L,
                hidden = false,
                capabilities = emptySet(),
            ),
        )
        var selectedIds by mutableStateOf(setOf("file-1"))
        var navigatedUp = false

        setContent {
            TestOnyxShell {
                Box(Modifier.fillMaxSize().background(LocalOnyxPalette.current.surface)) {
                    PaneEntriesContent(
                        model = PaneEntriesViewModel(
                            viewMode = ViewMode.DETAILS,
                            columns = listOf(
                                DetailsColumn.NAME,
                                DetailsColumn.TYPE,
                                DetailsColumn.SIZE,
                                DetailsColumn.MODIFIED,
                            ),
                            columnWeights = mapOf(
                                DetailsColumn.NAME to 240f,
                                DetailsColumn.TYPE to 80f,
                                DetailsColumn.SIZE to 100f,
                                DetailsColumn.MODIFIED to 150f,
                            ),
                            hiddenColumns = setOf(DetailsColumn.TYPE),
                            sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING),
                            selectedEntryIds = selectedIds,
                            entriesState = PaneEntriesState.Ready(entries),
                            paneActive = true,
                            contextMenuVisible = false,
                            paneId = PaneId.PRIMARY,
                            fileDropTarget = null,
                            inlineEditState = null,
                        ),
                        actions = testEntriesActions(
                            onSelectEntry = { id, _, _ -> selectedIds = setOf(id) },
                            onSelectEntries = { ids -> selectedIds = ids },
                            onNavigateUp = { navigatedUp = true },
                        ),
                        thumbnails = testThumbnails(),
                    )
                }
            }
        }

        waitForIdle()
        // F-04：TYPE 列已隐藏，单元格不应渲染任何扩展名类型值（md / pdf）。
        onAllNodesWithText("md").assertCountEquals(0)
        onAllNodesWithText("pdf").assertCountEquals(0)
        onNodeWithText("release-notes.md").assertIsDisplayed()

        // F-09：在根坐标系下点击第一行最右侧空白区（x = 840dp，远超所有可见列总宽 ~520dp），仍命中第一行而非清空选区。
        val rowBounds = onNodeWithText("DesignAssets").getUnclippedBoundsInRoot()
        val rowCenterY = (rowBounds.top.value + rowBounds.bottom.value) / 2f
        onRoot().performMouseInput {
            click(Offset(840f, rowCenterY))
        }
        waitForIdle()
        assertEquals(setOf("dir-1"), selectedIds)
        assertFalse(navigatedUp)

        // 重新选中第二行并导出视觉验收截图。
        runOnUiThread { selectedIds = setOf("file-1") }
        waitForIdle()
        saveScreenshot("01-details-hidden-col-fullwidth-after.png", onRoot().captureToImage())
    }

    /** 验证高级搜索点击「本周」后即使触发额外重组，高亮状态依然稳定不闪烁，并导出实景截图。 */
    @Test
    fun searchPanelModifiedWeekPresetStaysSelectedAcrossRecompositions() = runDesktopComposeUiTest(
        width = 820,
        height = 460,
    ) {
        var searchState by mutableStateOf(
            SearchPanelState(
                visible = true,
                rootLocation = "C:\\Projects\\onyx",
                query = "hello world",
                scope = SearchScope.CURRENT_DIRECTORY,
                filters = SearchFilters(searchInContent = true),
                status = SearchStatus.IDLE,
                advancedFiltersExpanded = true,
            ),
        )
        var recompositionTick by mutableIntStateOf(0)

        setContent {
            val tick = recompositionTick
            TestOnyxShell {
                SearchPanel(
                    state = searchState.copy(scannedEntryCount = tick),
                    presentation = SearchPanelPresentation(
                        searchHistory = emptyList(),
                        locationLabel = { it },
                    ),
                    listState = rememberLazyListState(),
                    actions = SearchPanelActions(
                        onQueryChange = { q -> searchState = searchState.copy(query = q) },
                        onSearch = {},
                        onCancel = {},
                        onClose = {},
                        onOpenResult = {},
                        onOpenResultInFolder = {},
                        onUpdateScope = { s -> searchState = searchState.copy(scope = s) },
                        onUpdateFilters = { next -> searchState = searchState.copy(filters = next) },
                        onSelectIndex = {},
                        onSetAdvancedFiltersExpanded = {},
                        onSetIncludeRemoteLocations = {},
                        onUseCurrentDirectory = {},
                        onOpenResultsAsCollection = {},
                        onClearHistory = {},
                    ),
                )
            }
        }

        waitForIdle()
        val modifiedLabel = getString(Res.string.search_filter_modified)
        val weekLabel = getString(Res.string.search_filter_week)
        onNodeWithText(modifiedLabel).performClick()
        waitForIdle()
        onNodeWithText(weekLabel).performClick()
        waitForIdle()
        assertEquals(SearchModifiedPreset.LAST_7_DAYS, searchState.filters.modifiedPreset)

        // 强制触发多次重组，验证预设不会因 Instant.now() 毫秒变化而失选，且顶部筛选计数同步更新为 2。
        runOnUiThread { recompositionTick += 1 }
        waitForIdle()
        runOnUiThread { recompositionTick += 1 }
        waitForIdle()
        assertEquals(SearchModifiedPreset.LAST_7_DAYS, searchState.filters.modifiedPreset)
        onNodeWithText(getString(Res.string.label_search_advanced_filters, 2)).assertIsDisplayed()
        saveScreenshot("02-search-modified-week-after.png", onRoot().captureToImage())
    }

    /** 验证任务中心仅对 `canRetry == true` 的失败任务显示重试按钮（`OPS-008`）。 */
    @Test
    fun taskCenterShowsRetryIconOnlyForRetryableFailedTasks() = runDesktopComposeUiTest(
        width = 640,
        height = 360,
    ) {
        val tasks = listOf(
            BackgroundTask(
                id = "non-retryable",
                kind = BackgroundTaskKind.DELETE,
                title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Delete 3 items"),
                status = BackgroundTaskStatus.FAILED,
                canRetry = false,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Access denied"),
            ),
            BackgroundTask(
                id = "retryable-copy",
                kind = BackgroundTaskKind.COPY,
                title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Copy 2 items"),
                status = BackgroundTaskStatus.FAILED,
                canRetry = true,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Network timeout"),
            ),
        )
        val retriedIds = mutableListOf<String>()

        setContent {
            TestOnyxShell {
                TaskCenterList(
                    tasks = tasks,
                    listState = rememberLazyListState(),
                    onPauseTask = {},
                    onResumeTask = {},
                    onRetryTask = { id -> retriedIds += id },
                    onCancelTask = {},
                    onDismissTask = {},
                )
            }
        }

        waitForIdle()
        val retryDesc = getString(Res.string.action_retry_task)
        onAllNodesWithContentDescription(retryDesc).assertCountEquals(1)
        onNodeWithContentDescription(retryDesc).performClick()
        waitForIdle()
        assertEquals(listOf("retryable-copy"), retriedIds)
    }

    /** 验证破坏性确认框与冲突解决框的 `Tab` / `Shift+Tab` 循环切换和 `NumPadEnter` 提交（`OPS-004` + `F-11`）。 */
    @Test
    fun confirmationDialogsCycleFocusWithTabAndConfirmWithNumPadEnter() = runDesktopComposeUiTest(
        width = 320,
        height = 200,
    ) {
        var destructiveFocus by mutableIntStateOf(0)
        var confirmed = 0
        var dismissed = 0
        var conflictFocus by mutableIntStateOf(0)
        var chosenStrategy: TransferConflictStrategy? = null
        var mode by mutableStateOf("destructive")

        setContent {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("dialog-key-probe")
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (mode == "destructive") {
                            handleDestructiveDialogKeyEvent(
                                event = event,
                                focusedButton = destructiveFocus,
                                onFocusChange = { destructiveFocus = it },
                                onConfirm = { confirmed += 1 },
                                onDismiss = { dismissed += 1 },
                            )
                        } else {
                            handleConflictDialogKeyEvent(
                                event = event,
                                focusedButton = conflictFocus,
                                applyToAll = true,
                                onFocusChange = { conflictFocus = it },
                                onResolve = { strategy, _ -> chosenStrategy = strategy },
                                onDismiss = {},
                            )
                        }
                    },
            )
        }

        waitForIdle()
        onNodeWithTag("dialog-key-probe").performKeyInput {
            pressKey(Key.Tab)
        }
        waitForIdle()
        assertEquals(1, destructiveFocus, "按 Tab 必须从取消(0)切换到确认(1)，不能锁死在 0")

        onNodeWithTag("dialog-key-probe").performKeyInput {
            pressKey(Key.NumPadEnter)
            pressKey(Key.Tab)
        }
        waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
        assertEquals(0, destructiveFocus)

        // 切换验证冲突对话框 Shift+Tab 反向循环从 0 回到 3（覆盖），并按 NumPadEnter 触发 OVERWRITE。
        runOnUiThread { mode = "conflict" }
        waitForIdle()
        onNodeWithTag("dialog-key-probe").performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                pressKey(Key.Tab)
            }
            pressKey(Key.NumPadEnter)
        }
        waitForIdle()
        assertEquals(3, conflictFocus)
        assertEquals(TransferConflictStrategy.OVERWRITE, chosenStrategy)
    }

    /** 验证右键菜单真实键盘流：跳过分隔线与禁用项、展开/收起子菜单、NumPadEnter 执行且拦截 Delete 穿透（`F-06` + `F-11`）。 */
    @Test
    fun contextMenuKeyboardNavigationHandlesSubmenusNumPadEnterAndBlocksLeak() = runDesktopComposeUiTest(
        width = 640,
        height = 420,
    ) {
        val model = PaneContextMenuModel(
            nodes = listOf(
                PaneContextMenuNode.Item(
                    id = "disabled-item",
                    displayName = "Disabled",
                    icon = PaneContextMenuIcon.OPEN,
                    enabled = false,
                    command = PaneContextMenuCommand.Pane(PaneCommand.RENAME_SELECTION),
                ),
                PaneContextMenuNode.Divider,
                PaneContextMenuNode.Item(
                    id = "open",
                    text = PaneContextMenuText.OPEN,
                    icon = PaneContextMenuIcon.OPEN,
                    command = PaneContextMenuCommand.Pane(PaneCommand.OPEN_SELECTION),
                ),
                PaneContextMenuNode.Item(
                    id = "archive",
                    text = PaneContextMenuText.ARCHIVE,
                    icon = PaneContextMenuIcon.ARCHIVE,
                    children = listOf(
                        PaneContextMenuNode.Item(
                            id = "extract-here",
                            text = PaneContextMenuText.EXTRACT_HERE,
                            icon = PaneContextMenuIcon.EXTRACT,
                            command = PaneContextMenuCommand.ExtractSelection,
                        ),
                    ),
                ),
            ),
        )
        var executed: PaneContextMenuCommand? = null
        var closed = false
        var leakedDeleteCount = 0

        setContent {
            TestOnyxShell {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Delete) {
                                leakedDeleteCount += 1
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    PaneContextMenu(
                        anchorOffset = androidx.compose.ui.unit.IntOffset(40, 40),
                        model = model,
                        onCommand = { executed = it },
                        onClose = { closed = true },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("pane-context-menu").performKeyInput {
            pressKey(Key.Delete)
        }
        waitForIdle()
        assertEquals(0, leakedDeleteCount, "右键菜单打开时 Delete 必须被菜单消费，绝不能穿透到底层")
        assertEquals(null, executed)

        // ↓（跳过 disabled 和分隔线到 open）-> ↓（archive）-> →（展开子菜单 extract-here）-> ←（收起）-> → -> NumPadEnter。
        onNodeWithTag("pane-context-menu").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
            pressKey(Key.NumPadEnter)
        }
        waitForIdle()
        assertEquals(PaneContextMenuCommand.ExtractSelection, executed)

        onNodeWithTag("pane-context-menu").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        assertTrue(closed)
    }

    /** 验证小键盘 `Key.NumPadEnter` 匹配内置命令及 Quick Open 空结果立即触发搜索（`F-11` + `SEARCH-002`）。 */
    @Test
    fun numPadEnterMatchesCommandsAndQuickOpenExecutesImmediateSearchWhenResultsEmpty() = runDesktopComposeUiTest(
        width = 320,
        height = 200,
    ) {
        var matchedOpenCommand = false
        var recordedShortcut: OnyxShortcut? = null
        var immediateSearchCount = 0

        setContent {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("numpad-probe")
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                            matchedOpenCommand = event.matchesCommand(OnyxCommand.OpenSelection)
                            recordedShortcut = event.toOnyxShortcutOrNull()
                        }
                        handleQuickOpenKeyboardEvent(
                            event = event,
                            results = emptyList(),
                            selectedIndex = 0,
                            actions = QuickOpenPanelActions(
                                onQueryChange = {},
                                onExecuteSearch = { immediateSearchCount += 1 },
                                onClose = {},
                                onOpenResult = {},
                                onOpenResultInFolder = {},
                            ),
                            onSelectedIndexChange = {},
                            query = "invoice",
                        )
                    },
            )
        }

        waitForIdle()
        onNodeWithTag("numpad-probe").performKeyInput {
            pressKey(Key.NumPadEnter)
        }
        waitForIdle()
        assertTrue(matchedOpenCommand)
        assertEquals(OnyxShortcut(OnyxShortcutKey.ENTER), recordedShortcut)
        assertEquals(1, immediateSearchCount)
    }

    /** 验证 Command Palette 支持 End 跳转并滚动到可见位置且以小键盘回车执行命令（`SEARCH-003` + `F-11`）。 */
    @Test
    fun commandPaletteScrollsToSelectedItemAndExecutesOnNumPadEnter() = runDesktopComposeUiTest(
        width = 640,
        height = 420,
    ) {
        val items = (1..20).map { index ->
            CommandPaletteItem(
                target = CommandPaletteTarget.Custom("cmd-$index"),
                label = "Command Action $index",
                shortcut = null,
                iconKey = AllIconsKeys.Actions.Execute,
                enabled = true,
            )
        }
        var executedTarget: CommandPaletteTarget? = null

        setContent {
            TestOnyxShell {
                CommandPalettePopup(
                    items = items,
                    onExecute = { target -> executedTarget = target },
                    onClose = {},
                )
            }
        }

        waitForIdle()
        onNodeWithTag("command-palette-query").performKeyInput {
            pressKey(Key.MoveEnd)
        }
        waitForIdle()
        onNodeWithText("Command Action 20").assertIsDisplayed()
        onNodeWithTag("command-palette-query").performKeyInput {
            pressKey(Key.NumPadEnter)
        }
        waitForIdle()
        assertEquals(CommandPaletteTarget.Custom("cmd-20"), executedTarget)
    }

    /** 验证地址栏支持 `Ctrl+L` 请求编辑、切换 Tab 重置编辑态、失焦保草稿重入、鼠标点击建议项以及 Tab 补全 + NumPadEnter 导航（`F-12`）。 */
    @Test
    fun addressBarResetsOnTabSwitchAndSupportsFocusRequestAndAutocomplete() = runDesktopComposeUiTest(
        width = 640,
        height = 240,
    ) {
        var tabId by mutableStateOf("tab-a")
        var location by mutableStateOf("C:\\work")
        var focusReq by mutableIntStateOf(0)
        var editingState by mutableStateOf(false)
        val openedLocations = mutableListOf<String>()
        val outsideFocusRequester = FocusRequester()

        setContent {
            TestOnyxShell {
                Column(Modifier.fillMaxSize()) {
                    HybridAddressBar(
                        location = location,
                        onActivate = {},
                        onOpenLocation = { next -> openedLocations += next },
                        buildBreadcrumbs = { loc -> listOf(VfsBreadcrumb(loc, loc)) },
                        onEditingChange = { editingState = it },
                        tabId = tabId,
                        focusRequestId = focusReq,
                        knownLocations = listOf("C:\\work\\Projects", "C:\\work\\Downloads"),
                        suggestLocations = { input, known ->
                            known.filter { it.startsWith(input, ignoreCase = true) }
                        },
                    )
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .testTag("address-bar-outside-focus")
                            .focusRequester(outsideFocusRequester)
                            .focusable(),
                    )
                }
            }
        }

        waitForIdle()
        assertFalse(editingState)

        // 模拟 Ctrl+L / Alt+D 触发编辑态。
        runOnUiThread { focusReq += 1 }
        waitForIdle()
        assertTrue(editingState)
        onNodeWithTag("address-bar-input").assertIsDisplayed()

        // 切换标签页必须自动退出编辑态并同步回调 false。
        runOnUiThread {
            tabId = "tab-b"
            location = "C:\\other"
        }
        waitForIdle()
        assertFalse(editingState)
        onNodeWithTag("address-bar-breadcrumbs").assertIsDisplayed()

        // 再次通过 focusRequestId 进入编辑并验证 Tab 补全与 NumPadEnter 提交。
        runOnUiThread {
            location = "C:\\work\\Pr"
            focusReq += 1
        }
        waitForIdle()
        mainClock.advanceTimeBy(250L)
        waitForIdle()
        onNodeWithTag("address-bar-input").performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.NumPadEnter)
        }
        waitForIdle()
        assertEquals(listOf("C:\\work\\Projects"), openedLocations)

        // 验证鼠标点击补全建议项可直接导航。
        runOnUiThread {
            location = "C:\\work\\Do"
            focusReq += 1
        }
        waitForIdle()
        mainClock.advanceTimeBy(250L)
        waitForIdle()
        onNodeWithText("C:\\work\\Downloads").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf("C:\\work\\Projects", "C:\\work\\Downloads"), openedLocations)

        // 验证临时失焦后保留未提交草稿并在重进编辑时恢复，而按 Esc 才真正丢弃草稿。
        runOnUiThread { focusReq += 1 }
        waitForIdle()
        onNodeWithTag("address-bar-input").performTextReplacement("C:\\work\\draft-keep")
        waitForIdle()
        runOnUiThread { outsideFocusRequester.requestFocus() }
        waitForIdle()
        assertFalse(editingState, "失焦后应退出编辑态")

        runOnUiThread { focusReq += 1 }
        waitForIdle()
        assertTrue(editingState)
        onNodeWithTag("address-bar-input").assertTextContains("C:\\work\\draft-keep")

        onNodeWithTag("address-bar-input").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        assertFalse(editingState)

        runOnUiThread { focusReq += 1 }
        waitForIdle()
        onNodeWithTag("address-bar-input").assertTextContains("C:\\work\\Do")
    }

    /** 验证多列画廊按行滚动时越出首屏的下一行首列条目能完整滚入视口（`REV-2`）。 */
    @Test
    fun multiColumnGalleryScrollIntoViewRevealsNextRowFirstColumnItem() = runDesktopComposeUiTest(
        width = 660,
        height = 360,
    ) {
        val entries = (0 until 24).map { idx ->
            VFile(
                id = "img-$idx",
                name = "photo-$idx.png",
                location = "C:\\gallery\\photo-$idx.png",
                parentLocation = "C:\\gallery",
                kind = VFileKind.FILE,
                sizeBytes = 1024L,
                modifiedAtEpochMillis = 0L,
                hidden = false,
                capabilities = emptySet(),
            )
        }
        var pendingScroll by mutableStateOf<String?>(null)
        var reportedColumns by mutableIntStateOf(1)

        setContent {
            TestOnyxShell {
                Box(Modifier.fillMaxSize().background(LocalOnyxPalette.current.surface)) {
                    PaneEntriesContent(
                        model = PaneEntriesViewModel(
                            viewMode = ViewMode.GALLERY,
                            columns = DetailsColumn.entries,
                            columnWeights = emptyMap(),
                            hiddenColumns = emptySet(),
                            sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING),
                            selectedEntryIds = setOf(pendingScroll ?: "img-0"),
                            entriesState = PaneEntriesState.Ready(entries),
                            paneActive = true,
                            contextMenuVisible = false,
                            paneId = PaneId.PRIMARY,
                            fileDropTarget = null,
                            inlineEditState = null,
                            galleryItemSizeDp = 150,
                            pendingScrollToEntryId = pendingScroll,
                        ),
                        actions = testEntriesActions(
                            onSelectEntry = { _, _, _ -> },
                            onSelectEntries = {},
                            onNavigateUp = {},
                        ).copy(
                            onConsumeScroll = { pendingScroll = null },
                            onViewportMetricsChanged = { cols, _ -> reportedColumns = cols },
                        ),
                        thumbnails = testThumbnails(),
                    )
                }
            }
        }

        waitForIdle()
        assertTrue(reportedColumns >= 3, "660dp 宽 150dp 画廊应至少测得 3~4 列，实际=$reportedColumns")

        // 依次请求滚动到越出首屏的后续行首列项，验证每次都能完整进入视口并消费滚动请求。
        val nextRowFirstCol = reportedColumns * 3
        runOnUiThread { pendingScroll = "img-$nextRowFirstCol" }
        waitForIdle()
        onNodeWithText("photo-$nextRowFirstCol.png").assertIsDisplayed()
        assertEquals(null, pendingScroll)

        val furtherRowFirstCol = (reportedColumns * 4).coerceAtMost(entries.lastIndex)
        runOnUiThread { pendingScroll = "img-$furtherRowFirstCol" }
        waitForIdle()
        onNodeWithText("photo-$furtherRowFirstCol.png").assertIsDisplayed()
        assertEquals(null, pendingScroll)
    }

    /** 验证右键菜单移入子菜单再移出自动关闭不卡死，且滑向兄弟子菜单不会被旧延迟闭包误关（`REV-3`）。 */
    @Test
    fun contextMenuSubmenuClosesCleanlyOnExitAndDoesNotCloseSiblingSubmenu() = runDesktopComposeUiTest(
        width = 700,
        height = 420,
    ) {
        val model = PaneContextMenuModel(
            nodes = listOf(
                PaneContextMenuNode.Item(
                    id = "submenu-a",
                    displayName = "Submenu Alpha",
                    icon = PaneContextMenuIcon.ARCHIVE,
                    children = listOf(
                        PaneContextMenuNode.Item(
                            id = "child-a",
                            displayName = "Alpha Child",
                            icon = PaneContextMenuIcon.EXTRACT,
                            command = PaneContextMenuCommand.ExtractSelection,
                        ),
                    ),
                ),
                PaneContextMenuNode.Item(
                    id = "submenu-b",
                    displayName = "Submenu Beta",
                    icon = PaneContextMenuIcon.FOLDER,
                    children = listOf(
                        PaneContextMenuNode.Item(
                            id = "child-b",
                            displayName = "Beta Child",
                            icon = PaneContextMenuIcon.OPEN,
                            command = PaneContextMenuCommand.OpenTerminal,
                        ),
                    ),
                ),
            ),
        )

        setContent {
            TestOnyxShell {
                Box(Modifier.fillMaxSize().testTag("context-menu-backdrop")) {
                    PaneContextMenu(
                        anchorOffset = androidx.compose.ui.unit.IntOffset(20, 20),
                        model = model,
                        onCommand = {},
                        onClose = {},
                    )
                }
            }
        }

        waitForIdle()

        // 1. 鼠标移入 Alpha -> 移入子菜单项 Alpha Child -> 移到外部空白区，等待 300ms 后子菜单必须自动关闭。
        onNodeWithText("Submenu Alpha").performMouseInput {
            moveTo(center)
        }
        waitForIdle()
        onNodeWithText("Alpha Child").assertIsDisplayed()
        onNodeWithText("Alpha Child").performMouseInput {
            moveTo(center)
        }
        waitForIdle()
        onNodeWithTag("context-menu-backdrop").performMouseInput {
            moveTo(Offset(680f, 380f))
        }
        mainClock.advanceTimeBy(300L)
        waitForIdle()
        onAllNodesWithText("Alpha Child").assertCountEquals(0)

        // 2. 移入 Alpha 后立刻滑到兄弟子菜单 Beta，等待 300ms 后 Beta 的子菜单必须保持打开不被 Alpha 的旧计时器误关。
        onNodeWithText("Submenu Alpha").performMouseInput {
            moveTo(center)
        }
        waitForIdle()
        onNodeWithText("Alpha Child").assertIsDisplayed()
        onNodeWithText("Submenu Beta").performMouseInput {
            moveTo(center)
        }
        waitForIdle()
        mainClock.advanceTimeBy(300L)
        waitForIdle()
        onNodeWithText("Beta Child").assertIsDisplayed()
    }

    /** 验证从 Command Palette 执行 FocusAddressBar 后地址栏不会被面板焦点恢复效应抢焦关闭（`REV-4`）。 */
    @Test
    fun commandPaletteFocusAddressBarKeepsAddressBarEditingOpen() = runDesktopComposeUiTest(
        width = 860,
        height = 460,
    ) {
        val initialTab = createDefaultPaneTabState("tab-1", "work", "C:\\work", ViewMode.DETAILS)
            .withTabState { it.copy(entriesState = PaneEntriesState.Ready(emptyList())) }
        val paneStateFlow = MutableStateFlow(
            initialTab.toPaneState(PaneId.PRIMARY, "tab-1").let { pane ->
                pane.copy(chromeState = pane.chromeState.copy(commandPaletteVisible = true))
            },
        )
        val stubPaneComponent = object : PaneComponent {
            override val state: StateFlow<PaneState> = paneStateFlow
            override val tabOrder: StateFlow<List<String>> = MutableStateFlow(listOf("tab-1"))
            override val tabStates: StateFlow<List<PaneTabState>> = MutableStateFlow(listOf(initialTab))
            override val tabStack: Value<ChildStack<TabConfig, TabComponent>> =
                MutableValue(ChildStack(initialTab.toTabSnapshot().toTabConfig(), object : TabComponent {
                    override val state: StateFlow<PaneTabState> = MutableStateFlow(initialTab)
                    override fun updateState(state: PaneTabState) = Unit
                }))

            override fun dispatch(intent: PaneIntent) {
                when (intent) {
                    PaneIntent.HideCommandPalette -> paneStateFlow.value = paneStateFlow.value.copy(
                        chromeState = paneStateFlow.value.chromeState.copy(commandPaletteVisible = false),
                    )
                    else -> Unit
                }
            }

            override fun detachTab(tabId: String): PaneTabTransfer? = null
            override fun attachTab(tabSnapshot: PaneTabTransfer, targetIndex: Int) = Unit
            override fun restoreSession(snapshot: PaneSessionSnapshot) = Unit
            override fun captureRemoval(entries: List<VFile>): PaneRemovalContext? = null
            override fun completeRemoval(context: PaneRemovalContext, removedIds: Set<String>) = Unit
        }

        setContent {
            val currentPaneState by paneStateFlow.collectAsState()
            val tabZones = remember { androidx.compose.runtime.mutableStateMapOf<PaneId, TabDropZone>() }
            val fileZones = remember { androidx.compose.runtime.mutableStateMapOf<String, FileDropZone>() }
            TestOnyxShell {
                PaneSurface(
                    model = PaneSurfaceModel(
                        state = currentPaneState,
                        active = true,
                        component = stubPaneComponent,
                        actions = PaneActions(
                            onDeleteSelection = {},
                            onExtractSelection = {},
                            onExtractToDirectory = {},
                            onExtractSmart = {},
                            onCreateZipArchive = {},
                            onBatchRename = {},
                            onCopySelection = {},
                            onCutSelection = {},
                            onPaste = {},
                            canUndo = false,
                            canRedo = false,
                            onUndo = {},
                            onRedo = {},
                            onBeginCreateDirectory = {},
                            onToggleFavoriteLocation = {},
                            onOpenSettings = {},
                            supportsContextMenuOpenWith = { false },
                            onFileContextMenuCommand = { _, _ -> },
                            onOpenTerminal = {},
                            isArchiveFileName = { false },
                            isImageFileName = { false },
                        ),
                        commandShortcuts = OnyxCommandShortcutMap.Default,
                        canPaste = false,
                        favoriteLocations = emptyList(),
                        onActivate = {},
                    ),
                    dragBindings = PaneDragBindings(
                        tabDropZones = tabZones,
                        fileDropZones = fileZones,
                        tabDropTarget = null,
                        fileDropTarget = null,
                        onTabDrop = { _, _, _ -> },
                        onTabDragPositionChange = {},
                        onTabDragEnd = {},
                        onFileDragStart = { _, _ -> },
                        onFileDragPositionChange = {},
                        onFileDragEnd = {},
                    ),
                    services = PaneSurfaceServices(
                        loadThumbnail = { _, _ -> null },
                        loadArchiveThumbnail = { _, _ -> null },
                        readFileHash = { error("unused") },
                        readArchiveInfo = { error("unused") },
                        buildBreadcrumbs = { loc -> listOf(VfsBreadcrumb(loc, loc)) },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        waitForIdle()
        val focusAddressLabel = getString(Res.string.action_focus_address_bar)
        onNodeWithTag("command-palette-query").performTextInput(focusAddressLabel)
        waitForIdle()
        onNodeWithTag("command-palette-query").performKeyInput {
            pressKey(Key.Enter)
        }
        waitForIdle()
        mainClock.advanceTimeBy(100L)
        waitForIdle()
        assertFalse(paneStateFlow.value.commandPaletteVisible)
        onNodeWithTag("address-bar-input").assertIsDisplayed()
    }

    /** 验证 Quick Open 有输入文本时不劫持普通 Home/End，仅空查询或 Ctrl+End 跳转结果列表（`REV-6`）。 */
    @Test
    fun quickOpenPreservesTextCursorHomeEndWhenQueryIsNonEmpty() = runDesktopComposeUiTest(
        width = 320,
        height = 200,
    ) {
        val sampleResults = (0..4).map { idx ->
            VFile("r-$idx", "item-$idx.txt", "/r/item-$idx.txt", "/r", VFileKind.FILE, 1, 0, false, emptySet())
        }
        var selected by mutableIntStateOf(0)
        var homeHandledForNonEmptyQuery: Boolean? = null
        var ctrlEndHandledForNonEmptyQuery: Boolean? = null

        setContent {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("quickopen-home-end-probe")
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val handled = handleQuickOpenKeyboardEvent(
                            event = event,
                            results = sampleResults,
                            selectedIndex = selected,
                            actions = QuickOpenPanelActions({}, {}, {}, {}, {}),
                            onSelectedIndexChange = { selected = it },
                            query = "report",
                        )
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.MoveHome -> homeHandledForNonEmptyQuery = handled
                                Key.MoveEnd -> ctrlEndHandledForNonEmptyQuery = handled
                            }
                        }
                        handled
                    },
            )
        }

        waitForIdle()
        onNodeWithTag("quickopen-home-end-probe").performKeyInput {
            pressKey(Key.MoveHome)
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.MoveEnd)
            }
        }
        waitForIdle()
        assertEquals(false, homeHandledForNonEmptyQuery, "输入框有文本时普通 Home 必须放行给文本光标")
        assertEquals(true, ctrlEndHandledForNonEmptyQuery, "Ctrl+End 应跳转到结果列表末项")
        assertEquals(4, selected)
    }

    /** 统一测试用暗色 Jewel 主题、语言环境与 Tooltip 宿主包装器。 */
    @Composable
    private fun TestOnyxShell(content: @Composable () -> Unit) {
        val preferences = remember { MutableStateFlow(OnyxSettings(themeMode = OnyxThemeMode.DARK)) }
        val localeController = remember(preferences) { JvmApplicationLocaleController(preferences) }
        val localeScope = rememberCoroutineScope()
        DisposableEffect(localeController, localeScope) {
            val binding = localeController.bind(localeScope)
            onDispose(binding::cancel)
        }
        val currentSettings by preferences.collectAsState()
        OnyxLocaleEnvironment(localeController.appliedLocale) {
            OnyxWindowTheme(currentSettings) {
                OnyxTooltipHost {
                    content()
                }
            }
        }
    }

    private fun testEntriesActions(
        onSelectEntry: (String, Boolean, Boolean) -> Unit,
        onSelectEntries: (Set<String>) -> Unit,
        onNavigateUp: () -> Unit,
    ): PaneEntriesActions = PaneEntriesActions(
        onActivate = {},
        onOpenEntry = {},
        onToggleSort = {},
        onResizeColumn = { _, _ -> },
        onToggleColumnVisibility = {},
        onSelectEntry = onSelectEntry,
        onStartFileDrag = { _, _ -> },
        onFileDragPositionChange = {},
        onFileDragEnd = {},
        onFileDropZoneChange = {},
        onShowContextMenu = { _, _, _ -> },
        onDismissContextMenu = {},
        onUpdateInlineEditDraft = {},
        onConfirmInlineEdit = {},
        onCancelInlineEdit = {},
        onSelectEntries = onSelectEntries,
        onNavigateUp = onNavigateUp,
    )

    private fun testThumbnails(): GalleryThumbnailServices = GalleryThumbnailServices(
        loadThumbnail = { _, _ -> null },
        loadArchiveThumbnail = { _, _ -> null },
        isImageFileName = { false },
        isArchiveFileName = { false },
    )

    private fun saveScreenshot(fileName: String, image: androidx.compose.ui.graphics.ImageBitmap) {
        val dir = Path.of("build", "batch2-screenshots")
        Files.createDirectories(dir)
        ImageIO.write(image.toAwtImage(), "png", dir.resolve(fileName).toFile())
    }
}
