@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.AudioPlaybackSnapshot
import com.oruke.onyx.app.filesystem.AudioPreviewPlayer
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.MediaKind
import com.oruke.onyx.app.filesystem.MediaMetadataInfo
import com.oruke.onyx.app.filesystem.MediaMetadataResult
import com.oruke.onyx.app.filesystem.PreviewTextEncoding
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PreviewPaneMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_audio_channel_stereo
import org.jetbrains.compose.resources.getString
import java.awt.Color as AwtColor
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全新「预览与元数据」面板的 Compose Desktop 交互与 Skia 真实渲染截图验收测试。
 *
 * 覆盖：
 * - 28dp 紧凑单行 Icon 顶栏 + 右对齐切换/关闭图标（零大按钮浪费）
 * - XHTML 文本铺满高度预览、编码下拉切换、自动换行与截断提示
 * - 图片铺满预览 + 唤起内置查看器
 * - 音频专辑封面 + 手动点击试听控制条（默认不自动播放、停播与静音）
 * - 视频关键帧预览 + 10%/35%/65% 帧时间点切换
 * - 分组元数据视图 + 按需点击计算 SHA-256（含浅色主题渲染验证）
 */
class PreviewAndMetadataUiVerificationTest {

    @Test
    fun compactHeaderAndFullHeightXhtmlTextPreviewWorkEndToEnd() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val entry = sampleFile("xhtml-1", "p-002.xhtml", "archive://Z:\\novel.epub!/OEBPS/Text/p-002.xhtml", 18_642L)
            val requestedEncodings = mutableListOf<PreviewTextEncoding>()
            var closedCount by mutableIntStateOf(0)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Row(modifier = Modifier.fillMaxSize().background(LocalOnyxPalette.current.appBackground)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LocalOnyxPalette.current.surface))
                            PreviewPane(
                                selectedEntry = entry,
                                modifier = Modifier.width(340.dp).fillMaxHeight(),
                                onClose = { closedCount += 1 },
                                loadThumbnail = { _, _ -> null },
                                loadTextPreview = { req ->
                                    requestedEncodings += req.encoding
                                    val resolved = if (req.encoding == PreviewTextEncoding.AUTO) {
                                        PreviewTextEncoding.UTF_8
                                    } else {
                                        req.encoding
                                    }
                                    PreviewTextResult.Text(
                                        value = """
                                            <?xml version="1.0" encoding="utf-8"?>
                                            <html xmlns="http://www.w3.org/1999/xhtml">
                                            <head>
                                              <title>第一章 邻座的艾莉同学</title>
                                            </head>
                                            <body>
                                              <h1>第一章 邻座的艾莉同学</h1>
                                              <p>「И на меня тоже обрати внимание.」</p>
                                              <p>坐在窗边的银发少女轻轻转过头，小声嘀咕了一句俄语。</p>
                                              <p>当前使用编码：${resolved.displayLabel}</p>
                                            </body>
                                            </html>
                                        """.trimIndent(),
                                        detectedEncoding = resolved,
                                        truncated = true,
                                        lineCount = 12,
                                        totalBytes = 18_642L,
                                    )
                                },
                                isImageFileName = { false },
                                isTextPreviewFileName = { it.endsWith(".xhtml") },
                                readFileHash = { FileHashResult.Unavailable },
                                readArchiveInfo = { ArchiveInfoResult.Unavailable },
                                isArchiveFileName = { false },
                                onOpenExternal = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("preview-pane-header-title").assertIsDisplayed()
            onNodeWithTag("preview-sidebar-tab-preview").assertIsDisplayed()
            onNodeWithTag("preview-sidebar-tab-inspector").assertIsDisplayed()
            onNodeWithTag("preview-sidebar-close-button").assertIsDisplayed()
            onNodeWithTag("preview-text-body").assertIsDisplayed()
            onNodeWithTag("preview-text-status-badge").assertIsDisplayed()

            savePreviewScreenshot(onRoot().captureToImage().toAwtImage(), "01-preview-text-xhtml-dark.png")

            // 切换编码为 Shift-JIS 并切换自动换行
            onNodeWithTag("preview-encoding-button").performClick()
            waitForIdle()
            onNodeWithTag("preview-encoding-option-SHIFT_JIS").performClick()
            waitForIdle()
            assertTrue(PreviewTextEncoding.SHIFT_JIS in requestedEncodings)

            onNodeWithTag("preview-wrap-toggle").performClick()
            waitForIdle()

            onNodeWithTag("preview-sidebar-close-button").performClick()
            waitForIdle()
            assertEquals(1, closedCount)
        }

    @Test
    fun imagePreviewFillsAreaAndOpensImageViewer() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val entry = sampleFile("img-1", "cover-artwork.png", "Z:\\gallery\\cover-artwork.png", 2_450_110L)
            val demoImage = createGradientBitmap(640, 420, AwtColor(0x1E, 0x3A, 0x8A), AwtColor(0xEC, 0x48, 0x99))
            var openedInViewer by mutableStateOf<VFile?>(null)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Row(modifier = Modifier.fillMaxSize().background(LocalOnyxPalette.current.appBackground)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LocalOnyxPalette.current.surface))
                            PreviewPane(
                                selectedEntry = entry,
                                modifier = Modifier.width(340.dp).fillMaxHeight(),
                                onClose = {},
                                loadThumbnail = { _, _ -> demoImage },
                                peekCachedThumbnail = { _, _, _, _ -> demoImage },
                                loadTextPreview = { PreviewTextResult.Unavailable },
                                isImageFileName = { it.endsWith(".png") },
                                isTextPreviewFileName = { false },
                                readImageSize = { IntSize(1920, 1080) },
                                readFileHash = { FileHashResult.Unavailable },
                                readArchiveInfo = { ArchiveInfoResult.Unavailable },
                                isArchiveFileName = { false },
                                onOpenImageViewer = { openedInViewer = it },
                                onOpenExternal = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("preview-image-bitmap").assertIsDisplayed()
            onNodeWithTag("preview-open-image-viewer-button").assertIsDisplayed()
            savePreviewScreenshot(onRoot().captureToImage().toAwtImage(), "02-preview-image-dark.png")

            onNodeWithTag("preview-open-image-viewer-button").performClick()
            waitForIdle()
            assertEquals(entry.id, openedInViewer?.id)
        }

    @Test
    fun audioPreviewShowsCoverTagsAndManualPlayControlWithoutAutoplay() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val entry = sampleFile("aud-1", "01. 1番輝く星.flac", "Z:\\audio\\Music\\01. 1番輝く星.flac", 32_450_000L)
            val cover = createGradientBitmap(320, 320, AwtColor(0x31, 0x2E, 0x81), AwtColor(0x0E, 0xA5, 0xE9))
            val fakePlayer = FakeAudioPreviewPlayer()

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        Row(modifier = Modifier.fillMaxSize().background(LocalOnyxPalette.current.appBackground)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LocalOnyxPalette.current.surface))
                            PreviewPane(
                                selectedEntry = entry,
                                modifier = Modifier.width(340.dp).fillMaxHeight(),
                                onClose = {},
                                loadThumbnail = { _, _ -> null },
                                loadTextPreview = { PreviewTextResult.Unavailable },
                                isImageFileName = { false },
                                isTextPreviewFileName = { false },
                                isAudioFileName = { it.endsWith(".flac") },
                                readFileHash = { FileHashResult.Unavailable },
                                readArchiveInfo = { ArchiveInfoResult.Unavailable },
                                isArchiveFileName = { false },
                                readMediaMetadata = {
                                    MediaMetadataResult.Ready(
                                        MediaMetadataInfo(
                                            kind = MediaKind.AUDIO,
                                            durationMillis = 255_000L,
                                            audioCodec = "FLAC",
                                            sampleRateHz = 44_100,
                                            channels = 2,
                                            bitrateBps = 980_000L,
                                            title = "1番輝く星",
                                            artist = "周防有希",
                                            album = "Character Song Vol.1",
                                            hasEmbeddedCover = true,
                                        )
                                    )
                                },
                                loadAudioCover = { _, _ -> cover },
                                audioPreviewPlayer = fakePlayer,
                                onOpenExternal = {},
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(300L)
            waitForIdle()

            // 默认绝对不自动播放，且音频声道通过官方字符串资源本地化渲染
            val stereoLabel = getString(Res.string.label_audio_channel_stereo)
            assertEquals(0, fakePlayer.toggleCount)
            onNodeWithText("1番輝く星").assertIsDisplayed()
            onNodeWithText("FLAC · 980 kbps · 44.1 kHz · $stereoLabel").assertIsDisplayed()
            onNodeWithTag("preview-audio-play-button").assertIsDisplayed()

            // 手动点击播放后更新播放态并截图
            onNodeWithTag("preview-audio-play-button").performClick()
            waitForIdle()
            assertEquals(1, fakePlayer.toggleCount)
            assertTrue(fakePlayer.state.value.isPlaying)
            savePreviewScreenshot(onRoot().captureToImage().toAwtImage(), "03-preview-audio-dark.png")

            // 点击静音与停止
            onNodeWithTag("preview-audio-mute-button").performClick()
            waitForIdle()
            assertEquals(0f, fakePlayer.state.value.volume)
            onNodeWithTag("preview-audio-stop-button").performClick()
            waitForIdle()
            assertTrue(fakePlayer.stopCount >= 1)
        }

    @Test
    fun videoPreviewSupportsThreeFramePositionsAndMetadataViewUsesOnDemandSha256() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val entry = sampleFile("vid-1", "Episode_01_1080p.mkv", "Z:\\video\\Episode_01_1080p.mkv", 1_480_000_000L)
            val frame10 = createGradientBitmap(640, 360, AwtColor(0x0F, 0x17, 0x2A), AwtColor(0x25, 0x63, 0xEB))
            val frame35 = createGradientBitmap(640, 360, AwtColor(0x14, 0x53, 0x2D), AwtColor(0x10, 0xB9, 0x81))
            val frame65 = createGradientBitmap(640, 360, AwtColor(0x7C, 0x2D, 0x12), AwtColor(0xF5, 0x9E, 0x0B))
            var lastRequestedRatio by mutableFloatStateOf(-1f)
            var hashCalls by mutableIntStateOf(0)
            var activeMode by mutableStateOf(PreviewPaneMode.PREVIEW)
            var themeMode by mutableStateOf(OnyxThemeMode.DARK)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = themeMode)) {
                        Row(modifier = Modifier.fillMaxSize().background(LocalOnyxPalette.current.appBackground)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LocalOnyxPalette.current.surface))
                            PreviewPane(
                                selectedEntry = entry,
                                modifier = Modifier.width(340.dp).fillMaxHeight(),
                                activeMode = activeMode,
                                onSelectMode = { activeMode = it },
                                onClose = {},
                                loadThumbnail = { _, _ -> null },
                                loadTextPreview = { PreviewTextResult.Unavailable },
                                isImageFileName = { false },
                                isTextPreviewFileName = { false },
                                isVideoFileName = { it.endsWith(".mkv") },
                                readFileHash = {
                                    hashCalls += 1
                                    FileHashResult.Hash("SHA-256", "deadbeef0123456789abcdef0123456789abcdef0123456789abcdef01234567")
                                },
                                readArchiveInfo = { ArchiveInfoResult.Unavailable },
                                isArchiveFileName = { false },
                                readMediaMetadata = {
                                    MediaMetadataResult.Ready(
                                        MediaMetadataInfo(
                                            kind = MediaKind.VIDEO,
                                            durationMillis = 1_452_000L,
                                            width = 1920,
                                            height = 1080,
                                            frameRate = 23.976,
                                            videoCodec = "HEVC",
                                            audioCodec = "AAC",
                                            sampleRateHz = 48_000,
                                            channels = 2,
                                            bitrateBps = 8_200_000L,
                                            subtitleTrackCount = 2,
                                        )
                                    )
                                },
                                loadVideoFrame = { _, ratio, _, _ ->
                                    lastRequestedRatio = ratio
                                    when {
                                        ratio < 0.2f -> frame10
                                        ratio < 0.5f -> frame35
                                        else -> frame65
                                    }
                                },
                                onOpenExternal = {},
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(300L)
            waitForIdle()

            onNodeWithTag("preview-video-frame-image").assertIsDisplayed()
            assertEquals(0.10f, lastRequestedRatio)
            savePreviewScreenshot(onRoot().captureToImage().toAwtImage(), "04-preview-video-dark.png")

            // 切换到 35% 与 65% 预览帧
            onNodeWithTag("preview-video-frame-35").performClick()
            waitForIdle()
            assertEquals(0.35f, lastRequestedRatio)

            onNodeWithTag("preview-video-frame-65").performClick()
            waitForIdle()
            assertEquals(0.65f, lastRequestedRatio)

            // 切换到浅色模式 + 元数据模式：验证 SHA-256 默认不自动计算，点击后才触发
            themeMode = OnyxThemeMode.LIGHT
            onNodeWithTag("preview-sidebar-tab-inspector").performClick()
            mainClock.advanceTimeBy(300L)
            waitForIdle()

            assertEquals(0, hashCalls)
            onNodeWithText("HEVC").assertIsDisplayed()
            onNodeWithTag("metadata-compute-sha256-button").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(1, hashCalls)
            onNodeWithText("deadbeef0123456789abcdef0123456789abcdef0123456789abcdef01234567")
                .performScrollTo()
                .assertIsDisplayed()
            savePreviewScreenshot(onRoot().captureToImage().toAwtImage(), "05-metadata-media-light.png")
        }

    @Test
    fun sha256CancelAndImmediateRestartDoesNotLoseComputingState() =
        runDesktopComposeUiTest(width = 600, height = 640) {
            val locale = MutableStateFlow(AppLocale.SIMPLIFIED_CHINESE)
            val entry = sampleFile("f-race", "big.bin", "Z:\\big.bin", 10_000_000L)
            val firstGate = kotlinx.coroutines.CompletableDeferred<FileHashResult>()
            val secondGate = kotlinx.coroutines.CompletableDeferred<FileHashResult>()
            var invocation by mutableIntStateOf(0)

            setContent {
                OnyxLocaleEnvironment(locale) {
                    OnyxWindowTheme(OnyxSettings(themeMode = OnyxThemeMode.DARK)) {
                        PreviewPane(
                            selectedEntry = entry,
                            modifier = Modifier.width(340.dp).fillMaxHeight(),
                            activeMode = PreviewPaneMode.METADATA,
                            loadThumbnail = { _, _ -> null },
                            loadTextPreview = { PreviewTextResult.Unavailable },
                            isImageFileName = { false },
                            isTextPreviewFileName = { false },
                            readFileHash = {
                                invocation += 1
                                if (invocation == 1) firstGate.await() else secondGate.await()
                            },
                            readArchiveInfo = { ArchiveInfoResult.Unavailable },
                            isArchiveFileName = { false },
                        )
                    }
                }
            }
            waitForIdle()

            // 启动第 1 次计算 -> 取消 -> 立刻启动第 2 次计算
            onNodeWithTag("metadata-compute-sha256-button").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("metadata-cancel-sha256-button").performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithTag("metadata-compute-sha256-button").performScrollTo().performClick()
            waitForIdle()

            // 第 2 次任务仍在计算中，取消按钮必须稳定可见，不能被第 1 次任务的 finally 误清掉
            onNodeWithTag("metadata-cancel-sha256-button").performScrollTo().assertIsDisplayed()

            secondGate.complete(FileHashResult.Hash("SHA-256", "cafebabe0123456789abcdef0123456789abcdef0123456789abcdef01234567"))
            waitForIdle()
            onNodeWithText("cafebabe0123456789abcdef0123456789abcdef0123456789abcdef01234567")
                .performScrollTo()
                .assertIsDisplayed()
        }

    private class FakeAudioPreviewPlayer : AudioPreviewPlayer {
        private val backing = MutableStateFlow(AudioPlaybackSnapshot(volume = 0.85f))
        override val state: StateFlow<AudioPlaybackSnapshot> = backing
        var toggleCount: Int = 0
        var stopCount: Int = 0

        override fun canPlay(entry: VFile): Boolean = true

        override fun togglePlayPause(entry: VFile, durationMillis: Long?) {
            toggleCount += 1
            val current = backing.value
            val nextPlaying = !(current.location == entry.location && current.isPlaying)
            backing.value = current.copy(
                location = entry.location,
                isPlaying = nextPlaying,
                positionMillis = if (nextPlaying) 42_000L else current.positionMillis,
                durationMillis = durationMillis ?: 255_000L,
            )
        }

        override fun seekTo(entry: VFile, positionMillis: Long, durationMillis: Long?) {
            backing.value = backing.value.copy(
                location = entry.location,
                positionMillis = positionMillis,
                durationMillis = durationMillis ?: backing.value.durationMillis,
            )
        }

        override fun setVolume(volume: Float) {
            backing.value = backing.value.copy(volume = volume.coerceIn(0f, 1f))
        }

        override fun stop() {
            stopCount += 1
            backing.value = backing.value.copy(isPlaying = false, positionMillis = 0L)
        }

        override fun dispose() {
            stop()
        }
    }

    private fun sampleFile(id: String, name: String, location: String, sizeBytes: Long): VFile {
        return VFile(
            id = id,
            name = name,
            location = location,
            kind = VFileKind.FILE,
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = 1_760_000_000_000L,
            parentLocation = location.substringBeforeLast('\\', location.substringBeforeLast('/', "")),
            hidden = false,
            capabilities = setOf(
                VFileCapability.READ_CONTENT,
                VFileCapability.WRITE_CONTENT,
                VFileCapability.RENAME,
                VFileCapability.DELETE,
            ),
        )
    }

    private fun createGradientBitmap(width: Int, height: Int, start: AwtColor, end: AwtColor): ImageBitmap {
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, start, width.toFloat(), height.toFloat(), end)
            g.fillRect(0, 0, width, height)
            g.color = AwtColor(255, 255, 255, 55)
            g.fillOval(width / 6, height / 6, width / 3, height / 3)
            g.color = AwtColor(255, 255, 255, 40)
            g.fillRoundRect(width / 2, height / 2, width / 3, height / 4, 18, 18)
        } finally {
            g.dispose()
        }
        return buffered.toComposeImageBitmap()
    }

    private fun savePreviewScreenshot(image: BufferedImage, fileName: String) {
        val outputDir = File("build/preview-redesign-screenshots").apply { mkdirs() }
        val outFile = File(outputDir, fileName)
        ImageIO.write(image, "png", outFile)
        assertTrue(outFile.length() > 0L, "预览面板截图写入失败: ${outFile.absolutePath}")
    }
}
