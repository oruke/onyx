package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.component.MAX_PREVIEW_PANE_WIDTH_DP
import com.oruke.onyx.app.component.MIN_PREVIEW_PANE_WIDTH_DP
import com.oruke.onyx.app.component.sanitizeRootSettings
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PreviewPaneMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.formatAspectRatio
import com.oruke.onyx.ui.formatAudioSampleRate
import com.oruke.onyx.ui.formatExactFileSize
import com.oruke.onyx.ui.formatFrameRate
import com.oruke.onyx.ui.formatMediaBitrate
import com.oruke.onyx.ui.formatMediaDuration
import com.oruke.onyx.ui.mergeEditableSettingsFrom
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 预览与元数据后端核心能力与风控回归测试：
 * 覆盖多编码自动探测、截断尾字符无 \uFFFD、精确行计数、归档头部有界预览与流式 SHA-256、
 * 无 Range 远程超大文本前置拦截、扩展名互斥无冲突、ffprobe/ffmpeg 超时取消销毁、PCM 跨块帧对齐与设置持久化。
 */
class PreviewAndMediaServiceTest {

    @Test
    fun textPreviewDecoderDetectsUtf8BomUtf16GbkShiftJisAndRejectsBinary() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "第一行\n第二行".toByteArray(StandardCharsets.UTF_8)
        val utf8Result = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(utf8Bom, PreviewTextEncoding.AUTO, maxLines = 100, bytesTruncated = false)
        )
        assertEquals(PreviewTextEncoding.UTF_8, utf8Result.detectedEncoding)
        assertEquals("第一行\n第二行", utf8Result.value)
        assertEquals(2, utf8Result.lineCount)
        assertFalse(utf8Result.truncated)

        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello 世界".toByteArray(StandardCharsets.UTF_16LE)
        val utf16Result = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(utf16Le, PreviewTextEncoding.AUTO, maxLines = 100, bytesTruncated = false)
        )
        assertEquals(PreviewTextEncoding.UTF_16, utf16Result.detectedEncoding)
        assertEquals("Hello 世界", utf16Result.value)

        val gbkSample = "不时轻声地以俄语遮羞的邻座艾莉同学 第一卷 正文预览".toByteArray(Charset.forName("GBK"))
        val gbkResult = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(gbkSample, PreviewTextEncoding.AUTO, maxLines = 100, bytesTruncated = false)
        )
        assertEquals(PreviewTextEncoding.GBK, gbkResult.detectedEncoding)
        assertTrue(gbkResult.value.contains("艾莉同学"))

        val sjisSample = "時々ボソッとロシア語でデレる隣のアーリャさん 第一章".toByteArray(Charset.forName("Shift_JIS"))
        val sjisResult = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(sjisSample, PreviewTextEncoding.AUTO, maxLines = 100, bytesTruncated = false)
        )
        assertEquals(PreviewTextEncoding.SHIFT_JIS, sjisResult.detectedEncoding)
        assertTrue(sjisResult.value.contains("ロシア語"))

        val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x00, 0x00, 0x0D)
        assertIs<PreviewTextResult.Unavailable>(
            TextPreviewDecoder.decode(binary, PreviewTextEncoding.AUTO, maxLines = 100, bytesTruncated = false)
        )
    }

    @Test
    fun textPreviewDecoderStripsTruncatedMultibyteTailsWithoutReplacementCharAndCountsTrailingNewlineExactly() {
        // 1. UTF-8 截断在 3 字节中文字符中间：无 \uFFFD，保持 UTF-8
        val utf8Full = "第一行中文内容测试".toByteArray(StandardCharsets.UTF_8)
        val utf8Cut = utf8Full.copyOf(utf8Full.size - 1)
        val utf8Decoded = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(utf8Cut, PreviewTextEncoding.AUTO, maxLines = 50, bytesTruncated = true)
        )
        assertEquals(PreviewTextEncoding.UTF_8, utf8Decoded.detectedEncoding)
        assertFalse(utf8Decoded.value.contains('\uFFFD'), "截断 UTF-8 不应产生末尾替换符: ${utf8Decoded.value}")
        assertEquals("第一行中文内容测", utf8Decoded.value)
        assertTrue(utf8Decoded.truncated)

        // 2. GBK 截断在 2 字节中文字符中间：仍识别为 GBK 且无 \uFFFD
        val gbkFull = "不时轻声地以俄语遮羞的邻座艾莉同学".toByteArray(Charset.forName("GBK"))
        val gbkCut = gbkFull.copyOf(gbkFull.size - 1)
        val gbkDecoded = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(gbkCut, PreviewTextEncoding.AUTO, maxLines = 50, bytesTruncated = true)
        )
        assertEquals(PreviewTextEncoding.GBK, gbkDecoded.detectedEncoding)
        assertFalse(gbkDecoded.value.contains('\uFFFD'), "截断 GBK 不应产生末尾替换符: ${gbkDecoded.value}")

        // 3. Shift-JIS 截断在 2 字节假名中间：仍识别为 Shift-JIS 且无 \uFFFD
        val sjisFull = "時々ボソッとロシア語でデレる隣のアーリャさん".toByteArray(Charset.forName("Shift_JIS"))
        val sjisCut = sjisFull.copyOf(sjisFull.size - 1)
        val sjisDecoded = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(sjisCut, PreviewTextEncoding.AUTO, maxLines = 50, bytesTruncated = true)
        )
        assertEquals(PreviewTextEncoding.SHIFT_JIS, sjisDecoded.detectedEncoding)
        assertFalse(sjisDecoded.value.contains('\uFFFD'), "截断 Shift-JIS 不应产生末尾替换符: ${sjisDecoded.value}")

        // 4. 恰好 500 行且以标准 \n 结尾的文件：行数为 500，不应误报截断
        val exact500Lines = (1..500).joinToString("\n", postfix = "\n") { "line-$it" }
        val exactResult = assertIs<PreviewTextResult.Text>(
            TextPreviewDecoder.decode(
                bytes = exact500Lines.toByteArray(StandardCharsets.UTF_8),
                requestedEncoding = PreviewTextEncoding.AUTO,
                maxLines = 500,
                bytesTruncated = false,
            )
        )
        assertEquals(500, exactResult.lineCount)
        assertFalse(exactResult.truncated)
    }

    @Test
    fun jvmPreviewAndHashServicesStreamArchivePrefixAndHashAndGuardUnboundedRemote() = runTest {
        val tempDir = Files.createTempDirectory("onyx-preview-test-")
        try {
            // 1. 本地大文件头部截断预览
            val bigLog = tempDir.resolve("large.log")
            val content = (1..300).joinToString("\n") { index -> "line-$index: " + "A".repeat(40) }
            Files.writeString(bigLog, content, StandardCharsets.UTF_8)

            val localService = JvmPreviewService()
            val bigEntry = sampleFile("big", "large.log", bigLog.toString(), tempDir.toString(), Files.size(bigLog))
            val preview = assertIs<PreviewTextResult.Text>(
                localService.loadTextPreview(PreviewTextRequest(bigEntry, maxBytes = 1024L, maxLines = 50))
            )
            assertTrue(preview.truncated)
            assertTrue(preview.value.startsWith("line-1:"))
            assertEquals(Files.size(bigLog), preview.totalBytes)

            // 2. 压缩包（EPUB）内的普通 XHTML 与超大 256KB 日志：均走前缀提取不整包解压 OOM
            val epubPath = tempDir.resolve("novel.epub")
            val xhtmlContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html><body><p>你好，艾莉同学</p></body></html>"
            val hugeInnerLog = (1..4000).joinToString("\n") { idx -> "archive-line-$idx: " + "B".repeat(60) }
            val hugeInnerBytes = hugeInnerLog.toByteArray(StandardCharsets.UTF_8)
            ZipOutputStream(Files.newOutputStream(epubPath)).use { zip ->
                zip.putNextEntry(ZipEntry("OEBPS/Text/p-002.xhtml"))
                zip.write(xhtmlContent.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/Text/huge.log"))
                zip.write(hugeInnerBytes)
                zip.closeEntry()
            }

            val archiveService = ArchiveService()
            val previewService = JvmPreviewService(archiveService = archiveService)
            val xhtmlEntry = sampleFile(
                id = "xhtml",
                name = "p-002.xhtml",
                location = ArchiveService.archiveLocation(epubPath.toString(), "OEBPS/Text/p-002.xhtml"),
                parentLocation = ArchiveService.archiveLocation(epubPath.toString(), "OEBPS/Text"),
                sizeBytes = xhtmlContent.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            )
            val xhtmlPreview = assertIs<PreviewTextResult.Text>(
                previewService.loadTextPreview(PreviewTextRequest(xhtmlEntry, maxBytes = 64 * 1024L, maxLines = 100))
            )
            assertTrue(xhtmlPreview.value.contains("你好，艾莉同学"))
            assertFalse(xhtmlPreview.truncated)

            val hugeArchiveEntry = sampleFile(
                id = "huge-inner",
                name = "huge.log",
                location = ArchiveService.archiveLocation(epubPath.toString(), "OEBPS/Text/huge.log"),
                parentLocation = ArchiveService.archiveLocation(epubPath.toString(), "OEBPS/Text"),
                sizeBytes = hugeInnerBytes.size.toLong(),
            )
            val hugeArchivePreview = assertIs<PreviewTextResult.Text>(
                previewService.loadTextPreview(PreviewTextRequest(hugeArchiveEntry, maxBytes = 2048L, maxLines = 200))
            )
            assertTrue(hugeArchivePreview.truncated)
            assertTrue(hugeArchivePreview.value.startsWith("archive-line-1:"))
            assertEquals(hugeInnerBytes.size.toLong(), hugeArchivePreview.totalBytes)

            // 3. 归档条目流式 SHA-256 与标准摘要完全一致
            val expectedSha = MessageDigest.getInstance("SHA-256")
                .digest(hugeInnerBytes)
                .joinToString("") { "%02x".format(it) }
            val hashService = JvmFileHashService(archiveService)
            val computedHash = assertIs<FileHashResult.Hash>(
                hashService.readHash(FileHashRequest(hugeArchiveEntry, maxBytes = 10L * 1024 * 1024))
            )
            assertEquals(expectedSha, computedHash.value)

            // 4. 无随机访问能力的远程大文件必须在调用 readFile 前拦截返回 TooLarge
            var remoteReadCalled = false
            val fakeRemoteContent = object : RoutableVfsContentService {
                override fun supports(location: String): Boolean = location.startsWith("webdav://")
                override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
                    remoteReadCalled = true
                    return Result.success(VfsContentSource(entry.name, entry.sizeBytes, emptyFlow<ByteArray>()))
                }
                override suspend fun writeFile(
                    parentLocation: String,
                    name: String,
                    chunks: Flow<ByteArray>,
                    conflictStrategy: TransferConflictStrategy,
                ): Result<VFile?> = Result.success(null)
            }
            val remoteGuardService = JvmPreviewService(contentServices = listOf(fakeRemoteContent))
            val hugeRemote = sampleFile("rem-1", "huge.log", "webdav://nas/huge.log", "webdav://nas", 500L * 1024 * 1024)
            assertIs<PreviewTextResult.TooLarge>(
                remoteGuardService.loadTextPreview(PreviewTextRequest(hugeRemote, maxBytes = 256 * 1024L, maxLines = 500))
            )
            assertFalse(remoteReadCalled, "无 Range 能力的远程超大文件不应触发 readFile 整包下载")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun jvmFileTypeServiceIsDisjointAcrossCategoriesAndResolvesTsAsText() {
        val types = JvmFileTypeService()
        assertTrue(types.isTextPreviewFileName("p-002.xhtml"))
        assertTrue(types.isTextPreviewFileName("index.ts"))
        assertFalse(types.isVideoFileName("index.ts"), ".ts 应明确归文本，避免 TypeScript 误走视频探测")
        assertTrue(types.isVideoFileName("bluray.m2ts"))
        assertTrue(types.isVideoFileName("cam.mts"))
        assertTrue(types.isTextPreviewFileName("vector.svg"))
        assertFalse(types.isImageFileName("vector.svg"))
        assertTrue(types.isTextPreviewFileName("Makefile"))
        assertTrue(types.isTextPreviewFileName("LICENSE"))

        val samples = listOf(
            "a.png", "b.webp", "c.xhtml", "d.ts", "e.svg", "f.mp3", "g.flac", "h.mkv", "i.m2ts", "j.zip",
        )
        for (name in samples) {
            val matchedCategories = listOf(
                types.isImageFileName(name),
                types.isTextPreviewFileName(name),
                types.isAudioFileName(name),
                types.isVideoFileName(name),
                types.isArchiveFileName(name),
            ).count { it }
            assertEquals(1, matchedCategories, "文件名 $name 应严格落入单一互斥分类")
        }
    }

    @Test
    fun processRunnerEnforcesTimeoutCancellationAndOutputByteLimit() = runBlocking(Dispatchers.IO) {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val sleepCmd = if (isWindows) {
            listOf("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 15")
        } else {
            listOf("sleep", "15")
        }

        // 1. 挂起进程必须在超时时间到达时被看门狗强制终止，不能无限阻塞
        val start = System.currentTimeMillis()
        val timedOutResult = JvmMediaPreviewService.runProcessCapturingStdout(
            command = sleepCmd,
            timeoutMillis = 250L,
            maxOutputBytes = 1024 * 1024,
        )
        val elapsed = System.currentTimeMillis() - start
        assertNull(timedOutResult)
        assertTrue(elapsed < 5_000L, "超时看门狗应及时销毁挂起进程，实际耗时 ${elapsed}ms")

        // 2. 协程取消时立即销毁阻塞中的子进程
        val cancelStart = System.currentTimeMillis()
        val job = launch {
            JvmMediaPreviewService.runProcessCapturingStdout(
                command = sleepCmd,
                timeoutMillis = 15_000L,
                maxOutputBytes = 1024 * 1024,
            )
        }
        delay(200L)
        job.cancel()
        job.join()
        val cancelElapsed = System.currentTimeMillis() - cancelStart
        assertTrue(cancelElapsed < 5_000L, "协程取消应立即打断并销毁子进程，实际耗时 ${cancelElapsed}ms")

        // 3. 标准输出超过 maxOutputBytes 时立即中止并返回 null，防止 OOM
        val verboseCmd = if (isWindows) {
            listOf("powershell.exe", "-NoProfile", "-Command", "Write-Output ('X' * 4096)")
        } else {
            listOf("sh", "-c", "head -c 4096 /dev/zero | tr '\\0' 'X'")
        }
        val overflowResult = JvmMediaPreviewService.runProcessCapturingStdout(
            command = verboseCmd,
            timeoutMillis = 5_000L,
            maxOutputBytes = 128,
        )
        assertNull(overflowResult, "超过 maxOutputBytes 的异常输出必须被截断丢弃")
    }

    @Test
    fun pcmFrameAlignerPreservesCarryoverAcrossArbitraryMisalignedChunks() {
        val original = ByteArray(40) { idx -> (idx + 1).toByte() } // 10 个 4 字节立体声帧
        val aligner = JvmAudioPreviewPlayer.PcmFrameAligner(frameSize = 4, chunkCapacity = 16)
        val reassembled = mutableListOf<Byte>()

        // 故意按 1、3、5、2、7、1、9、6、6 等非 4 字节对齐分片送入
        val splitSizes = listOf(1, 3, 5, 2, 7, 1, 9, 6, 6)
        var offset = 0
        for (size in splitSizes) {
            val slice = original.copyOfRange(offset, offset + size)
            offset += size
            val alignedLen = aligner.pushAndAlign(slice, slice.size)
            assertEquals(0, alignedLen % 4, "输出长度必须严格为 frameSize 整数倍")
            for (i in 0 until alignedLen) {
                reassembled += aligner.alignedBuffer[i]
            }
        }
        assertContentEquals(original, reassembled.toByteArray())
    }

    @Test
    fun ffprobeJsonParserVolumeFormattingAndSettingsPersistenceBehaveCorrectly() = runTest {
        val videoJson = """
            {
              "streams": [
                { "codec_type": "video", "codec_name": "hevc", "width": 1920, "height": 1080, "avg_frame_rate": "24000/1001" },
                { "codec_type": "audio", "codec_name": "aac", "sample_rate": "48000", "channels": 2 },
                { "codec_type": "subtitle", "codec_name": "ass" }
              ],
              "format": { "duration": "1452.500", "bit_rate": "8500000" }
            }
        """.trimIndent()
        val videoInfo = assertNotNull(JvmMediaPreviewService.parseFfprobeJson(videoJson, MediaKind.VIDEO))
        assertEquals(1_452_500L, videoInfo.durationMillis)
        assertEquals("HEVC", videoInfo.videoCodec)
        assertEquals(1, videoInfo.subtitleTrackCount)

        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        JvmAudioPreviewPlayer.applyVolumeScale16BitLe(pcm, pcm.size, 0.5f)
        assertEquals(8192, (pcm[1].toInt() shl 8) or (pcm[0].toInt() and 0xFF))
        assertEquals(-8192, (pcm[3].toInt() shl 8) or (pcm[2].toInt() and 0xFF))

        assertEquals("04:15", formatMediaDuration(255_000L))
        assertEquals("8.50 Mbps", formatMediaBitrate(8_500_000L))
        assertEquals("48 kHz", formatAudioSampleRate(48_000))
        assertEquals("44.1 kHz", formatAudioSampleRate(44_100))
        assertEquals("23.98 fps", formatFrameRate(23.976))
        assertEquals("16:9", formatAspectRatio(1920, 1080))
        assertTrue(formatExactFileSize(18_642L).contains("18,642 B"))

        val clampedLow = OnyxSettings(previewPaneWidthDp = 50).sanitizeRootSettings()
        assertEquals(MIN_PREVIEW_PANE_WIDTH_DP, clampedLow.previewPaneWidthDp)
        val clampedHigh = OnyxSettings(previewPaneWidthDp = 999).sanitizeRootSettings()
        assertEquals(MAX_PREVIEW_PANE_WIDTH_DP, clampedHigh.previewPaneWidthDp)

        val live = OnyxSettings(previewPaneWidthDp = 420, previewPaneMode = PreviewPaneMode.METADATA)
        val merged = live.mergeEditableSettingsFrom(draft = OnyxSettings(uiScale = 125), initialDraft = OnyxSettings())
        assertEquals(420, merged.previewPaneWidthDp)
        assertEquals(PreviewPaneMode.METADATA, merged.previewPaneMode)
        assertEquals(125, merged.uiScale)
    }

    private fun sampleFile(
        id: String,
        name: String,
        location: String,
        parentLocation: String,
        sizeBytes: Long,
    ): VFile = VFile(
        id = id,
        name = name,
        location = location,
        parentLocation = parentLocation,
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = 0L,
        hidden = false,
        capabilities = emptySet(),
    )
}
