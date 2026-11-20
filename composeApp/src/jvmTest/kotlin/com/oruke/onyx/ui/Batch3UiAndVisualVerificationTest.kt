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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneRemovalContext
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.PaneTabState
import com.oruke.onyx.app.component.PaneTabTransfer
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.app.component.TabComponent
import com.oruke.onyx.app.component.TabConfig
import com.oruke.onyx.app.component.TaskCenterComponent
import com.oruke.onyx.app.component.createDefaultPaneTabState
import com.oruke.onyx.app.component.defaultDetailsColumnWeights
import com.oruke.onyx.app.component.defaultDetailsColumns
import com.oruke.onyx.app.component.toPaneState
import com.oruke.onyx.app.component.withResizedDetailsColumnState
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.FileLabelMatcher
import com.oruke.onyx.core.model.FileLabelRule
import com.oruke.onyx.core.model.FileLabelStyle
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.StandardUserDirectory
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.resolveEffectiveDetailsColumnWidths
import com.oruke.onyx.vfs.api.FileContextMenuRequest
import com.oruke.onyx.vfs.api.FileContextMenuSection
import com.oruke.onyx.vfs.api.OpenWithApp
import com.oruke.onyx.vfs.api.SystemMenuAction
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_move_down
import onyx.composeapp.generated.resources.action_save_connection
import onyx.composeapp.generated.resources.label_remote_protocol_short_s3
import onyx.composeapp.generated.resources.label_shortcut_unsupported_key
import onyx.composeapp.generated.resources.label_user_command_validation_missing_executable
import onyx.composeapp.generated.resources.label_user_command_validation_missing_name
import org.jetbrains.compose.resources.getString
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch 3 UI 交互与 Skia 实景视觉验收测试。
 *
 * 覆盖：
 * - `DPI-01` / `REM-03`：150% 缩放下 S3 网络位置管理界面完整不裁切、徽标显示 `S3`
 * - `VIS-01` / `THEME-01` / `SET-03`：设置窗口深浅色双主题滚动条、高对比度渲染
 * - `VIS-02`：双面板 100% / 150% 下窄地址栏 `…` 折叠下拉跳转、自适应明细列宽完整展示 `修改时间` 且无 1 字崩溃
 * - `SET-02`：快捷键扩展白名单（`Ctrl+B`）、不支持按键/冲突迁移内联反馈及窗口级全局快捷键与文本输入隔离
 * - `SET-05` / `SET-06`：文件颜色标签字节即时同步与规则上下移、用户命令表单空名称/空可执行文件校验
 */
class Batch3UiAndVisualVerificationTest {
    /** 验证 150% DPI 深色主题下 S3 网络位置管理无按钮截断、徽标为 `S3` 并输出对比截图 `01`。 */
    @Test
    fun remoteConnectionsS3Dark150RendersCleanlyWithoutTruncation() =
        runDesktopComposeUiTest(width = 920, height = 620) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val settings = OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                uiScale = 150,
                preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
            )
            val s3Config = S3ConnectionConfig(
                provider = S3ProviderPreset.CUSTOM,
                endpoint = "https://demo.r2.cloudflarestorage.com",
                region = "auto",
                addressingStyle = S3AddressingStyle.PATH_STYLE,
            )
            val profiles = listOf(
                RemoteConnectionProfile(
                    id = "s3-1",
                    name = "Cloudflare R2 媒体库",
                    protocol = RemoteConnectionProtocol.S3,
                    location = "s3://media-assets/archive/",
                    s3Config = s3Config,
                ),
                RemoteConnectionProfile(
                    id = "dav-1",
                    name = "工作室 WebDAV",
                    protocol = RemoteConnectionProtocol.WEBDAVS,
                    location = "webdavs://dav.example.com/shared/",
                ),
            )
            val draft = RemoteConnectionDraft(
                name = "Cloudflare R2 媒体库",
                protocol = RemoteConnectionProtocol.S3,
                location = "s3://media-assets/archive/",
                username = "AKIAEXAMPLE123",
                s3Config = s3Config,
            )
            val actions = RemoteConnectionUiActions(
                onDraftChange = {},
                onNew = {},
                onEdit = {},
                onSave = {},
                onTest = {},
                onCancelTest = {},
                onDelete = {},
                onOpen = {},
                onOpenDraft = {},
            )

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(settings) {
                        val appearance = LocalOnyxAppearance.current
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(LocalOnyxPalette.current.appBackground)
                                .padding(12.dp),
                        ) {
                            RemoteConnectionsSettings(
                                connections = profiles,
                                connectionDraft = draft,
                                editingConnectionId = "s3-1",
                                testState = RemoteConnectionTestState.Idle,
                                error = null,
                                saving = false,
                                actions = actions,
                                fontSize = appearance.listFontSize,
                                labelFontSize = appearance.headerFontSize,
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val s3ShortLabel = getString(Res.string.label_remote_protocol_short_s3)
            val saveLabel = getString(Res.string.action_save_connection)
            assertTrue(onAllNodesWithText(s3ShortLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText(saveLabel, useUnmergedTree = true).assertIsDisplayed()

            saveBatch3Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "01-remote-connections-s3-dark-150-after.png",
            )
        }

    /** 验证深色与浅色设置窗口均展示可见滚动条与高对比度文本，并输出对比截图 `02` 与 `03`。 */
    @Test
    fun settingsDialogDarkAndLightRenderScrollbarsAndHighContrast() =
        runDesktopComposeUiTest(width = 900, height = 640) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var currentSettings by mutableStateOf(
                OnyxSettings(
                    themeMode = OnyxThemeMode.DARK,
                    uiScale = 100,
                    preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
                ),
            )
            var currentCategory by mutableStateOf(SettingsCategory.THEME_APPEARANCE)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(currentSettings) {
                        val appearance = LocalOnyxAppearance.current
                        SettingsDialogLayout(
                            title = "设置",
                            draft = currentSettings,
                            category = currentCategory,
                            onCategoryChange = { currentCategory = it },
                            onDraftChange = { currentSettings = it },
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

            onNodeWithTag("settings-nav-scrollbar", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("settings-content-scrollbar", useUnmergedTree = true).assertIsDisplayed()
            saveBatch3Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "02-settings-appearance-dark-100-after.png",
            )

            currentSettings = currentSettings.copy(themeMode = OnyxThemeMode.LIGHT)
            currentCategory = SettingsCategory.SHORTCUTS_ADVANCED
            waitForIdle()

            onNodeWithTag("settings-content-scrollbar", useUnmergedTree = true).assertIsDisplayed()
            saveBatch3Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "03-settings-light-100-after.png",
            )
        }

    /** 验证双面板 150% 深色与 100% 浅色下的侧栏滚动条、面包屑 `…` 折叠下拉与自适应明细列宽，并输出截图 `04` 与 `05`。 */
    @Test
    fun dualPaneDark150AndLight100RenderBreadcrumbOverflowAndAdaptiveColumns() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var currentSettings by mutableStateOf(
                OnyxSettings(
                    themeMode = OnyxThemeMode.DARK,
                    uiScale = 150,
                    preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
                ),
            )
            val deepPath = "C:\\Users\\oruke\\AppData\\Local\\Temp\\onyx-audit-sandbox-workspace\\docs\\2026-reports"
            val deepBreadcrumbs = listOf(
                VfsBreadcrumb("本地磁盘 (C:)", "C:\\"),
                VfsBreadcrumb("Users", "C:\\Users"),
                VfsBreadcrumb("oruke", "C:\\Users\\oruke"),
                VfsBreadcrumb("AppData", "C:\\Users\\oruke\\AppData"),
                VfsBreadcrumb("onyx-audit-sandbox-workspace", "C:\\Users\\oruke\\AppData\\Local\\Temp\\onyx-audit-sandbox-workspace"),
                VfsBreadcrumb("docs", "C:\\Users\\oruke\\AppData\\Local\\Temp\\onyx-audit-sandbox-workspace\\docs"),
                VfsBreadcrumb("2026-reports", deepPath),
            )
            var openedBreadcrumbTarget by mutableStateOf<String?>(null)
            val sampleEntries = listOf(
                sampleEntry("d1", "Architecture-Specs", VFileKind.DIRECTORY, null),
                sampleEntry("d2", "DesignAssets", VFileKind.DIRECTORY, null),
                sampleEntry("f1", "Q4-Quarterly-Release-Notes.md", VFileKind.FILE, 48_320L),
                sampleEntry("f2", "benchmark-results-2026.csv", VFileKind.FILE, 1_536_000L),
                sampleEntry("f3", "presentation-deck-final.pdf", VFileKind.FILE, 8_420_000L),
                sampleEntry("f4", "screenshot-verification.png", VFileKind.FILE, 912_400L),
            )

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(currentSettings) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(LocalOnyxPalette.current.appBackground),
                        ) {
                            PaneSidebar(
                                state = PaneSidebarState(
                                    location = deepPath,
                                    systemQuickAccessLocations = listOf(
                                        SystemQuickAccessLocation("桌面", "C:\\Users\\oruke\\Desktop", standardDirectory = StandardUserDirectory.DESKTOP),
                                        SystemQuickAccessLocation("文档", "C:\\Users\\oruke\\Documents", standardDirectory = StandardUserDirectory.DOCUMENTS),
                                        SystemQuickAccessLocation("下载", "C:\\Users\\oruke\\Downloads", standardDirectory = StandardUserDirectory.DOWNLOADS),
                                    ),
                                    favoriteLocations = listOf("C:\\Projects\\onyx", "D:\\Archive", "E:\\Media"),
                                    recentLocations = listOf(deepPath, "C:\\Projects\\onyx\\composeApp", "C:\\Temp"),
                                    remoteConnections = listOf(
                                        RemoteConnectionProfile("r1", "NAS WebDAV", RemoteConnectionProtocol.WEBDAV, "webdav://nas/files/"),
                                        RemoteConnectionProfile("r2", "R2 媒体库", RemoteConnectionProtocol.S3, "s3://media/"),
                                    ),
                                    treeState = SidebarTreeState(roots = emptyList()),
                                    showTree = false,
                                    collapsedSections = emptySet(),
                                ),
                                actions = PaneSidebarActions(
                                    locationLabel = { path -> path.substringAfterLast('\\').ifBlank { path } },
                                    onActivate = {},
                                    onOpenLocation = {},
                                    onToggleFavoriteLocation = {},
                                    onNewRemoteConnection = {},
                                    onEditRemoteConnection = {},
                                    onToggleTreeNode = {},
                                    onRetryTreeNode = {},
                                    onToggleSection = {},
                                ),
                            )
                            Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
                            DualPanePreviewColumn(
                                paneId = PaneId.PRIMARY,
                                location = deepPath,
                                breadcrumbs = deepBreadcrumbs,
                                entries = sampleEntries,
                                active = true,
                                onOpenBreadcrumb = { openedBreadcrumbTarget = it },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
                            DualPanePreviewColumn(
                                paneId = PaneId.SECONDARY,
                                location = deepPath,
                                breadcrumbs = deepBreadcrumbs,
                                entries = sampleEntries,
                                active = false,
                                onOpenBreadcrumb = { openedBreadcrumbTarget = it },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("sidebar-scrollbar", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(
                onAllNodesWithText("…", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "窄双面板深路径下必须显示面包屑 `…` 折叠按钮",
            )
            saveBatch3Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "04-main-dual-pane-dark-150-after.png",
            )

            currentSettings = currentSettings.copy(themeMode = OnyxThemeMode.LIGHT, uiScale = 100)
            waitForIdle()
            saveBatch3Screenshot(
                onRoot().captureToImage().toAwtImage(),
                "05-main-dual-pane-light-100-after.png",
            )

            onAllNodesWithText("…", useUnmergedTree = true)[0].performClick()
            waitForIdle()
            onNodeWithTag("address-bar-breadcrumb-overflow-menu", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Users", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals("C:\\Users", openedBreadcrumbTarget)
        }

    /** 验证快捷键扩展白名单（`Ctrl+B`）、不支持键提示与冲突迁移内联提示（SET-02）。 */
    @Test
    fun commandShortcutsSupportExpandedKeysAndShowInlineFeedback() =
        runDesktopComposeUiTest(width = 760, height = 560) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var draft by mutableStateOf(OnyxSettings(themeMode = OnyxThemeMode.DARK))
            var recording by mutableStateOf<OnyxCommand?>(null)
            var feedback by mutableStateOf<ShortcutRecordingFeedback?>(null)
            val focusRequester = FocusRequester()

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(draft) {
                        val appearance = LocalOnyxAppearance.current
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    handleShortcutRecordingKeyEvent(
                                        event = event,
                                        recordingCommand = recording,
                                        settings = draft,
                                        onDraftChange = { draft = it },
                                        onRecordingCommandChange = { recording = it },
                                        onFeedback = { feedback = it },
                                    )
                                }
                                .padding(12.dp),
                        ) {
                            CommandShortcutSettings(
                                settings = draft,
                                onDraftChange = { draft = it },
                                fontSize = appearance.listFontSize,
                                labelFontSize = appearance.headerFontSize,
                                recordingCommand = recording,
                                onRecordingCommandChange = { recording = it },
                                feedback = feedback,
                                onFeedbackChange = { feedback = it },
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 1. 录制扩展白名单内的 Ctrl+B 到 Refresh
            recording = OnyxCommand.Refresh
            waitForIdle()
            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.B) }
            }
            waitForIdle()
            val afterCtrlB = commandShortcutMapFromSettings(draft.commandShortcutOverrides)
            assertEquals(
                OnyxShortcut(OnyxShortcutKey.B, setOf(OnyxShortcutModifier.PRIMARY)),
                afterCtrlB.shortcutFor(OnyxCommand.Refresh),
            )
            assertEquals(null, recording)

            // 2. 录制冲突快捷键 Ctrl+K（原属 QuickOpen）到 Filter -> 自动迁移并展示内联反馈
            recording = OnyxCommand.Filter
            waitForIdle()
            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
            }
            waitForIdle()
            val afterConflict = commandShortcutMapFromSettings(draft.commandShortcutOverrides)
            assertEquals(
                OnyxShortcut(OnyxShortcutKey.K, setOf(OnyxShortcutModifier.PRIMARY)),
                afterConflict.shortcutFor(OnyxCommand.Filter),
            )
            assertEquals(null, afterConflict.shortcutFor(OnyxCommand.QuickOpen))
            assertTrue(feedback is ShortcutRecordingFeedback.Reassigned)
            onNodeWithTag("shortcut-recording-feedback", useUnmergedTree = true).assertIsDisplayed()

            // 3. 录制不在白名单中的按键（CapsLock）-> 提示不支持并停留在录制态
            recording = OnyxCommand.GoUp
            waitForIdle()
            onRoot().performKeyInput { pressKey(Key.CapsLock) }
            waitForIdle()
            assertEquals(ShortcutRecordingFeedback.UnsupportedKey, feedback)
            assertEquals(OnyxCommand.GoUp, recording)
            val unsupportedText = getString(Res.string.label_shortcut_unsupported_key)
            onNodeWithText(unsupportedText, useUnmergedTree = true).assertIsDisplayed()
        }

    /** 验证文件颜色标签字节输入即时同步、上下移排序与用户命令空名称/空可执行文件校验（SET-05 / SET-06）。 */
    @Test
    fun fileLabelInstantSizeAndReorderPlusUserCommandValidationWork() =
        runDesktopComposeUiTest(width = 1120, height = 760) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var draft by mutableStateOf(
                OnyxSettings(
                    fileLabelRules = listOf(
                        FileLabelRule(
                            id = "rule-a",
                            name = "Rule A",
                            matcher = FileLabelMatcher(minSizeBytes = 100L),
                        ),
                        FileLabelRule(
                            id = "rule-b",
                            name = "Rule B",
                            matcher = FileLabelMatcher(),
                        ),
                    ),
                    userCommands = listOf(
                        UserCommandDefinition(id = "cmd-invalid", name = "", executable = ""),
                    ),
                ),
            )

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(draft) {
                        Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                FileLabelSettings(draft = draft, onChange = { draft = it })
                            }
                            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)) {
                                UserCommandSettings(draft = draft, onChange = { draft = it })
                            }
                        }
                    }
                }
            }
            waitForIdle()

            val missingNameText = getString(Res.string.label_user_command_validation_missing_name)
            val missingExecText = getString(Res.string.label_user_command_validation_missing_executable)
            onNodeWithText(missingNameText, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText(missingExecText, useUnmergedTree = true).assertIsDisplayed()

            onNodeWithText("100", useUnmergedTree = true).performTextReplacement("2048")
            waitForIdle()
            assertEquals(2048L, draft.fileLabelRules.first().matcher.minSizeBytes)

            val moveDownLabel = getString(Res.string.action_move_down)
            onAllNodesWithText(moveDownLabel, useUnmergedTree = true)[0].performClick()
            waitForIdle()
            assertEquals(listOf("rule-b", "rule-a"), draft.fileLabelRules.map { it.id })
        }

    /** 验证窗口级全局快捷键在焦点非文本输入时响应，而在文本输入独占或浮层开启时严格隔离（SET-02）。 */
    @Test
    fun windowGlobalKeyHandlerRespectsFocusBlockingRegistryAndOverlays() =
        runDesktopComposeUiTest(width = 480, height = 240) {
            val registry = WindowKeyboardFocusRegistry()
            val dispatched = mutableListOf<RootIntent>()
            val fakeRoot = FakeMinimalRootComponent(onDispatch = { dispatched += it })
            val focusRequester = FocusRequester()

            setContent {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            handleWindowGlobalKeyEvent(
                                event = event,
                                state = fakeRoot.state.value,
                                rootComponent = fakeRoot,
                                focusRegistry = registry,
                            )
                        },
                )
            }
            waitForIdle()

            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
            onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.D) } }
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Comma) } }
            waitForIdle()
            assertEquals(
                listOf<RootIntent>(
                    RootIntent.ShowQuickOpen,
                    RootIntent.FocusActivePaneAddressBar,
                    RootIntent.OpenSettings,
                ),
                dispatched,
            )

            dispatched.clear()
            registry.setPaneBlocking(PaneId.PRIMARY, true)
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
            onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.D) } }
            waitForIdle()
            assertTrue(dispatched.isEmpty(), "文本输入独占键盘期间严禁劫持全局快捷键")

            registry.setPaneBlocking(PaneId.PRIMARY, false)
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
            waitForIdle()
            assertEquals(listOf<RootIntent>(RootIntent.FocusActivePaneAddressBar), dispatched)
        }

    /** 验证真实双栏 BoundPaneSurface 在按过一次 Ctrl+L 并退出编辑后，切换活动面板绝不会误重放地址栏聚焦（REV-02 / R2-REV-02）。 */
    @Test
    fun switchingActivePaneAfterAddressBarFocusRequestDoesNotReplayFocus() =
        runDesktopComposeUiTest(width = 1080, height = 520) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val fakeRoot = FakeMinimalRootComponent(layoutMode = PaneLayoutMode.DUAL_HORIZONTAL)
            var primaryBlocked = false
            var secondaryBlocked = false

            setContent {
                val rootState by fakeRoot.state.collectAsState()
                val tabDropZones = remember { mutableStateMapOf<PaneId, TabDropZone>() }
                val fileDropZones = remember { mutableStateMapOf<String, FileDropZone>() }
                val dragBindings = remember {
                    PaneDragBindings(
                        tabDropZones = tabDropZones,
                        tabDropTarget = null,
                        fileDropTarget = null,
                        fileDropZones = fileDropZones,
                        onTabDrop = { _, _, _ -> },
                        onTabDragPositionChange = {},
                        onTabDragEnd = {},
                        onFileDragStart = { _, _ -> },
                        onFileDragPositionChange = {},
                        onFileDragEnd = {},
                    )
                }
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(rootState.settings) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            BoundPaneSurface(
                                paneId = PaneId.PRIMARY,
                                state = rootState,
                                rootComponent = fakeRoot,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                dragBindings = dragBindings,
                                onGlobalShortcutBlockChange = { primaryBlocked = it },
                            )
                            BoundPaneSurface(
                                paneId = PaneId.SECONDARY,
                                state = rootState,
                                rootComponent = fakeRoot,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                dragBindings = dragBindings,
                                onGlobalShortcutBlockChange = { secondaryBlocked = it },
                            )
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size)
            assertTrue(!primaryBlocked && !secondaryBlocked)

            // 1. 在主面板派发 FocusActivePaneAddressBar -> 仅主面板地址栏进入编辑态
            fakeRoot.dispatch(RootIntent.FocusActivePaneAddressBar)
            waitForIdle()
            assertEquals(1, onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size)
            assertTrue(primaryBlocked, "主面板地址栏进入编辑态后应上报文本独占")
            assertTrue(!secondaryBlocked, "次面板地址栏不得进入编辑态")

            // 2. 按 Esc 退出地址栏编辑
            onNodeWithTag("address-bar-input").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size)
            assertTrue(!primaryBlocked && !secondaryBlocked)

            // 3. 切换活动面板到次面板再切回主面板 -> 两侧都不得误重放地址栏编辑态
            fakeRoot.dispatch(RootIntent.ActivatePane(PaneId.SECONDARY))
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size,
                "切换至次面板时不得重放主面板历史地址栏聚焦请求",
            )
            assertTrue(!primaryBlocked && !secondaryBlocked)

            fakeRoot.dispatch(RootIntent.ActivatePane(PaneId.PRIMARY))
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size,
                "切回主面板时不得重放已消费的地址栏聚焦请求",
            )
            assertTrue(!primaryBlocked && !secondaryBlocked)

            // 4. 在次面板触发聚焦后再退出并切回主面板 -> 依然不重放
            fakeRoot.dispatch(RootIntent.ActivatePane(PaneId.SECONDARY))
            waitForIdle()
            fakeRoot.dispatch(RootIntent.FocusActivePaneAddressBar)
            waitForIdle()
            assertEquals(1, onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size)
            assertTrue(!primaryBlocked && secondaryBlocked)

            onNodeWithTag("address-bar-input").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            fakeRoot.dispatch(RootIntent.ActivatePane(PaneId.PRIMARY))
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("address-bar-input").fetchSemanticsNodes().size)
            assertTrue(!primaryBlocked && !secondaryBlocked)
        }

    /** 验证宽视口首次组合后动态缩窄容器再拖拽表头分隔线，仍命中最新自适应列宽而不暴跳 ~100dp（REV-06 / R2-REV-01）。 */
    @Test
    fun shrinkingDetailsViewportThenDraggingResizeGapUsesLatestEffectiveWidths() =
        runDesktopComposeUiTest(width = 960, height = 360) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            var viewportWidthDp by mutableStateOf(900)
            val entries = listOf(
                sampleEntry("f1", "architecture-spec.md", VFileKind.FILE, 48_320L),
            )
            val plainResizeCalls = mutableListOf<Pair<DetailsColumn, Float>>()
            val effectiveResizeCalls = mutableListOf<Triple<DetailsColumn, Float, Map<DetailsColumn, Float>>>()

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Box(modifier = Modifier.width(viewportWidthDp.dp).fillMaxHeight()) {
                            PaneEntriesContent(
                                model = PaneEntriesViewModel(
                                    viewMode = ViewMode.DETAILS,
                                    columns = defaultDetailsColumns(),
                                    columnWeights = defaultDetailsColumnWeights(),
                                    hiddenColumns = emptySet(),
                                    sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING),
                                    selectedEntryIds = emptySet(),
                                    entriesState = PaneEntriesState.Ready(entries),
                                    paneActive = true,
                                    contextMenuVisible = false,
                                    paneId = PaneId.PRIMARY,
                                    fileDropTarget = null,
                                    inlineEditState = null,
                                    tabId = "tab-resize-regression",
                                ),
                                actions = PaneEntriesActions(
                                    onActivate = {},
                                    onOpenEntry = {},
                                    onToggleSort = {},
                                    onResizeColumn = { col, delta -> plainResizeCalls += col to delta },
                                    onResizeColumnFromEffective = { col, delta, baseWidths ->
                                        effectiveResizeCalls += Triple(col, delta, baseWidths)
                                    },
                                    onToggleColumnVisibility = {},
                                    onSelectEntry = { _, _, _ -> },
                                    onStartFileDrag = { _, _ -> },
                                    onFileDragPositionChange = {},
                                    onFileDragEnd = {},
                                    onFileDropZoneChange = {},
                                    onShowContextMenu = { _, _, _ -> },
                                    onDismissContextMenu = {},
                                    onUpdateInlineEditDraft = {},
                                    onConfirmInlineEdit = {},
                                    onCancelInlineEdit = {},
                                ),
                                thumbnails = GalleryThumbnailServices(
                                    loadThumbnail = { _, _ -> null },
                                    loadArchiveThumbnail = { _, _ -> null },
                                    isArchiveFileName = { false },
                                    isImageFileName = { false },
                                ),
                            )
                        }
                    }
                }
            }
            // 先在 900dp 宽视口（effectiveColumnWeights === model.columnWeights）完成首次组合
            waitForIdle()

            // 动态缩窄至 540dp 进入自适应收缩模式，再对名称列分隔线执行真实拖拽手势
            viewportWidthDp = 540
            waitForIdle()

            onNodeWithTag("details-resize-gap-NAME", useUnmergedTree = true).performMouseInput {
                moveTo(center)
                press()
                advanceEventTime(16L)
                moveBy(Offset(20f, 0f))
                advanceEventTime(16L)
                moveBy(Offset(8f, 0f))
                advanceEventTime(16L)
                release()
            }
            waitForIdle()

            assertTrue(
                plainResizeCalls.isEmpty(),
                "缩窄视口后首次拖拽列宽必须通过 rememberUpdatedState 命中 onResizeColumnFromEffective，不得落回旧宽视口闭包",
            )
            assertTrue(effectiveResizeCalls.isNotEmpty(), "真实拖拽名称列分隔线必须触发自适应基准列宽调整回调")
            val (column, deltaDp, capturedBaseWidths) = effectiveResizeCalls.first()
            assertEquals(DetailsColumn.NAME, column)
            val expectedAdaptive = resolveEffectiveDetailsColumnWidths(
                columns = defaultDetailsColumns(),
                columnWeights = defaultDetailsColumnWeights(),
                hiddenColumns = emptySet(),
                viewportWidthDp = 540f,
            )
            val capturedNameWidth = capturedBaseWidths.getValue(DetailsColumn.NAME)
            assertEquals(expectedAdaptive.getValue(DetailsColumn.NAME), capturedNameWidth, 0.5f)
            assertTrue(capturedNameWidth < 260f, "窄视口基准名称列宽应处于收缩态（< 260dp），实际为 $capturedNameWidth")

            // 确认以捕获到的最新自适应列宽叠加增量后无 ~100dp 突跳
            val tab = createDefaultPaneTabState("t-resize", "work", "C:\\work", ViewMode.DETAILS)
            val updated = tab.withResizedDetailsColumnState(column, deltaDp, capturedBaseWidths)
            assertEquals(capturedNameWidth + deltaDp, updated.detailsColumnWeights.getValue(DetailsColumn.NAME), 0.5f)
        }

    @Composable
    private fun DualPanePreviewColumn(
        paneId: PaneId,
        location: String,
        breadcrumbs: List<VfsBreadcrumb>,
        entries: List<VFile>,
        active: Boolean,
        onOpenBreadcrumb: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val palette = LocalOnyxPalette.current
        Column(modifier = modifier.background(palette.surface)) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
                BreadcrumbAddressBar(
                    location = location,
                    onActivate = {},
                    onEdit = {},
                    onOpenLocation = onOpenBreadcrumb,
                    buildBreadcrumbs = { breadcrumbs },
                )
            }
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PaneEntriesContent(
                    model = PaneEntriesViewModel(
                        viewMode = ViewMode.DETAILS,
                        columns = defaultDetailsColumns(),
                        columnWeights = defaultDetailsColumnWeights(),
                        hiddenColumns = emptySet(),
                        sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING),
                        selectedEntryIds = if (active) setOf("f1") else emptySet(),
                        entriesState = PaneEntriesState.Ready(entries),
                        paneActive = active,
                        contextMenuVisible = false,
                        paneId = paneId,
                        fileDropTarget = null,
                        inlineEditState = null,
                        tabId = "tab-$paneId",
                    ),
                    actions = PaneEntriesActions(
                        onActivate = {},
                        onOpenEntry = {},
                        onToggleSort = {},
                        onResizeColumn = { _, _ -> },
                        onToggleColumnVisibility = {},
                        onSelectEntry = { _, _, _ -> },
                        onStartFileDrag = { _, _ -> },
                        onFileDragPositionChange = {},
                        onFileDragEnd = {},
                        onFileDropZoneChange = {},
                        onShowContextMenu = { _, _, _ -> },
                        onDismissContextMenu = {},
                        onUpdateInlineEditDraft = {},
                        onConfirmInlineEdit = {},
                        onCancelInlineEdit = {},
                    ),
                    thumbnails = GalleryThumbnailServices(
                        loadThumbnail = { _, _ -> null },
                        loadArchiveThumbnail = { _, _ -> null },
                        isArchiveFileName = { false },
                        isImageFileName = { false },
                    ),
                )
            }
        }
    }

    private fun sampleEntry(id: String, name: String, kind: VFileKind, size: Long?): VFile {
        return VFile(
            id = id,
            name = name,
            location = "C:\\work\\$name",
            parentLocation = "C:\\work",
            kind = kind,
            sizeBytes = size,
            modifiedAtEpochMillis = 1_794_996_000_000L,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_METADATA),
        )
    }

    private fun saveBatch3Screenshot(image: java.awt.image.BufferedImage, fileName: String) {
        val outputDir = File("build/batch3-screenshots").apply { mkdirs() }
        val file = File(outputDir, fileName)
        ImageIO.write(image, "png", file)
        assertTrue(Files.size(file.toPath()) > 0L, "截图写入失败: ${file.absolutePath}")
    }
}

/**
 * 仅用于窗口级快捷键与双栏 BoundPaneSurface 单测的极简 [RootComponent] 代理实现。
 */
private class FakeMinimalRootComponent(
    private val onDispatch: (RootIntent) -> Unit = {},
    layoutMode: PaneLayoutMode = PaneLayoutMode.SINGLE,
) : RootComponent {
    private val primaryTab = createDefaultPaneTabState("t1", "work", "C:\\work", ViewMode.DETAILS)
    private val secondaryTab = createDefaultPaneTabState("t2", "docs", "C:\\docs", ViewMode.DETAILS)
    private val primaryState = primaryTab.toPaneState(PaneId.PRIMARY, "t1")
    private val secondaryState = secondaryTab.toPaneState(PaneId.SECONDARY, "t2")

    override val state = MutableStateFlow(
        RootState(
            layoutMode = layoutMode,
            paneSplitFraction = 0.5f,
            activePane = PaneId.PRIMARY,
            primaryPane = primaryState,
            secondaryPane = secondaryState,
            sidebarTreeState = SidebarTreeState(roots = emptyList()),
            systemQuickAccessLocations = emptyList(),
            settings = OnyxSettings(),
            sessionRestoreState = SessionRestoreState.Ready,
            dialogState = null,
            canPaste = false,
            tasks = emptyList(),
        ),
    )
    override val imageViewerState = MutableStateFlow(ImageViewerState())
    override val resourceLocale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
    override val primaryPane: PaneComponent = FakeMinimalPaneComponent(primaryState, primaryTab)
    override val secondaryPane: PaneComponent = FakeMinimalPaneComponent(secondaryState, secondaryTab)
    override val taskCenter: TaskCenterComponent
        get() = throw UnsupportedOperationException()

    override fun dispatch(intent: RootIntent) {
        onDispatch(intent)
        when (intent) {
            is RootIntent.ActivatePane -> {
                state.value = state.value.copy(activePane = intent.paneId)
            }

            RootIntent.FocusActivePaneAddressBar -> {
                val target = state.value.activePane
                val current = state.value.addressBarFocusRequests
                state.value = state.value.copy(
                    addressBarFocusRequests = current + (target to ((current[target] ?: 0) + 1)),
                )
            }

            else -> Unit
        }
    }
    override fun locationLabel(location: String): String = location
    override fun buildBreadcrumbs(location: String): List<VfsBreadcrumb> =
        listOf(VfsBreadcrumb(label = location, location = location))
    override fun isArchiveFileName(fileName: String): Boolean = false
    override fun isImageFileName(fileName: String): Boolean = false
    override fun isTextPreviewFileName(fileName: String): Boolean = false
    override fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation =
        FileTransferOperation.COPY

    override suspend fun loadThumbnail(location: String, maxDimension: Int): ImageBitmap? = null
    override suspend fun loadArchiveThumbnail(location: String, maxDimension: Int): ImageBitmap? = null
    override suspend fun readImageSize(entry: VFile): IntSize? = null
    override suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult =
        throw UnsupportedOperationException()

    override suspend fun readFileHash(request: FileHashRequest): FileHashResult =
        throw UnsupportedOperationException()

    override suspend fun readArchiveInfo(request: ArchiveInfoRequest): ArchiveInfoResult =
        throw UnsupportedOperationException()

    override suspend fun listOpenWithApps(entry: VFile): List<OpenWithApp> = emptyList()
    override fun supportsOpenWith(entry: VFile): Boolean = false
    override suspend fun listSystemMenuActions(entries: List<VFile>): List<SystemMenuAction> = emptyList()
    override fun supportsContextMenuOpenWith(entry: VFile): Boolean = false
    override suspend fun listContextMenuSections(request: FileContextMenuRequest): List<FileContextMenuSection> =
        emptyList()

    override fun prepareExternalDrag(entries: List<VFile>): Boolean = false
    override suspend fun testRemoteConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult =
        throw UnsupportedOperationException()

    override fun matchFileLabels(entries: List<VFile>): Map<String, FileLabelStyle> = emptyMap()
    override fun userCommandsForPlacement(placement: UserCommandPlacement): List<UserCommandDefinition> = emptyList()
}

/**
 * 仅用于 [BoundPaneSurface] 真实双栏 UI 测试的极简 [PaneComponent] 桩实现。
 */
private class FakeMinimalPaneComponent(
    initialState: PaneState,
    initialTab: PaneTabState,
) : PaneComponent {
    override val state: StateFlow<PaneState> = MutableStateFlow(initialState)
    override val tabOrder: StateFlow<List<String>> = MutableStateFlow(listOf(initialTab.id))
    override val tabStates: StateFlow<List<PaneTabState>> = MutableStateFlow(listOf(initialTab))
    override val tabStack: Value<ChildStack<TabConfig, TabComponent>>
        get() = throw UnsupportedOperationException()

    override fun dispatch(intent: PaneIntent) = Unit
    override fun detachTab(tabId: String): PaneTabTransfer? = null
    override fun attachTab(tabSnapshot: PaneTabTransfer, targetIndex: Int) = Unit
    override fun restoreSession(snapshot: PaneSessionSnapshot) = Unit
    override fun captureRemoval(entries: List<VFile>): PaneRemovalContext? = null
    override fun completeRemoval(context: PaneRemovalContext, removedIds: Set<String>) = Unit
}
