@file:OptIn(ExperimentalTestApi::class)

package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxDecoratedWindowTheme
import com.oruke.onyx.ui.theme.rememberOnyxWindowIconPainter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.window.TitleBarScope

/**
 * 验证新版扁平二次元风格应用 Logo 资源规格（PNG 与多尺寸 ICO）及明暗主题标题栏渲染效果。
 */
class OnyxBrandLogoUiTest {

    @Test
    fun `logo png and windows multi-size ico resources meet specification`() {
        val pngStream = OnyxBrandLogoUiTest::class.java.getResourceAsStream(
            "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo.png",
        ) ?: OnyxBrandLogoUiTest::class.java.getResourceAsStream("/drawable/onyx_logo.png")
        assertNotNull(pngStream, "应能从类路径读取 onyx_logo.png")
        val image = pngStream.use(ImageIO::read)
        assertNotNull(image, "onyx_logo.png 应能被 ImageIO 正常解码")
        assertEquals(EXPECTED_LOGO_DIMENSION, image.width)
        assertEquals(EXPECTED_LOGO_DIMENSION, image.height)
        // 四角应保持透明（异形扁平贴纸图标），中心区域应为不透明像素
        assertEquals(0, (image.getRGB(2, 2) ushr ALPHA_SHIFT) and BYTE_MASK)
        assertEquals(FULL_ALPHA, (image.getRGB(image.width / 2, image.height / 2) ushr ALPHA_SHIFT) and BYTE_MASK)

        val smallStream = OnyxBrandLogoUiTest::class.java.getResourceAsStream(
            "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo_small.png",
        ) ?: OnyxBrandLogoUiTest::class.java.getResourceAsStream("/drawable/onyx_logo_small.png")
        assertNotNull(smallStream, "应能从类路径读取小尺寸光学优化图标 onyx_logo_small.png")
        val smallImage = smallStream.use(ImageIO::read)
        assertNotNull(smallImage, "onyx_logo_small.png 应能被 ImageIO 正常解码")
        assertEquals(EXPECTED_SMALL_LOGO_DIMENSION, smallImage.width)
        assertEquals(EXPECTED_SMALL_LOGO_DIMENSION, smallImage.height)

        val icoBytes = assertNotNull(
            OnyxBrandLogoUiTest::class.java.getResourceAsStream("/onyx.ico")?.use { it.readBytes() },
            "应能从类路径读取 Windows 安装包图标 onyx.ico",
        )
        val header = ByteBuffer.wrap(icoBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, header.short.toInt())
        assertEquals(ICO_TYPE_ICON, header.short.toInt())
        assertEquals(EXPECTED_ICO_ENTRY_COUNT, header.short.toInt())
    }

    @Test
    fun `brand logo renders cleanly in dark and light title bars and dialog surfaces`() =
        runDesktopComposeUiTest(width = 760, height = 420) {
            val darkSettings = OnyxSettings(
                themeMode = OnyxThemeMode.DARK,
                themePreset = OnyxThemePreset.DEFAULT,
            )
            val lightSettings = OnyxSettings(
                themeMode = OnyxThemeMode.LIGHT,
                themePreset = OnyxThemePreset.DEFAULT,
            )

            setContent {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        OnyxDecoratedWindowTheme(darkSettings) {
                            OnyxDialogSurface(
                                title = "Onyx Dark",
                                settings = darkSettings,
                                drawOuterBorder = true,
                                showCustomCloseButton = true,
                            ) {
                                val palette = LocalOnyxPalette.current
                                val iconPainter = rememberOnyxWindowIconPainter()
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(palette.appBackground)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .background(palette.titleBarBackground),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        PreviewTitleBarScope(iconPainter).TitleBarBrandTitle(
                                            title = "Onyx 1.4.15",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textColor = palette.foreground,
                                        )
                                    }
                                    androidx.compose.foundation.Image(
                                        painter = iconPainter,
                                        contentDescription = null,
                                        modifier = Modifier.size(196.dp),
                                    )
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        OnyxDecoratedWindowTheme(lightSettings) {
                            OnyxDialogSurface(
                                title = "Onyx Light",
                                settings = lightSettings,
                                drawOuterBorder = true,
                                showCustomCloseButton = true,
                            ) {
                                val palette = LocalOnyxPalette.current
                                val iconPainter = rememberOnyxWindowIconPainter()
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(palette.appBackground)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .background(palette.titleBarBackground),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        PreviewTitleBarScope(iconPainter).TitleBarBrandTitle(
                                            title = "Onyx 1.4.15",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textColor = palette.foreground,
                                        )
                                    }
                                    androidx.compose.foundation.Image(
                                        painter = iconPainter,
                                        contentDescription = null,
                                        modifier = Modifier.size(196.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            waitForIdle()
            onNodeWithText("Onyx Dark").assertExists()
            onNodeWithText("Onyx Light").assertExists()

            val outputDir = Path.of("build", "browsing-acceptance")
            Files.createDirectories(outputDir)
            val screenshot = onRoot().captureToImage().toAwtImage()
            assertTrue(ImageIO.write(screenshot, "png", outputDir.resolve("brand-logo-dark-light.png").toFile()))
        }
}

private class PreviewTitleBarScope(
    override val icon: Painter?,
) : TitleBarScope {
    override val title: String = "Onyx"

    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
}

private const val EXPECTED_LOGO_DIMENSION = 512
private const val EXPECTED_SMALL_LOGO_DIMENSION = 128
private const val ICO_TYPE_ICON = 1
private const val EXPECTED_ICO_ENTRY_COUNT = 9
private const val ALPHA_SHIFT = 24
private const val BYTE_MASK = 0xFF
private const val FULL_ALPHA = 255
