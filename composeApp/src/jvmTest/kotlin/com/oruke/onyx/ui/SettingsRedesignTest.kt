package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.delegate.EntrySorter
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.CustomThemePreset
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.FileListColors
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeColors
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.ui.theme.resolveEffectiveDarkTheme
import com.oruke.onyx.ui.theme.resolveOnyxPalette
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRedesignTest {

    @Test
    fun `theme engine resolves light dark modes presets and color overrides accurately`() {
        assertFalse(resolveEffectiveDarkTheme(OnyxThemeMode.SYSTEM, systemDark = false))
        assertTrue(resolveEffectiveDarkTheme(OnyxThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveEffectiveDarkTheme(OnyxThemeMode.LIGHT, systemDark = true))
        assertTrue(resolveEffectiveDarkTheme(OnyxThemeMode.DARK, systemDark = false))

        val darkPalettes = OnyxThemePreset.entries.associateWith { preset ->
            resolveOnyxPalette(themePreset = preset, dark = true, overrides = OnyxThemeColors())
        }
        assertEquals(Color(0xFF090A0F), darkPalettes.getValue(OnyxThemePreset.MIDNIGHT_OLED).appBackground)
        assertNotEquals(
            darkPalettes.getValue(OnyxThemePreset.DEFAULT).accent,
            darkPalettes.getValue(OnyxThemePreset.DRACULA_PURPLE).accent,
        )
        assertNotEquals(
            darkPalettes.getValue(OnyxThemePreset.NORDIC_FROST).appBackground,
            darkPalettes.getValue(OnyxThemePreset.FOREST_EMERALD).appBackground,
        )

        val customAccent = 0xFFE11D48L
        val customAppBg = 0xFF101216L
        val customHeaderBg = 0xFF1A1E24L
        val overridden = resolveOnyxPalette(
            themePreset = OnyxThemePreset.DEFAULT,
            dark = true,
            overrides = OnyxThemeColors(
                appBackgroundArgb = customAppBg,
                headerBackgroundArgb = customHeaderBg,
                accentArgb = customAccent,
            ),
        )
        assertEquals(Color(customAppBg), overridden.appBackground)
        assertEquals(Color(customHeaderBg), overridden.headerBackground)
        assertEquals(Color(customAccent), overridden.accent)
    }

    @Test
    fun `custom themes and resetCategoryToDefaults work and preserve saved custom themes`() {
        val customPreset = CustomThemePreset(
            id = "custom-cyber",
            name = "赛博霓虹",
            themeMode = OnyxThemeMode.DARK,
            basePreset = OnyxThemePreset.MIDNIGHT_OLED,
            themeColors = OnyxThemeColors(accentArgb = 0xFFEC4899L),
            fileListColors = FileListColors(selectionArgb = 0x66EC4899L),
        )
        val customized = OnyxSettings(
            preferredLocale = AppLocale.JAPANESE,
            closeToTray = false,
            restoreSessionOnStartup = false,
            themeMode = OnyxThemeMode.DARK,
            themePreset = OnyxThemePreset.DRACULA_PURPLE,
            themeColors = OnyxThemeColors(accentArgb = 0xFF9333EAL),
            customThemes = listOf(customPreset),
            activeCustomThemeId = customPreset.id,
            uiScale = 125,
            defaultShowHiddenItems = true,
            showFileExtensions = false,
            naturalSortEnabled = false,
            directoriesFirst = false,
            singleClickToOpen = true,
            doubleClickEmptySpaceToNavigateUp = false,
            deleteMode = DeleteMode.PERMANENT,
            confirmBeforeDelete = false,
            autoShowTaskCenter = false,
            autoCloseTaskCenterWhenFinished = false,
            favoriteLocations = listOf("C:\\Projects"),
            recentLocations = listOf("D:\\Media"),
        )

        val afterThemeReset = customized.resetCategoryToDefaults(SettingsCategory.THEME_APPEARANCE)
        assertEquals(OnyxThemeMode.SYSTEM, afterThemeReset.themeMode)
        assertEquals(OnyxThemePreset.DEFAULT, afterThemeReset.themePreset)
        assertEquals(OnyxThemeColors(), afterThemeReset.themeColors)
        assertNull(afterThemeReset.activeCustomThemeId)
        assertEquals(listOf(customPreset), afterThemeReset.customThemes)
        assertEquals(100, afterThemeReset.uiScale)
        assertEquals(AppLocale.JAPANESE, afterThemeReset.preferredLocale)
        assertFalse(afterThemeReset.closeToTray)
        assertTrue(afterThemeReset.defaultShowHiddenItems)
        assertTrue(afterThemeReset.singleClickToOpen)
        assertFalse(afterThemeReset.autoShowTaskCenter)
        assertFalse(afterThemeReset.autoCloseTaskCenterWhenFinished)

        val afterFullReset = customized.resetAllPreferencesToDefaults()
        assertEquals(AppLocale.SYSTEM, afterFullReset.preferredLocale)
        assertTrue(afterFullReset.closeToTray)
        assertFalse(afterFullReset.defaultShowHiddenItems)
        assertTrue(afterFullReset.showFileExtensions)
        assertTrue(afterFullReset.naturalSortEnabled)
        assertTrue(afterFullReset.directoriesFirst)
        assertFalse(afterFullReset.singleClickToOpen)
        assertTrue(afterFullReset.confirmBeforeDelete)
        assertTrue(afterFullReset.autoShowTaskCenter)
        assertTrue(afterFullReset.autoCloseTaskCenterWhenFinished)
        assertEquals(listOf(customPreset), afterFullReset.customThemes)
        assertEquals(listOf("C:\\Projects"), afterFullReset.favoriteLocations)
        assertEquals(listOf("D:\\Media"), afterFullReset.recentLocations)
    }

    @Test
    fun `EntrySorter natural sort and directoriesFirst order files as expected`() {
        fun file(name: String, isDir: Boolean = false) = VFile(
            id = name,
            name = name,
            location = "C:\\test\\$name",
            parentLocation = "C:\\test",
            kind = if (isDir) VFileKind.DIRECTORY else VFileKind.FILE,
            sizeBytes = 100L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )

        val items = listOf(
            file("chapter10.txt"),
            file("chapter2.txt"),
            file("chapter1.txt"),
            file("assets", isDir = true),
            file("zebra", isDir = true),
        )
        val sortByNameAsc = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING)

        val naturalDirsFirst = EntrySorter.sortEntries(
            entries = items,
            sort = sortByNameAsc,
            naturalSortEnabled = true,
            directoriesFirst = true,
        )
        assertEquals(
            listOf("assets", "zebra", "chapter1.txt", "chapter2.txt", "chapter10.txt"),
            naturalDirsFirst.map { it.name },
        )

        val lexicalMixedDirs = EntrySorter.sortEntries(
            entries = items,
            sort = sortByNameAsc,
            naturalSortEnabled = false,
            directoriesFirst = false,
        )
        assertEquals(
            listOf("assets", "chapter1.txt", "chapter10.txt", "chapter2.txt", "zebra"),
            lexicalMixedDirs.map { it.name },
        )
    }

    @Test
    fun `displayFileName hides file extension while preserving dotfiles and directory names`() {
        val regularFile = VFile(
            id = "1",
            name = "archive.tar.gz",
            location = "C:\\archive.tar.gz",
            parentLocation = "C:\\",
            kind = VFileKind.FILE,
            sizeBytes = 100L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )
        val dotFile = VFile(
            id = "2",
            name = ".gitignore",
            location = "C:\\.gitignore",
            parentLocation = "C:\\",
            kind = VFileKind.FILE,
            sizeBytes = 100L,
            modifiedAtEpochMillis = 0L,
            hidden = true,
            capabilities = emptySet(),
        )
        val directory = VFile(
            id = "3",
            name = "release.v1.0",
            location = "C:\\release.v1.0",
            parentLocation = "C:\\",
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )

        assertEquals("archive.tar.gz", regularFile.displayFileName(showExtensions = true))
        assertEquals("archive.tar", regularFile.displayFileName(showExtensions = false))
        assertEquals(".gitignore", dotFile.displayFileName(showExtensions = false))
        assertEquals("release.v1.0", directory.displayFileName(showExtensions = false))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `visual render capture of redesigned SettingsDialogLayout and HSV Alpha Color Picker`() =
        runDesktopComposeUiTest(width = 880, height = 720) {
            val savedCustom = CustomThemePreset(
                id = "custom-cyber",
                name = "赛博霓虹粉",
                themeMode = OnyxThemeMode.DARK,
                basePreset = OnyxThemePreset.DRACULA_PURPLE,
                themeColors = OnyxThemeColors(accentArgb = 0xFFEC4899L),
                fileListColors = FileListColors(selectionArgb = 0x59EC4899L),
            )
            var currentCategory = SettingsCategory.THEME_APPEARANCE
            var currentSettings = OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                themePreset = OnyxThemePreset.DRACULA_PURPLE,
                customThemes = listOf(savedCustom),
                activeCustomThemeId = savedCustom.id,
                themeColors = savedCustom.themeColors,
                fileListColors = savedCustom.fileListColors,
            )

            setContent {
                OnyxWindowTheme(settings = currentSettings) {
                    val appearance = LocalOnyxAppearance.current
                    SettingsDialogLayout(
                        title = "设置",
                        draft = currentSettings,
                        category = currentCategory,
                        onCategoryChange = {},
                        onDraftChange = {},
                        onCleanupInvalidLocations = {},
                        onConfirm = {},
                        onDismiss = {},
                        bodyFontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                    )
                }
            }

            waitForIdle()
            val darkThemeImage = onRoot().captureToImage().toAwtImage()
            assertTrue(darkThemeImage.width == 880 && darkThemeImage.height == 720)
            val reportDir = File("build/reports").apply { mkdirs() }
            ImageIO.write(darkThemeImage, "png", File(reportDir, "visual-settings-theme-dark.png"))

            // Capture expanded HSV + Alpha visual color picker
            setContent {
                OnyxWindowTheme(settings = currentSettings) {
                    val palette = LocalOnyxPalette.current
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.appBackground)
                            .padding(20.dp),
                    ) {
                        SettingsGroupCard(title = "全局界面颜色微调（展开 HSV + Alpha 透明度颜色选择器）") {
                            ColorOverrideField(
                                label = "活动面板选中背景（支持透明度）",
                                value = 0x998B5CF6L,
                                defaultPreviewColor = palette.selectionBackground,
                                initialExpanded = true,
                                onChange = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val pickerImage = onRoot().captureToImage().toAwtImage()
            ImageIO.write(pickerImage, "png", File(reportDir, "visual-settings-color-picker.png"))

            // Capture Views & Browsing page in Light Nordic Frost
            currentCategory = SettingsCategory.VIEWS_BROWSING
            currentSettings = OnyxSettings(
                themeMode = OnyxThemeMode.LIGHT,
                themePreset = OnyxThemePreset.NORDIC_FROST,
            )
            setContent {
                OnyxWindowTheme(settings = currentSettings) {
                    val appearance = LocalOnyxAppearance.current
                    SettingsDialogLayout(
                        title = "设置",
                        draft = currentSettings,
                        category = currentCategory,
                        onCategoryChange = {},
                        onDraftChange = {},
                        onCleanupInvalidLocations = {},
                        onConfirm = {},
                        onDismiss = {},
                        bodyFontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                    )
                }
            }

            waitForIdle()
            val lightViewsImage = onRoot().captureToImage().toAwtImage()
            assertTrue(lightViewsImage.width == 880 && lightViewsImage.height == 720)
            ImageIO.write(lightViewsImage, "png", File(reportDir, "visual-settings-views-light.png"))
        }
}
