@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.SidebarTreeNode
import com.oruke.onyx.app.component.SidebarTreeNodeLoadState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.app.component.withBatchRenamePreview
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.ImageLoadOutcome
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.Color as AwtColor
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch 4 UI 交互与 Skia 真实渲染截图验收测试。
 *
 * 覆盖：
 * - Preview/Inspector 分段页签面板与截图 `02`（F-07 / F-14）
 * - ImageViewer 悬停边缘箭头、错误卡片重试/外部打开、显式关闭按钮、快捷键与截图 `01`（F-08 / F-10 / VIS-03）
 * - TabBar 中键关闭与右键菜单（F-15）
 * - Sidebar 单一 LazyColumn 滚动与右键菜单（F-17）
 * - BatchRename 禁用态/空预设名校验/执行期取消按钮（OPS-012）
 * - ArchivePasswordDialog 重试解锁与防连击提交锁（OPS-013）
 * - TaskErrorSection 超过 120 条错误截断脚标（OPS-016）
 * - QuickOpen 最近位置列表键盘导航与截图 `03`（SEARCH-006 / VIS-03）
 * - CommandPalette 底部留白与 `切换预览面板` 条目及截图 `04`（F-07 / VIS-03）
 * - SearchPanel 互斥大小预设、自定义 MB 输入与 Ctrl+F 重聚输入框（SEARCH-008）
 * - ColorOverrideField `allowAlpha=false` 隐藏 Alpha 滑块及浅色设置外观截图 `05`（THEME-02 / VIS-03）
 */
class Batch4UiAndVisualVerificationTest {
    /**
     * F-08 + F-10 + VIS-03: 验证图片查看器真图渲染、显式关闭按钮、`F11` 全屏回调，
     * 以及结构化错误卡片的「重试」与「用系统默认应用打开」恢复路径，并输出 After 截图 `01`。
     */
    @Test
    fun imageViewerRendersUpgradedToolbarHandlesShortcutsAndStructuredErrors() =
        runDesktopComposeUiTest(width = 1100, height = 680) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val sampleBitmap = createDemoLandscapeBitmap(800, 500)
            val file1 = sampleEntry("img-1", "mount-fuji-sunset.png", 1_480_220L)
            val file2 = sampleEntry("img-2", "tokyo-night-neon.png", 2_120_880L)
            var currentIndex by mutableIntStateOf(0)
            var closedCount by mutableIntStateOf(0)
            var fullscreenToggled by mutableIntStateOf(0)
            var externalOpenedFile by mutableStateOf<VFile?>(null)
            var failFirstLoad by mutableStateOf(false)
            var loadAttempts by mutableIntStateOf(0)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        val activeFile = if (currentIndex == 0) file1 else file2
                        ImageViewerContent(
                            state = ImageViewerState(
                                visible = true,
                                currentFile = activeFile,
                                allImages = listOf(file1, file2),
                                currentIndex = currentIndex,
                            ),
                            onClose = { closedCount += 1 },
                            onNext = { currentIndex = (currentIndex + 1).coerceAtMost(1) },
                            onPrevious = { currentIndex = (currentIndex - 1).coerceAtLeast(0) },
                            onSetZoom = {},
                            onSetFitMode = {},
                            onRotate = {},
                            loadThumbnail = { _, _ -> sampleBitmap },
                            readImageSize = { IntSize(1920, 1200) },
                            loadViewerImage = { _, _ ->
                                loadAttempts += 1
                                if (failFirstLoad) {
                                    ImageLoadOutcome.TooLarge(
                                        limitBytes = 50L * 1024 * 1024,
                                        actualBytes = 68L * 1024 * 1024,
                                    )
                                } else {
                                    ImageLoadOutcome.Loaded(sampleBitmap)
                                }
                            },
                            onOpenExternally = { externalOpenedFile = it },
                            onToggleFullscreen = { fullscreenToggled += 1 },
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("image-viewer-close-button").assertIsDisplayed()
            onNodeWithTag("image-viewer-fit-button").assertIsDisplayed()
            onNodeWithTag("image-viewer-actual-button").assertIsDisplayed()

            // 鼠标悬停到画布以展示边缘翻页箭头后抓取新版图片查看器真实截图
            onRoot().performMouseInput { moveTo(Offset(550f, 340f)) }
            waitForIdle()
            onNodeWithTag("image-viewer-edge-next").assertIsDisplayed()
            saveBatch4Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "01-image-viewer-upgraded-dark-100-after.png",
            )

            // 验证显式关闭按钮与 F11 全屏快捷键
            onNodeWithTag("image-viewer-close-button").performClick()
            waitForIdle()
            assertEquals(1, closedCount)

            onRoot().performKeyInput { pressKey(Key.F11) }
            waitForIdle()
            assertEquals(1, fullscreenToggled)

            // 切到下一张并模拟 TooLarge 失败 -> 验证结构化错误卡片、外部打开与重试恢复
            failFirstLoad = true
            currentIndex = 1
            waitForIdle()
            onNodeWithTag("image-viewer-error-card").assertIsDisplayed()
            onNodeWithTag("image-viewer-open-external-button").performClick()
            waitForIdle()
            assertEquals(file2.id, externalOpenedFile?.id)

            failFirstLoad = false
            val attemptsBeforeRetry = loadAttempts
            onNodeWithTag("image-viewer-retry-button").performClick()
            waitForIdle()
            assertTrue(loadAttempts > attemptsBeforeRetry, "点击重试应重新触发加载")
            assertEquals(0, onAllNodesWithTag("image-viewer-error-card").fetchSemanticsNodes().size)
        }

    /**
     * F-07 + F-14: 验证右侧统一 Preview / Inspector 分段页签切换、图片预览、检查器 SHA-256 与复制按钮，
     * 并输出 After 截图 `02`。
     */
    @Test
    fun previewPaneSupportsSegmentedInspectorTabAndCapturesVisual() =
        runDesktopComposeUiTest(width = 920, height = 620) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val previewBitmap = createDemoLandscapeBitmap(480, 320)
            val selected = sampleEntry("photo-1", "release-diagram-2026.png", 842_190L)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(LocalOnyxPalette.current.appBackground),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(LocalOnyxPalette.current.surface),
                            )
                            PreviewPane(
                                selectedEntry = selected,
                                modifier = Modifier.width(320.dp).fillMaxHeight(),
                                loadThumbnail = { _, _ -> previewBitmap },
                                loadTextPreview = { PreviewTextResult.Unavailable },
                                isImageFileName = { it.endsWith(".png") },
                                isTextPreviewFileName = { false },
                                peekCachedThumbnail = { _, _, _, _ -> previewBitmap },
                                readFileHash = {
                                    FileHashResult.Hash(
                                        algorithm = "SHA-256",
                                        value = "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef",
                                    )
                                },
                                readArchiveInfo = { ArchiveInfoResult.Unavailable },
                                isArchiveFileName = { false },
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("preview-sidebar-tab-preview").assertIsDisplayed()
            onNodeWithTag("preview-sidebar-tab-inspector").assertIsDisplayed()
            saveBatch4Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "02-preview-inspector-panel-dark-100-after.png",
            )

            // 切换到「元数据」模式，验证按需点击「计算 SHA-256」后展示哈希值
            onNodeWithTag("preview-sidebar-tab-inspector").performClick()
            waitForIdle()
            onNodeWithTag("metadata-compute-sha256-button").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef").assertIsDisplayed()
        }

    /**
     * SEARCH-006 + VIS-03: 验证 Quick Open 在空查询时渲染最近访问位置列表并支持键盘方向键 + 回车直达，
     * 并输出 After 截图 `03`。
     */
    @Test
    fun quickOpenDisplaysRecentLocationsSupportsKeyboardNavigationAndCapturesVisual() =
        runDesktopComposeUiTest(width = 900, height = 560) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val recent = listOf(
                "C:\\Users\\oruke\\Projects\\onyx",
                "D:\\Media\\Wallpapers\\4K",
                "Z:\\Archive\\2026-Releases",
            )
            var openedLocation by mutableStateOf<String?>(null)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        QuickOpenPanel(
                            state = SearchPanelState(
                                visible = true,
                                query = "",
                                results = emptyList(),
                                status = SearchStatus.IDLE,
                            ),
                            locationLabel = { it },
                            onQueryChange = {},
                            onExecuteSearch = {},
                            onClose = {},
                            onOpenResult = {},
                            onOpenResultInFolder = {},
                            onSelectedIndexChange = {},
                            recentLocations = recent,
                            onOpenLocation = { openedLocation = it },
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("quick-open-recent-locations-header", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("quick-open-recent-item-0", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("quick-open-recent-item-1", useUnmergedTree = true).assertIsDisplayed()
            saveBatch4Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "03-quick-open-recent-dark-100-after.png",
            )

            // 下移一项并回车直达第 2 个最近位置
            onRoot().performKeyInput {
                pressKey(Key.DirectionDown)
                pressKey(Key.Enter)
            }
            waitForIdle()
            assertEquals("D:\\Media\\Wallpapers\\4K", openedLocation)
        }

    /**
     * F-07 + VIS-03: 验证命令面板包含「切换预览面板」并带底部呼吸留白，同时输出 After 截图 `04`。
     */
    @Test
    fun commandPaletteIncludesTogglePreviewPaneAndBottomPaddingVisual() =
        runDesktopComposeUiTest(width = 640, height = 440) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val items = listOf(
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.QuickOpen), "快速打开", "Ctrl+K", AllIconsKeys.Actions.Find, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.ShowSearchPanel), "全局搜索", "Ctrl+Shift+F", AllIconsKeys.Actions.Find, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.TogglePreviewPane), "切换预览面板", "Alt+P", AllIconsKeys.Actions.Preview, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.NewFile), "新建文件", "Ctrl+N", AllIconsKeys.Actions.AddFile, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.NewDirectory), "新建文件夹", "Ctrl+Shift+N", AllIconsKeys.Nodes.Folder, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.OpenSettings), "打开设置", "Ctrl+,", AllIconsKeys.General.Settings, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.RenameSelection), "重命名", "F2", AllIconsKeys.Actions.Edit, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.ToggleFavorite), "收藏/取消收藏", "Ctrl+D", AllIconsKeys.Nodes.Favorite, true),
                CommandPaletteItem(CommandPaletteTarget.BuiltIn(OnyxCommand.Refresh), "刷新当前目录", "F5", AllIconsKeys.Actions.Refresh, true),
            )
            var executedTarget by mutableStateOf<CommandPaletteTarget?>(null)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(LocalOnyxPalette.current.appBackground),
                        ) {
                            CommandPalettePopup(
                                items = items,
                                onExecute = { executedTarget = it },
                                onClose = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("切换预览面板").assertIsDisplayed()
            saveBatch4Screenshot(
                onAllNodes(isRoot())[0].captureToImage().toAwtImage(),
                "04-command-palette-padding-dark-100-after.png",
            )

            onNodeWithText("切换预览面板").performClick()
            waitForIdle()
            assertEquals(CommandPaletteTarget.BuiltIn(OnyxCommand.TogglePreviewPane), executedTarget)
        }

    /**
     * THEME-02 + VIS-03: 验证 `ColorOverrideField(allowAlpha = false)` 隐藏透明度滑块、
     * `allowAlpha = true` 显示透明度滑块，并输出浅色设置外观页对比截图 `05`。
     */
    @Test
    fun colorOverrideFieldEnforcesAllowAlphaAndCapturesLightSettingsVisual() =
        runDesktopComposeUiTest(width = 900, height = 660) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var opaqueMode by mutableStateOf(true)
            var pickedArgb by mutableStateOf<Long?>(0xFFF8FAFCL)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.LIGHT)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(LocalOnyxPalette.current.appBackground)
                                .padding(16.dp),
                        ) {
                            ColorOverrideField(
                                label = "窗口主背景",
                                value = pickedArgb,
                                defaultPreviewColor = Color(0xFFFFFFFF),
                                initialExpanded = true,
                                allowAlpha = !opaqueMode,
                                onChange = { pickedArgb = it },
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 不透明槽位展开时不应渲染 Alpha 滑块
            assertEquals(
                0,
                onAllNodesWithTag("color-picker-alpha-section").fetchSemanticsNodes().size,
                "allowAlpha=false 时必须隐藏透明度滑块",
            )

            // 切到允许透明度的槽位后应展示 Alpha 滑块
            opaqueMode = false
            waitForIdle()
            onNodeWithTag("color-picker-alpha-section").assertIsDisplayed()
        }

    /**
     * VIS-03: 渲染浅色模式外观设置页（包含增强描边的浅色微缩主题卡片与浅色色块）并输出 After 截图 `05`。
     */
    @Test
    fun settingsLightThemeAppearanceRendersCrispPresetPreviewsAndSwatches() =
        runDesktopComposeUiTest(width = 920, height = 660) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var draft by mutableStateOf(
                OnyxSettings(
                    themeMode = OnyxThemeMode.LIGHT,
                    uiScale = 100,
                    preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
                ),
            )

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(draft) {
                        val appearance = LocalOnyxAppearance.current
                        SettingsDialogLayout(
                            title = "设置",
                            draft = draft,
                            category = SettingsCategory.THEME_APPEARANCE,
                            onCategoryChange = {},
                            onDraftChange = { draft = it },
                            onCleanupInvalidLocations = {},
                            onConfirm = {},
                            onDismiss = {},
                            bodyFontSize = appearance.listFontSize,
                            labelFontSize = appearance.headerFontSize,
                            showTitlePrefix = false,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("settings-content-scrollbar", useUnmergedTree = true).assertIsDisplayed()
            saveBatch4Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "05-settings-light-swatches-100-after.png",
            )
        }

    /**
     * F-15: 验证 TabBar 中键直接关闭标签，以及右键菜单触发「关闭其他标签 / 关闭右侧标签 / 复制路径」。
     */
    @Test
    fun tabBarSupportsMiddleClickCloseAndContextMenuActions() =
        runDesktopComposeUiTest(width = 760, height = 240) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val closedTabs = mutableListOf<String>()
            val closedOthers = mutableListOf<String>()
            val closedRight = mutableListOf<String>()
            val copiedTabIds = mutableListOf<String>()

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        PaneTabBar(
                            state = PaneTabBarState(
                                activeTabId = "t1",
                                tabs = listOf(
                                    PaneTabItemState("t1", "Alpha", "C:\\Alpha"),
                                    PaneTabItemState("t2", "Beta", "C:\\Beta"),
                                    PaneTabItemState("t3", "Gamma", "C:\\Gamma"),
                                ),
                            ),
                            active = true,
                            onActivate = {},
                            onSelectTab = {},
                            onCloseTab = { closedTabs += it },
                            onCreateTab = {},
                            onDropTab = { _, _ -> },
                            onDragPositionChange = {},
                            onDragEnd = {},
                            onDropZoneChange = {},
                            dropIndicatorIndex = null,
                            onCloseOtherTabs = { closedOthers += it },
                            onCloseTabsToRight = { closedRight += it },
                            onCopyTabPath = { copiedTabIds += it },
                        )
                    }
                }
            }
            waitForIdle()

            // 中键点击 Beta 标签直接关闭
            onNodeWithText("Beta").performMouseInput { click(button = MouseButton.Tertiary) }
            waitForIdle()
            assertEquals(listOf("t2"), closedTabs)

            // 右键点击 Alpha 标签并点击「复制路径」
            onNodeWithText("Alpha").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithTag("tab-context-menu").assertIsDisplayed()
            onNodeWithText("复制路径").performClick()
            waitForIdle()
            assertEquals(listOf("t1"), copiedTabIds)

            // 再次右键点击 Alpha 并点击「关闭右侧标签」
            onNodeWithText("Alpha").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithText("关闭右侧标签").performClick()
            waitForIdle()
            assertEquals(listOf("t1"), closedRight)

            // 再次右键点击 Beta 并点击「关闭其他标签」
            onNodeWithText("Beta").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithText("关闭其他标签").performClick()
            waitForIdle()
            assertEquals(listOf("t2"), closedOthers)
        }

    /**
     * REV-05 / F-15: 验证窄视口溢出标签栏动态新增并激活尾部新标签后，自动等待布局完成并滚动使新标签进入可视区。
     */
    @Test
    fun tabBarAutoScrollsNewlyCreatedOverflowTabIntoView() =
        runDesktopComposeUiTest(width = 360, height = 160) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var tabs by mutableStateOf(
                (1..6).map { idx -> PaneTabItemState("t$idx", "Workspace-Folder-$idx", "C:\\Workspace\\$idx") },
            )
            var activeTabId by mutableStateOf("t1")

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Box(modifier = Modifier.width(260.dp)) {
                            PaneTabBar(
                                state = PaneTabBarState(activeTabId = activeTabId, tabs = tabs),
                                active = true,
                                onActivate = {},
                                onSelectTab = { activeTabId = it },
                                onCloseTab = {},
                                onCreateTab = {},
                                onDropTab = { _, _ -> },
                                onDragPositionChange = {},
                                onDragEnd = {},
                                onDropZoneChange = {},
                                dropIndicatorIndex = null,
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 动态追加第 7 个溢出尾部标签并切换为活动标签
            tabs = tabs + PaneTabItemState("t7", "BrandNewTailTab", "C:\\Workspace\\BrandNewTailTab")
            activeTabId = "t7"
            waitForIdle()
            mainClock.advanceTimeBy(1_000L)
            waitForIdle()

            val newTabBounds = onNodeWithTag("pane-tab-chip-t7").getBoundsInRoot()
            assertTrue(
                newTabBounds.left < 260.dp && newTabBounds.right <= 264.dp,
                "溢出态新建尾部标签应自动滚入 260dp 标签栏可见区，实际 bounds=$newTabBounds",
            )
        }

    /**
     * F-17: 验证侧边栏全部区域与目录树均在单一 `LazyColumn`（`sidebar-list`）内渲染，并支持右键菜单动作。
     */
    @Test
    fun sidebarUnifiesScrollInSingleLazyColumnAndProvidesContextMenu() =
        runDesktopComposeUiTest(width = 480, height = 520) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val openedNewTabs = mutableListOf<String>()
            val copiedPaths = mutableListOf<String>()

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        PaneSidebar(
                            state = PaneSidebarState(
                                location = "C:\\work",
                                systemQuickAccessLocations = listOf(
                                    SystemQuickAccessLocation("桌面", "C:\\Users\\oruke\\Desktop"),
                                ),
                                favoriteLocations = listOf("D:\\Projects"),
                                recentLocations = listOf("E:\\Downloads"),
                                remoteConnections = listOf(
                                    RemoteConnectionProfile(
                                        id = "nas-1",
                                        name = "家庭 NAS",
                                        protocol = RemoteConnectionProtocol.SMB,
                                        location = "smb://nas.local/share/",
                                    ),
                                ),
                                treeState = SidebarTreeState(
                                    roots = listOf(
                                        SidebarTreeNode(
                                            location = "C:\\",
                                            label = "本地磁盘 (C:)",
                                            expanded = false,
                                            loadState = SidebarTreeNodeLoadState.IDLE,
                                            children = emptyList(),
                                        ),
                                    ),
                                ),
                                showTree = true,
                                collapsedSections = emptySet(),
                            ),
                            actions = PaneSidebarActions(
                                locationLabel = { it.substringAfterLast('\\').ifBlank { it } },
                                onActivate = {},
                                onOpenLocation = {},
                                onToggleFavoriteLocation = {},
                                onNewRemoteConnection = {},
                                onEditRemoteConnection = {},
                                onToggleTreeNode = {},
                                onRetryTreeNode = {},
                                onToggleSection = {},
                                onOpenInNewTab = { openedNewTabs += it },
                                onCopyPath = { copiedPaths += it },
                            ),
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("sidebar-list").assertIsDisplayed()
            onNodeWithTag("sidebar-scrollbar", useUnmergedTree = true).assertIsDisplayed()

            onNodeWithText("Projects").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithTag("sidebar-context-menu").assertIsDisplayed()
            onNodeWithText("在新标签页打开").performClick()
            waitForIdle()
            assertEquals(listOf("D:\\Projects"), openedNewTabs)

            onNodeWithText("Projects").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithText("复制路径").performClick()
            waitForIdle()
            assertEquals(listOf("D:\\Projects"), copiedPaths)
        }

    /**
     * OPS-012 + OPS-013 + OPS-016: 验证批量重命名禁用态与执行期取消按钮、压缩包密码防连击与重试解锁、
     * 以及任务中心超过 120 条错误时的截断提示脚标。
     */
    @Test
    fun opsDialogsAndTaskErrorsVerifyDisabledStateCancelLockAndTruncationFooter() =
        runDesktopComposeUiTest(width = 820, height = 620) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val intents = mutableListOf<RootIntent>()
            var confirmedCount by mutableIntStateOf(0)
            var renameState by mutableStateOf(
                RootDialogState.BatchRename(
                    paneId = PaneId.PRIMARY,
                    entries = listOf(sampleEntry("f1", "demo.txt", 128L)),
                ),
            )

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        val editor = BatchRenameEditorState(renameState) { nextDraft ->
                            renameState = renameState.copy(draft = nextDraft)
                        }
                        val canConfirm = editor.hasChanges && !isBatchRenameBlockingValidation(renameState.validationMessage)
                        BatchRenameContent(
                            state = renameState,
                            editor = editor,
                            canConfirm = canConfirm,
                            onConfirm = { confirmedCount += 1 },
                            onDismiss = {},
                            settings = OnyxSettings(),
                            onIntent = { intents += it },
                        )
                    }
                }
            }
            waitForIdle()

            // 无变更时「应用」按钮禁用，点击不会触发 onConfirm
            onNodeWithText("应用").performClick()
            waitForIdle()
            assertEquals(0, confirmedCount)

            // REV-03: 存在有效变更且仅触发了空预设名提示（MSG_RENAME_PRESET_NAME_REQUIRED）时，应用按钮不得被反锁
            renameState = renameState.copy(
                draft = renameState.draft.copy(
                    mode = com.oruke.onyx.core.model.BatchRenameEditMode.PREFIX_SUFFIX,
                    prefix = "v2_",
                ),
            ).withBatchRenamePreview().copy(
                validationMessage = I18nMessage(MessageKey.MSG_RENAME_PRESET_NAME_REQUIRED),
            )
            waitForIdle()
            onNodeWithText("应用").performClick()
            waitForIdle()
            assertEquals(1, confirmedCount, "空预设名保存提示不应禁用本身合法的批量重命名应用按钮")

            // 切换到执行中并绑定任务 ID -> 显示取消按钮并派发 CancelTask
            renameState = renameState.copy(executing = true, executingTaskId = "task-rename-99")
            waitForIdle()
            onNodeWithText("取消").performClick()
            waitForIdle()
            assertTrue(RootIntent.CancelTask("task-rename-99") in intents)
        }

    /**
     * OPS-013 + OPS-016: 验证压缩包密码弹窗提交防连击与错密重试自动解锁，以及 >120 条任务错误截断脚标。
     */
    @Test
    fun archivePasswordPreventsDoubleSubmitAndTaskErrorShowsTruncatedFooter() =
        runDesktopComposeUiTest(width = 680, height = 560) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val submittedPasswords = mutableListOf<String>()
            var attempt by mutableIntStateOf(0)
            var passwordError by mutableStateOf<I18nMessage?>(null)
            var verifying by mutableStateOf(false)
            var requestId by mutableStateOf("req-archive-1")
            val manyErrors = (1..135).map { idx ->
                TaskError(
                    fileName = "file-$idx.dat",
                    message = "Permission denied #$idx",
                )
            }
            val failedTask = BackgroundTask(
                id = "task-many-errors",
                kind = BackgroundTaskKind.COPY,
                title = I18nMessage(MessageKey.MSG_COPY_ITEMS, 135),
                detail = I18nMessage(MessageKey.MSG_COPY_FAILED, "Permission denied"),
                status = BackgroundTaskStatus.FAILED,
                errors = manyErrors,
            )

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                ArchivePasswordDialogContent(
                                    archiveName = "secret.7z",
                                    error = passwordError,
                                    onConfirm = { pw ->
                                        submittedPasswords += pw
                                        verifying = true
                                    },
                                    onDismiss = {},
                                    attempt = attempt,
                                    verifying = verifying,
                                    requestId = requestId,
                                )
                            }
                            TaskErrorSection(
                                task = failedTask,
                                expanded = true,
                                copiedFeedbackKey = null,
                                onToggleExpand = {},
                                onCopyErrorText = { _, _ -> },
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 输入密码并连按两次 Enter -> 防连击锁确保只提交 1 次
            onNodeWithTag("archive-password-input").performTextInput("wrong123")
            onNodeWithTag("archive-password-input").performKeyInput {
                pressKey(Key.Enter)
                pressKey(Key.Enter)
            }
            waitForIdle()
            assertEquals(listOf("wrong123"), submittedPasswords)

            // 模拟后台校验失败：verifying=false, attempt+1, error 非空 -> 自动解锁，再次输入并提交成功触发第二次回调
            verifying = false
            attempt = 1
            passwordError = I18nMessage(MessageKey.MSG_ARCHIVE_PASSWORD_INVALID)
            waitForIdle()
            onNodeWithTag("archive-password-input").performTextReplacement("correct456")
            onNodeWithTag("archive-password-input").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(listOf("wrong123", "correct456"), submittedPasswords)

            // REV-07: 同名压缩包切换到不同 requestId 时，密码草稿必须清空隔离，按 Enter 不会误提交前一份密码
            verifying = false
            attempt = 0
            passwordError = null
            requestId = "req-archive-2"
            waitForIdle()
            onNodeWithTag("archive-password-input").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(listOf("wrong123", "correct456"), submittedPasswords)

            // 验证超过 120 条任务错误时的截断提示脚标存在
            assertEquals(
                1,
                onAllNodesWithTag("task-errors-truncated-footer-task-many-errors").fetchSemanticsNodes().size,
                "展开超过 120 条错误时必须渲染截断提示脚标",
            )
        }

    /**
     * SEARCH-008: 验证搜索面板互斥大小预设、点击已选取消、自定义 Min/Max MB 输入及 Ctrl+F 随时重聚搜索框。
     */
    @Test
    fun searchPanelSizePresetsAreMutuallyExclusiveSupportCustomMbAndCtrlFRefocusesQuery() =
        runDesktopComposeUiTest(width = 780, height = 560) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var state by mutableStateOf(
                SearchPanelState(
                    visible = true,
                    rootLocation = "C:\\work",
                    query = "report",
                    advancedFiltersExpanded = true,
                    filters = SearchFilters(),
                    status = SearchStatus.COMPLETED,
                ),
            )
            val otherFocusRequester = FocusRequester()

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(10.dp)
                                    .focusRequester(otherFocusRequester)
                                    .focusable()
                                    .testTag("external-focus-sink"),
                            )
                            val listState = rememberLazyListState()
                            SearchPanel(
                                state = state,
                                presentation = SearchPanelPresentation(
                                    locationLabel = { it },
                                    searchHistory = emptyList(),
                                ),
                                actions = SearchPanelActions(
                                    onQueryChange = { state = state.copy(query = it) },
                                    onSearch = {},
                                    onCancel = {},
                                    onClose = {},
                                    onOpenResult = {},
                                    onOpenResultInFolder = {},
                                    onUpdateScope = {},
                                    onUpdateFilters = { state = state.copy(filters = it) },
                                    onSelectIndex = {},
                                    onSetAdvancedFiltersExpanded = {},
                                    onSetIncludeRemoteLocations = {},
                                    onSetKeepWindowOpen = {},
                                    onFocusMainWindow = {},
                                    onUseCurrentDirectory = {},
                                    onOpenResultsAsCollection = {},
                                    onClearHistory = {},
                                ),
                                listState = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 打开「大小」筛选面板
            onNodeWithText("大小").performClick()
            waitForIdle()

            // 点击 < 1MB -> 严格上限 1_048_575，再次点击取消
            onNodeWithTag("search-size-under-1mb").performClick()
            waitForIdle()
            assertEquals(null, state.filters.minSizeBytes)
            assertEquals(SEARCH_SIZE_UNDER_1_MB_MAX_BYTES, state.filters.maxSizeBytes)

            onNodeWithTag("search-size-under-1mb").performClick()
            waitForIdle()
            assertEquals(null, state.filters.minSizeBytes)
            assertEquals(null, state.filters.maxSizeBytes)

            // 点击 > 100MB -> 严格下限 104_857_601
            onNodeWithTag("search-size-over-100mb").performClick()
            waitForIdle()
            assertEquals(SEARCH_SIZE_OVER_100_MB_MIN_BYTES, state.filters.minSizeBytes)
            assertEquals(null, state.filters.maxSizeBytes)

            // 在自定义 Min/Max MB 输入框中输入 2 和 50 -> 准确写入字节上下限
            onNodeWithTag("search-size-min-mb").performTextReplacement("2")
            onNodeWithTag("search-size-max-mb").performTextReplacement("50")
            waitForIdle()
            assertEquals(2L * SEARCH_SIZE_1_MB_BYTES, state.filters.minSizeBytes)
            assertEquals(50L * SEARCH_SIZE_1_MB_BYTES, state.filters.maxSizeBytes)

            // 焦点位于输入框外时按下 Ctrl+F -> 立即重新聚焦到 search-query
            onNodeWithTag("search-size-max-mb").performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.F) }
            }
            waitForIdle()
            onNodeWithTag("search-query").assertIsFocused()
        }

    private fun sampleEntry(id: String, name: String, sizeBytes: Long): VFile = VFile(
        id = id,
        name = name,
        location = "C:\\work\\$name",
        parentLocation = "C:\\work",
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = 1_794_996_000_000L,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT, VFileCapability.READ_METADATA),
    )

    /** 生成用于图片查看器与预览面板真图渲染的渐变风景位图。 */
    private fun createDemoLandscapeBitmap(width: Int, height: Int): ImageBitmap {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, AwtColor(0x1E, 0x29, 0x52), 0f, height.toFloat(), AwtColor(0xF9, 0x73, 0x16))
            g.fillRect(0, 0, width, height)
            // 柔和太阳
            g.color = AwtColor(0xFE, 0xF0, 0x8A, 220)
            g.fillOval((width * 0.62).toInt(), (height * 0.18).toInt(), (height * 0.24).toInt(), (height * 0.24).toInt())
            // 前景剪影山峰
            g.color = AwtColor(0x0F, 0x17, 0x2A)
            val xs = intArrayOf(0, (width * 0.28).toInt(), (width * 0.52).toInt(), (width * 0.78).toInt(), width, width, 0)
            val ys = intArrayOf(height, (height * 0.48).toInt(), (height * 0.68).toInt(), (height * 0.42).toInt(), (height * 0.64).toInt(), height, height)
            g.fillPolygon(xs, ys, xs.size)
        } finally {
            g.dispose()
        }
        return image.toComposeImageBitmap()
    }

    private fun saveBatch4Screenshot(image: BufferedImage, fileName: String) {
        val outputDir = File("build/batch4-screenshots").apply { mkdirs() }
        val file = File(outputDir, fileName)
        ImageIO.write(image, "png", file)
        assertTrue(Files.size(file.toPath()) > 0L, "Batch 4 截图写入失败: ${file.absolutePath}")
    }
}
