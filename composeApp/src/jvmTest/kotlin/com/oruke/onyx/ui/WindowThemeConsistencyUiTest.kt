@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class, ComposeToolingApi::class)

package com.oruke.onyx.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeColors
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.LocalOnyxSettings
import com.oruke.onyx.ui.theme.OnyxDecoratedWindowTheme
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.resolveOnyxPalette
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.theme.defaultButtonStyle

/**
 * 验证子窗口、模态对话框沉浸式标题栏、主次按钮样式与主窗口主题（明暗模式、预设、自定义强调色与缩放）的一致性。
 */
class WindowThemeConsistencyUiTest {

    @Test
    fun `dialog surface title bar and buttons sync with main window presets and overrides`() =
        runDesktopComposeUiTest(width = 880, height = 640) {
            var activeSettings by mutableStateOf(
                OnyxSettings(
                    themeMode = OnyxThemeMode.DARK,
                    themePreset = OnyxThemePreset.DEFAULT,
                ),
            )
            var observedPalette: OnyxPalette? = null
            var observedButtonBg: Color? = null

            setContent {
                OnyxDialogSurface(
                    title = "设置",
                    settings = activeSettings,
                    drawOuterBorder = true,
                    showCustomCloseButton = true,
                ) {
                    observedPalette = LocalOnyxPalette.current
                    observedButtonBg = (JewelTheme.defaultButtonStyle.colors.background as? SolidColor)?.value
                    val appearance = LocalOnyxAppearance.current
                    SettingsDialogLayout(
                        title = "设置",
                        draft = activeSettings,
                        category = SettingsCategory.GENERAL,
                        onCategoryChange = {},
                        onDraftChange = {},
                        onCleanupInvalidLocations = {},
                        onConfirm = {},
                        onDismiss = {},
                        bodyFontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                        showTitlePrefix = false,
                    )
                }
            }

            waitForIdle()
            onNodeWithTag("onyx-dialog-title-bar", useUnmergedTree = true).assertExists()
            val defaultDarkPalette = resolveOnyxPalette(OnyxThemePreset.DEFAULT, dark = true)
            assertEquals(defaultDarkPalette.titleBarBackground, assertNotNull(observedPalette).titleBarBackground)
            assertEquals(defaultDarkPalette.appBackground, assertNotNull(observedPalette).appBackground)

            val outputDir = Path.of("build", "browsing-acceptance")
            Files.createDirectories(outputDir)
            val defaultDarkImg = onRoot().captureToImage().toAwtImage()
            assertTrue(ImageIO.write(defaultDarkImg, "png", outputDir.resolve("window-theme-settings-dark.png").toFile()))

            val customRoseSettings = OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                themePreset = OnyxThemePreset.DRACULA_PURPLE,
                themeColors = OnyxThemeColors(accentArgb = 0xFFEC4899L),
            )
            val defaultButtonBefore = assertNotNull(observedButtonBg)
            runOnUiThread {
                activeSettings = customRoseSettings
            }
            waitForIdle()

            val expectedDracula = resolveOnyxPalette(
                themePreset = OnyxThemePreset.DRACULA_PURPLE,
                dark = true,
                overrides = customRoseSettings.themeColors,
            )
            assertEquals(expectedDracula.titleBarBackground, assertNotNull(observedPalette).titleBarBackground)
            assertEquals(expectedDracula.accent, assertNotNull(observedPalette).accent)
            assertTrue(
                assertNotNull(observedButtonBg) != defaultButtonBefore,
                "DefaultButton background should update when theme preset/accent changes",
            )
            val draculaImg = onRoot().captureToImage().toAwtImage()
            assertTrue(
                ImageIO.write(draculaImg, "png", outputDir.resolve("window-theme-settings-dracula.png").toFile()),
            )
        }

    @Test
    fun `native modal dialogs inherit host window theme scale and immersive title bar`() =
        runDesktopComposeUiTest {
            val hostSettings = OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                themePreset = OnyxThemePreset.FOREST_EMERALD,
                uiScale = 125,
            )
            var showSettings by mutableStateOf(true)
            var showConfirmation by mutableStateOf(false)
            var confirmationPalette: OnyxPalette? = null
            var confirmationDensity: Density? = null

            try {
                setContent {
                    OnyxDecoratedWindowTheme(hostSettings) {
                        CompositionLocalProvider(LocalOnyxSettings provides hostSettings) {
                            if (showSettings) {
                                SettingsDialog(
                                    state = RootDialogState.Settings(draft = hostSettings),
                                    onDraftChange = {},
                                    onCleanupInvalidLocations = {},
                                    onConfirm = {},
                                    onDismiss = { showSettings = false },
                                    initialWidth = 820,
                                    initialHeight = 580,
                                )
                            }
                            if (showConfirmation) {
                                OnyxDialogWindow(
                                    onCloseRequest = { showConfirmation = false },
                                    title = "确认删除",
                                    windowName = "onyx-test-inherited-dialog",
                                ) {
                                    confirmationPalette = LocalOnyxPalette.current
                                    confirmationDensity = androidx.compose.ui.platform.LocalDensity.current
                                    DialogFrame(
                                        title = "确认删除",
                                        body = {},
                                        actions = {},
                                    )
                                }
                            }
                        }
                    }
                }

                waitUntil(timeoutMillis = DIALOG_WAIT_TIMEOUT_MILLIS) {
                    runOnUiThread { findShowingDialog("onyx-settings") != null }
                }
                val settingsWindow = assertNotNull(runOnUiThread { findShowingDialog("onyx-settings") })
                waitUntil(timeoutMillis = DIALOG_WAIT_TIMEOUT_MILLIS) {
                    runOnUiThread { settingsWindow.hasDialogTag("onyx-dialog-title-bar") }
                }
                captureNativeDialogWindow(settingsWindow, "window-theme-native-dialog.png")

                runOnUiThread {
                    showSettings = false
                    showConfirmation = true
                }
                waitUntil(timeoutMillis = DIALOG_WAIT_TIMEOUT_MILLIS) {
                    runOnUiThread {
                        findShowingDialog("onyx-settings") == null &&
                            findShowingDialog("onyx-test-inherited-dialog") != null &&
                            confirmationPalette != null
                    }
                }
                val expectedEmerald = resolveOnyxPalette(OnyxThemePreset.FOREST_EMERALD, dark = true)
                assertEquals(expectedEmerald.appBackground, assertNotNull(confirmationPalette).appBackground)
                assertEquals(expectedEmerald.accent, assertNotNull(confirmationPalette).accent)
                assertNotNull(confirmationDensity)
            } finally {
                runOnUiThread {
                    showSettings = false
                    showConfirmation = false
                }
                waitForIdle()
            }
        }

    private fun findShowingDialog(name: String): ComposeDialog? =
        Window.getWindows()
            .filterIsInstance<ComposeDialog>()
            .firstOrNull { dialog -> dialog.name == name && dialog.isShowing }

    private fun ComposeDialog.hasDialogTag(tag: String): Boolean {
        val pending = ArrayDeque(semanticsOwners.map { owner -> owner.unmergedRootSemanticsNode })
        while (pending.isNotEmpty()) {
            val node: SemanticsNode = pending.removeFirst()
            if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) {
                return true
            }
            pending.addAll(node.children)
        }
        return false
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.captureNativeDialogWindow(
        dialog: ComposeDialog,
        fileName: String,
    ) {
        val wasAlwaysOnTop = runOnUiThread {
            val prev = dialog.isAlwaysOnTop
            dialog.isAlwaysOnTop = true
            dialog.toFront()
            dialog.renderImmediately()
            prev
        }
        try {
            waitForIdle()
            val robot = Robot()
            robot.waitForIdle()
            Toolkit.getDefaultToolkit().sync()
            robot.delay(NATIVE_CAPTURE_SETTLE_MILLIS)
            val bounds = runOnUiThread {
                val origin = dialog.locationOnScreen
                Rectangle(origin.x, origin.y, dialog.width.coerceAtLeast(1), dialog.height.coerceAtLeast(1))
            }
            val output = Path.of("build", "browsing-acceptance", fileName)
            Files.createDirectories(output.parent)
            assertTrue(ImageIO.write(robot.createScreenCapture(bounds), "png", output.toFile()))
        } finally {
            runOnUiThread { dialog.isAlwaysOnTop = wasAlwaysOnTop }
        }
    }
}

private const val DIALOG_WAIT_TIMEOUT_MILLIS = 15_000L
private const val NATIVE_CAPTURE_SETTLE_MILLIS = 300
