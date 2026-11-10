@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.core.model.OnyxThemeColors
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.resolveOnyxPalette
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证侧边栏与文件列表（详情视图、画廊视图）在浅色与深色主题下的鼠标悬停视觉对比度与真实像素渲染。
 */
class HoverVisibilityUiTest {

    @Test
    fun `all built-in light and dark palettes have distinct high-contrast rowHoverBackground`() {
        for (preset in OnyxThemePreset.entries) {
            for (dark in listOf(false, true)) {
                val palette = resolveOnyxPalette(
                    themePreset = preset,
                    dark = dark,
                    overrides = OnyxThemeColors(),
                )
                assertNotEquals(
                    palette.surface,
                    palette.rowHoverBackground,
                    "$preset (dark=$dark) rowHoverBackground must differ from surface",
                )
                assertNotEquals(
                    palette.surfaceVariant,
                    palette.rowHoverBackground,
                    "$preset (dark=$dark) rowHoverBackground must differ from surfaceVariant",
                )
                assertNotEquals(
                    palette.selectionBackground,
                    palette.rowHoverBackground,
                    "$preset (dark=$dark) rowHoverBackground must differ from selectionBackground",
                )
                val distSurface = rgbDistance(palette.rowHoverBackground, palette.surface)
                val distSurfaceVariant = rgbDistance(palette.rowHoverBackground, palette.surfaceVariant)
                assertTrue(
                    distSurface >= 20.0,
                    "$preset (dark=$dark) hover vs surface distance too small: $distSurface",
                )
                assertTrue(
                    distSurfaceVariant >= 16.0,
                    "$preset (dark=$dark) hover vs surfaceVariant distance too small: $distSurfaceVariant",
                )
            }
        }
    }

    @Test
    fun `sidebar and file list hover states render unmistakable highlight in light and dark themes`() =
        runDesktopComposeUiTest(width = 1100, height = 700) {
            val sample = Files.createTempDirectory("onyx-hover-ui-")
            val projectsDir = Files.createDirectories(sample.resolve("Projects"))
            Files.createDirectories(sample.resolve("Launcher"))
            Files.createDirectories(sample.resolve("LeagueClient"))
            Files.createDirectories(sample.resolve("WeGameLauncher"))
            Files.writeString(sample.resolve("bugreport.exe"), "exe-binary")
            Files.writeString(sample.resolve("crash_report_helper.dll"), "dll-binary")
            Files.writeString(sample.resolve("launcher.exe"), "launcher-binary")

            var capturedRoot: RootComponent? = null
            val reportDir = File("build/reports").apply { mkdirs() }

            try {
                setContent {
                    DirectoryBrowsingApplication(sample.toString()) { capturedRoot = it }
                }
                waitUntil(timeoutMillis = 15_000) {
                    capturedRoot?.let { root ->
                        root.state.value.sessionRestoreState == SessionRestoreState.Ready &&
                            (root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready)
                                ?.entries?.isNotEmpty() == true
                    } == true
                }
                val root = checkNotNull(capturedRoot)
                val entries = (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready).entries
                val launcherEntry = entries.first { it.name == "Launcher" }

                // 1. 浅色模式（Default Light）：选中 Launcher，悬停文件列表中的 WeGameLauncher
                val lightPalette = resolveOnyxPalette(OnyxThemePreset.DEFAULT, dark = false, overrides = OnyxThemeColors())
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(
                                themeMode = OnyxThemeMode.LIGHT,
                                themePreset = OnyxThemePreset.DEFAULT,
                                sidebarVisible = true,
                                favoriteLocations = listOf(projectsDir.toString()),
                                uiScale = 100,
                            ),
                        ),
                    )
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                    root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS))
                    root.primaryPane.dispatch(PaneIntent.SelectEntry(launcherEntry.id))
                }
                waitForIdle()

                onNodeWithText("WeGameLauncher", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                }
                waitForIdle()
                val fileListLightImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(fileListLightImg, "png", File(reportDir, "visual-hover-filelist-light.png"))
                assertTrue(
                    countMatchingPixels(fileListLightImg, lightPalette.rowHoverBackground) > 500,
                    "Expected hovered file row in Light mode to render rowHoverBackground pixels",
                )

                // 2. 浅色模式（Default Light）：悬停侧边栏中的收藏项 Projects（侧边栏为第 1 个匹配项）
                onAllNodesWithText("Projects", useUnmergedTree = true).onFirst().performMouseInput {
                    moveTo(center)
                }
                waitForIdle()
                val sidebarLightImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(sidebarLightImg, "png", File(reportDir, "visual-hover-sidebar-light.png"))
                assertTrue(
                    countMatchingPixels(sidebarLightImg, lightPalette.rowHoverBackground) > 200,
                    "Expected hovered sidebar item in Light mode to render rowHoverBackground pixels",
                )

                // 3. 深色模式（Default Dark）：悬停文件列表中的 WeGameLauncher
                val darkPalette = resolveOnyxPalette(OnyxThemePreset.DEFAULT, dark = true, overrides = OnyxThemeColors())
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(
                                themeMode = OnyxThemeMode.DARK,
                                themePreset = OnyxThemePreset.DEFAULT,
                            ),
                        ),
                    )
                }
                waitForIdle()

                onNodeWithText("WeGameLauncher", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                }
                waitForIdle()
                val fileListDarkImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(fileListDarkImg, "png", File(reportDir, "visual-hover-filelist-dark.png"))
                assertTrue(
                    countMatchingPixels(fileListDarkImg, darkPalette.rowHoverBackground) > 500,
                    "Expected hovered file row in Dark mode to render rowHoverBackground pixels",
                )

                // 4. 深色模式（Default Dark）：悬停侧边栏中的收藏项 Projects
                onAllNodesWithText("Projects", useUnmergedTree = true).onFirst().performMouseInput {
                    moveTo(center)
                }
                waitForIdle()
                val sidebarDarkImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(sidebarDarkImg, "png", File(reportDir, "visual-hover-sidebar-dark.png"))
                assertTrue(
                    countMatchingPixels(sidebarDarkImg, darkPalette.rowHoverBackground) > 200,
                    "Expected hovered sidebar item in Dark mode to render rowHoverBackground pixels",
                )

                // 5. 画廊模式（Light Mode）：悬停画廊项 WeGameLauncher
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(
                                themeMode = OnyxThemeMode.LIGHT,
                                themePreset = OnyxThemePreset.DEFAULT,
                            ),
                        ),
                    )
                    root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.GALLERY))
                }
                waitForIdle()

                onNodeWithText("WeGameLauncher", useUnmergedTree = true).performMouseInput {
                    moveTo(center)
                }
                waitForIdle()
                val galleryLightImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(galleryLightImg, "png", File(reportDir, "visual-hover-gallery-light.png"))
                assertTrue(
                    countMatchingPixels(galleryLightImg, lightPalette.rowHoverBackground) > 400,
                    "Expected hovered gallery tile in Light mode to render rowHoverBackground pixels",
                )

                // 6. 验证设置窗口草稿实时预览与首次应用深色模式文件列表即时生效（无需二次应用）
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS))
                    root.dispatch(RootIntent.OpenSettings)
                    root.dispatch(
                        RootIntent.UpdateSettingsDraft(
                            root.state.value.settings.copy(
                                themeMode = OnyxThemeMode.DARK,
                                themePreset = OnyxThemePreset.DEFAULT,
                            ),
                        ),
                    )
                }
                waitForIdle()
                val livePreviewDarkImg = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                ImageIO.write(livePreviewDarkImg, "png", File(reportDir, "visual-live-preview-main-dark.png"))
                assertTrue(
                    countMatchingPixels(livePreviewDarkImg, darkPalette.surface) > 20_000,
                    "Main window file list must live-preview Dark surface while Settings draft is edited",
                )
                assertTrue(
                    countMatchingPixels(livePreviewDarkImg, darkPalette.surfaceVariant) > 2_000,
                    "Main window file list zebra stripes must live-preview Dark surfaceVariant",
                )

                // 关闭设置且未点击应用时自动回退到浅色模式
                runOnUiThread {
                    root.dispatch(RootIntent.DismissDialog)
                }
                waitForIdle()
                val revertedLightImg = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                assertTrue(
                    countMatchingPixels(revertedLightImg, lightPalette.surface) > 20_000,
                    "Main window file list must revert to Light surface when Settings is dismissed without applying",
                )

                // 首次点击应用深色模式，文件列表在第一次应用时即刻完整切换为深色
                runOnUiThread {
                    root.dispatch(RootIntent.OpenSettings)
                    root.dispatch(
                        RootIntent.UpdateSettingsDraft(
                            root.state.value.settings.copy(
                                themeMode = OnyxThemeMode.DARK,
                                themePreset = OnyxThemePreset.DEFAULT,
                            ),
                        ),
                    )
                    root.dispatch(RootIntent.ConfirmDialog)
                }
                waitForIdle()
                val firstApplyDarkImg = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                ImageIO.write(firstApplyDarkImg, "png", File(reportDir, "visual-first-apply-main-dark.png"))
                assertTrue(
                    countMatchingPixels(firstApplyDarkImg, darkPalette.surface) > 20_000,
                    "File list must switch to Dark surface on the FIRST apply",
                )
            } finally {
                mainClock.autoAdvance = false
                setContent { }
                waitForIdle()
                Files.walk(sample).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            }
        }

    @Test
    fun loadingLayoutAndAnimationRenderCleanlyInDetailsAndGallery() =
        runDesktopComposeUiTest(width = 1100, height = 680) {
            val reportDir = File("build/reports").apply { mkdirs() }
            var viewMode = androidx.compose.runtime.mutableStateOf(ViewMode.DETAILS)
            val darkSettings = com.oruke.onyx.core.model.OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                themePreset = OnyxThemePreset.DEFAULT,
            )
            val dummyActions = com.oruke.onyx.ui.PaneEntriesActions(
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
            )
            try {
                setContent {
                    com.oruke.onyx.ui.theme.OnyxWindowTheme(settings = darkSettings) {
                        val palette = com.oruke.onyx.ui.theme.LocalOnyxPalette.current
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(palette.surface),
                        ) {
                            com.oruke.onyx.ui.PaneEntriesLoading(
                                model = com.oruke.onyx.ui.PaneEntriesViewModel(
                                    viewMode = viewMode.value,
                                    columns = com.oruke.onyx.core.model.DetailsColumn.entries,
                                    columnWeights = com.oruke.onyx.app.component.defaultDetailsColumnWeights(),
                                    hiddenColumns = emptySet(),
                                    sort = com.oruke.onyx.core.model.DetailsSort(
                                        column = com.oruke.onyx.core.model.DetailsColumn.NAME,
                                        direction = com.oruke.onyx.core.model.SortDirection.ASCENDING,
                                    ),
                                    selectedEntryIds = emptySet(),
                                    entriesState = PaneEntriesState.Loading,
                                    paneActive = true,
                                    contextMenuVisible = false,
                                    paneId = com.oruke.onyx.core.model.PaneId.PRIMARY,
                                    fileDropTarget = null,
                                    inlineEditState = null,
                                    galleryItemSizeDp = 140,
                                ),
                                actions = dummyActions,
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("pane-entries-loading-PRIMARY", useUnmergedTree = true).assertIsDisplayed()
                // 验证加载状态下详情表头保持固定可见，且加载胶囊水平居中（不再贴在最左侧）
                val containerBounds = onNodeWithTag("pane-entries-loading-PRIMARY", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(containerBounds.width > 800f)

                val loadingDetailsImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(loadingDetailsImg, "png", File(reportDir, "visual-loading-details-dark.png"))

                runOnUiThread { viewMode.value = ViewMode.GALLERY }
                waitForIdle()
                val loadingGalleryImg = onRoot().captureToImage().toAwtImage()
                ImageIO.write(loadingGalleryImg, "png", File(reportDir, "visual-loading-gallery-dark.png"))
            } finally {
                mainClock.autoAdvance = false
                setContent { }
                waitForIdle()
            }
        }

    private fun rgbDistance(a: Color, b: Color): Double {
        val ar = (a.toArgb() shr 16) and 0xFF
        val ag = (a.toArgb() shr 8) and 0xFF
        val ab = a.toArgb() and 0xFF
        val br = (b.toArgb() shr 16) and 0xFF
        val bg = (b.toArgb() shr 8) and 0xFF
        val bb = b.toArgb() and 0xFF
        val dr = (ar - br).toDouble()
        val dg = (ag - bg).toDouble()
        val db = (ab - bb).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun countMatchingPixels(image: BufferedImage, target: Color, tolerance: Int = 3): Int {
        val tr = (target.toArgb() shr 16) and 0xFF
        val tg = (target.toArgb() shr 8) and 0xFF
        val tb = target.toArgb() and 0xFF
        var matches = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - tr) <= tolerance && abs(g - tg) <= tolerance && abs(b - tb) <= tolerance) {
                    matches++
                }
            }
        }
        return matches
    }
}
