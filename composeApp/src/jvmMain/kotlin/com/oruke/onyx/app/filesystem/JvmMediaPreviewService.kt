package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import kotlin.math.roundToLong

/**
 * 基于宿主机 `ffprobe` / `ffmpeg` 与 JVM 原生音频能力的零重依赖媒体预览服务。
 *
 * - 仅对本地路径与映射盘（如 `Z:\`）执行媒体探测与抽帧，远程虚拟协议与归档内媒体自动跳过；
 * - 所有外部进程均绑定真实超时看门狗、协程取消强制销毁钩子与标准输出字节上限保护；
 * - 无 `ffprobe`/`ffmpeg` 时自动降级（WAV/AIFF/AU 仍通过 `javax.sound.sampled` 读取时长与采样率）；
 * - 探测结果与抽帧位图均内置按 `(location, mtime, size)` 索引的有界 LRU 缓存。
 *
 * @param fileTypeService 统一文件类型判断服务。
 */
internal class JvmMediaPreviewService(
    private val fileTypeService: FileTypeService,
) : MediaPreviewService {

    private val probeCommand: String? by lazy { resolveExecutable("ffprobe") }
    private val ffmpegCommand: String? by lazy { resolveExecutable("ffmpeg") }

    private val metadataCache = object : LinkedHashMap<String, MediaMetadataResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataResult>?): Boolean {
            return size > MAX_METADATA_CACHE_ENTRIES
        }
    }

    /** 视频抽帧与音频内嵌封面的字节与条数双重封顶 LRU 缓存，淘汰时联动引用计数显式释放原生 Skia 位图。 */
    private val frameCache = ByteBoundedBitmapLruCache(
        maxEntries = MAX_FRAME_CACHE_ENTRIES,
        maxBytes = MAX_FRAME_CACHE_BYTES,
        onEvict = ManagedBitmapRegistry::release,
    )

    override val isProbeAvailable: Boolean
        get() = probeCommand != null

    override val isFrameExtractionAvailable: Boolean
        get() = ffmpegCommand != null

    /** 清空内存中的媒体探测元数据与抽帧/封面位图缓存。 */
    override fun clearCache() {
        synchronized(metadataCache) { metadataCache.clear() }
        frameCache.clear()
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun readMediaMetadata(entry: VFile): MediaMetadataResult = withContext(Dispatchers.IO) {
        if (entry.kind != VFileKind.FILE) return@withContext MediaMetadataResult.Unavailable
        val mediaKind = resolveMediaKind(entry.name) ?: return@withContext MediaMetadataResult.Unavailable
        if (isRemoteOrArchiveLocation(entry.location)) {
            return@withContext MediaMetadataResult.RemoteSkipped
        }
        val path = runCatching { Path.of(entry.location) }.getOrNull()
            ?: return@withContext MediaMetadataResult.Unavailable
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return@withContext MediaMetadataResult.Unavailable
        }

        val cacheKey = "${entry.location}|${entry.modifiedAtEpochMillis}|${entry.sizeBytes}"
        synchronized(metadataCache) { metadataCache[cacheKey] }?.let { return@withContext it }

        val result = try {
            val ffprobe = probeCommand
            if (ffprobe != null) {
                probeWithFfprobe(ffprobe, path, mediaKind)
            } else if (mediaKind == MediaKind.AUDIO) {
                probeNativeAudioFallback(path.toFile()) ?: MediaMetadataResult.ToolUnavailable
            } else {
                MediaMetadataResult.ToolUnavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MediaMetadataResult.Failed(failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
        }

        if (result is MediaMetadataResult.Ready) {
            synchronized(metadataCache) { metadataCache[cacheKey] = result }
        }
        result
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadVideoFrame(
        entry: VFile,
        positionRatio: Float,
        durationMillis: Long?,
        maxDimension: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (entry.kind != VFileKind.FILE || !fileTypeService.isVideoFileName(entry.name)) return@withContext null
        if (isRemoteOrArchiveLocation(entry.location)) return@withContext null
        val ffmpeg = ffmpegCommand ?: return@withContext null
        val path = runCatching { Path.of(entry.location) }.getOrNull() ?: return@withContext null
        if (!Files.exists(path) || !Files.isRegularFile(path)) return@withContext null

        val safeRatio = positionRatio.coerceIn(0f, 0.95f)
        val cacheKey = "video|${entry.location}|${(safeRatio * 100).toInt()}|$maxDimension|${entry.modifiedAtEpochMillis}|${entry.sizeBytes}"
        frameCache.get(cacheKey)?.let { return@withContext it }

        val seekSeconds = computeSeekSeconds(safeRatio, durationMillis)
        val bitmap = try {
            extractVideoFramePng(ffmpeg, path, seekSeconds, maxDimension)
                ?: if (seekSeconds > 0.0) {
                    extractVideoFramePng(ffmpeg, path, 0.0, maxDimension)
                } else {
                    null
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) {
            ManagedBitmapRegistry.register(bitmap)
            frameCache.put(cacheKey, bitmap)
        }
        bitmap
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadAudioCover(
        entry: VFile,
        maxDimension: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (entry.kind != VFileKind.FILE || !fileTypeService.isAudioFileName(entry.name)) return@withContext null
        if (isRemoteOrArchiveLocation(entry.location)) return@withContext null
        val ffmpeg = ffmpegCommand ?: return@withContext null
        val path = runCatching { Path.of(entry.location) }.getOrNull() ?: return@withContext null
        if (!Files.exists(path) || !Files.isRegularFile(path)) return@withContext null

        val cacheKey = "cover|${entry.location}|$maxDimension|${entry.modifiedAtEpochMillis}|${entry.sizeBytes}"
        frameCache.get(cacheKey)?.let { return@withContext it }

        val scaleFilter = buildScaleFilter(maxDimension)
        val command = listOf(
            ffmpeg,
            "-v",
            "error",
            "-i",
            path.toString(),
            "-an",
            "-map",
            "0:v:0",
            "-frames:v",
            "1",
            "-vf",
            scaleFilter,
            "-f",
            "image2pipe",
            "-vcodec",
            "png",
            "pipe:1",
        )
        val bytes = try {
            runProcessCapturingStdout(command, FRAME_TIMEOUT_MILLIS, MAX_FRAME_PNG_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val bitmap = decodePngBytes(bytes) ?: return@withContext null
        ManagedBitmapRegistry.register(bitmap)
        frameCache.put(cacheKey, bitmap)
        bitmap
    }

    private fun resolveMediaKind(fileName: String): MediaKind? = when {
        fileTypeService.isVideoFileName(fileName) -> MediaKind.VIDEO
        fileTypeService.isAudioFileName(fileName) -> MediaKind.AUDIO
        else -> null
    }

    private suspend fun probeWithFfprobe(
        ffprobe: String,
        path: Path,
        kind: MediaKind,
    ): MediaMetadataResult {
        val command = listOf(
            ffprobe,
            "-v",
            "quiet",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            path.toString(),
        )
        val output = runProcessCapturingStdout(command, PROBE_TIMEOUT_MILLIS, MAX_PROBE_JSON_BYTES)
            ?: return MediaMetadataResult.Unavailable
        if (output.isEmpty()) return MediaMetadataResult.Unavailable
        val info = parseFfprobeJson(output.toString(Charsets.UTF_8), kind)
            ?: return MediaMetadataResult.Unavailable
        return MediaMetadataResult.Ready(info)
    }

    private fun probeNativeAudioFallback(file: File): MediaMetadataResult.Ready? {
        return runCatching {
            val fileFormat = AudioSystem.getAudioFileFormat(file)
            val format = fileFormat.format
            val frameRate = format.frameRate
            val frameLength = fileFormat.frameLength
            val durationMillis = if (frameRate > 0f && frameLength > 0) {
                ((frameLength / frameRate) * 1000.0).roundToLong()
            } else {
                null
            }
            MediaMetadataResult.Ready(
                MediaMetadataInfo(
                    kind = MediaKind.AUDIO,
                    durationMillis = durationMillis,
                    audioCodec = fileFormat.type.extension.uppercase(),
                    sampleRateHz = format.sampleRate.takeIf { it > 0f }?.toInt(),
                    channels = format.channels.takeIf { it > 0 },
                )
            )
        }.getOrNull()
    }

    private suspend fun extractVideoFramePng(
        ffmpeg: String,
        path: Path,
        seekSeconds: Double,
        maxDimension: Int,
    ): ImageBitmap? {
        val scaleFilter = buildScaleFilter(maxDimension)
        val command = buildList {
            add(ffmpeg)
            add("-v")
            add("error")
            if (seekSeconds > 0.0) {
                add("-ss")
                add("%.3f".format(java.util.Locale.US, seekSeconds))
            }
            add("-i")
            add(path.toString())
            add("-frames:v")
            add("1")
            add("-vf")
            add(scaleFilter)
            add("-f")
            add("image2pipe")
            add("-vcodec")
            add("png")
            add("pipe:1")
        }
        val bytes = runProcessCapturingStdout(command, FRAME_TIMEOUT_MILLIS, MAX_FRAME_PNG_BYTES) ?: return null
        return decodePngBytes(bytes)
    }

    private fun decodePngBytes(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val skiaImage = SkiaImage.makeFromEncoded(bytes)
            try {
                skiaImage.toComposeImageBitmap()
            } finally {
                skiaImage.close()
            }
        }.getOrNull()
    }

    private fun buildScaleFilter(maxDimension: Int): String {
        val safeMax = maxDimension.coerceIn(128, 1280)
        return "scale=w='min($safeMax,iw)':h='min($safeMax,ih)':force_original_aspect_ratio=decrease"
    }

    private fun computeSeekSeconds(positionRatio: Float, durationMillis: Long?): Double {
        if (durationMillis != null && durationMillis > 1_000L) {
            return (durationMillis / 1000.0) * positionRatio.toDouble()
        }
        return when {
            positionRatio <= 0.15f -> 1.0
            positionRatio <= 0.45f -> 5.0
            else -> 10.0
        }
    }

    internal companion object {
        private const val MAX_METADATA_CACHE_ENTRIES = 256
        private const val MAX_FRAME_CACHE_ENTRIES = 16
        private const val MAX_FRAME_CACHE_BYTES = 16L * 1024 * 1024
        private const val PROBE_TIMEOUT_MILLIS = 6_000L
        private const val FRAME_TIMEOUT_MILLIS = 8_000L
        private const val MAX_PROBE_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_FRAME_PNG_BYTES = 16 * 1024 * 1024

        private val ProbeJson = Json { ignoreUnknownKeys = true }

        /** 共享守护线程看门狗，用于在阻塞读标准输出期间强制中止超时子进程。 */
        private val processWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "onyx-media-process-watchdog").apply { isDaemon = true }
        }

        /** 共享守护线程池，负责消费媒体子进程标准输出并与可取消协程解耦。 */
        private val processReaderExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "onyx-media-process-reader").apply { isDaemon = true }
        }

        /**
         * 执行外部进程并在严格超时看门狗、协程即时取消销毁与最大字节上限保护下捕获标准输出。
         *
         * @param command 完整进程参数列表（直调系统进程，不经过 shell）。
         * @param timeoutMillis 最大允许执行时长（毫秒）。
         * @param maxOutputBytes 允许读取的标准输出最大字节数。
         * @return 进程正常退出时的输出字节；超时、超限或异常退出返回 `null`。
         */
        internal suspend fun runProcessCapturingStdout(
            command: List<String>,
            timeoutMillis: Long,
            maxOutputBytes: Int,
        ): ByteArray? {
            currentCoroutineContext().ensureActive()
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val timedOut = AtomicBoolean(false)
            val cancelled = AtomicBoolean(false)
            val overflowed = AtomicBoolean(false)

            return suspendCancellableCoroutine { cont ->
                val timeoutFuture = processWatchdog.schedule(
                    {
                        if (timedOut.compareAndSet(false, true)) {
                            process.destroyForcibly()
                            runCatching { process.inputStream.close() }
                        }
                    },
                    timeoutMillis.coerceAtLeast(50L),
                    TimeUnit.MILLISECONDS,
                )

                val readerFuture = processReaderExecutor.submit {
                    var resultBytes: ByteArray? = null
                    try {
                        val output = ByteArrayOutputStream(minOf(maxOutputBytes, 32 * 1024))
                        val buffer = ByteArray(8192)
                        var totalRead = 0
                        process.inputStream.use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (timedOut.get() || cancelled.get()) break
                                if (totalRead + read > maxOutputBytes) {
                                    overflowed.set(true)
                                    process.destroyForcibly()
                                    break
                                }
                                output.write(buffer, 0, read)
                                totalRead += read
                            }
                        }
                        if (!timedOut.get() && !cancelled.get() && !overflowed.get()) {
                            val finished = process.waitFor(timeoutMillis.coerceAtMost(2_000L), TimeUnit.MILLISECONDS)
                            if (finished && process.exitValue() == 0) {
                                resultBytes = output.toByteArray()
                            }
                        }
                    } catch (_: Exception) {
                        resultBytes = null
                    } finally {
                        timeoutFuture.cancel(false)
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                        if (cont.isActive) {
                            cont.resume(resultBytes)
                        }
                    }
                }

                // 协程取消瞬间立即强制销毁子进程并关闭管道，毫秒级打断阻塞中的 input.read()
                cont.invokeOnCancellation {
                    cancelled.set(true)
                    timeoutFuture.cancel(false)
                    process.destroyForcibly()
                    runCatching { process.inputStream.close() }
                    readerFuture.cancel(true)
                }
            }
        }

        /** 判断位置是否为远程虚拟协议或压缩包路径（应跳过整包下载探测）。 */
        internal fun isRemoteOrArchiveLocation(location: String): Boolean {
            return ArchiveService.isArchiveLocation(location) || location.contains("://")
        }

        /** 检测宿主机 PATH 中指定可执行文件是否可用。 */
        internal fun resolveExecutable(name: String): String? {
            val candidates = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("$name.exe", name)
            } else {
                listOf(name)
            }
            for (candidate in candidates) {
                val ok = runCatching {
                    val process = ProcessBuilder(candidate, "-version")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    try {
                        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }.getOrDefault(false)
                if (ok) return candidate
            }
            return null
        }

        /**
         * 解析 `ffprobe -print_format json -show_format -show_streams` 输出为结构化媒体元数据。
         *
         * @param jsonText ffprobe 标准输出 JSON。
         * @param kind 目标媒体大类。
         * @return 解析后的媒体元数据；JSON 异常时返回 `null`。
         */
        internal fun parseFfprobeJson(jsonText: String, kind: MediaKind): MediaMetadataInfo? {
            val root = runCatching { ProbeJson.parseToJsonElement(jsonText).jsonObject }.getOrNull()
                ?: return null
            val formatObj = root["format"] as? JsonObject
            val streams = (root["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

            var videoStream: JsonObject? = null
            var audioStream: JsonObject? = null
            var hasEmbeddedCover = false
            var subtitleCount = 0

            for (stream in streams) {
                val codecType = stream.stringValue("codec_type")?.lowercase()
                val disposition = stream["disposition"] as? JsonObject
                val isAttachedPic = (disposition?.get("attached_pic") as? JsonPrimitive)?.intOrNull == 1
                when (codecType) {
                    "video" -> {
                        if (isAttachedPic) {
                            hasEmbeddedCover = true
                        } else if (videoStream == null) {
                            videoStream = stream
                        }
                    }

                    "audio" -> if (audioStream == null) audioStream = stream
                    "subtitle" -> subtitleCount += 1
                }
            }

            val formatTags = (formatObj?.get("tags") as? JsonObject).orEmptyTags()
            val audioTags = (audioStream?.get("tags") as? JsonObject).orEmptyTags()
            val mergedTags = audioTags + formatTags

            val durationSeconds = formatObj.doubleValue("duration")
                ?: videoStream.doubleValue("duration")
                ?: audioStream.doubleValue("duration")
            val durationMillis = durationSeconds?.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToLong() }
            val bitrateBps = formatObj.longValue("bit_rate")
                ?: audioStream.longValue("bit_rate")
                ?: videoStream.longValue("bit_rate")

            return MediaMetadataInfo(
                kind = kind,
                durationMillis = durationMillis,
                width = videoStream.intValue("width")?.takeIf { it > 0 },
                height = videoStream.intValue("height")?.takeIf { it > 0 },
                frameRate = parseFrameRate(videoStream.stringValue("avg_frame_rate"))
                    ?: parseFrameRate(videoStream.stringValue("r_frame_rate")),
                videoCodec = videoStream.stringValue("codec_name")?.uppercase(),
                audioCodec = audioStream.stringValue("codec_name")?.uppercase(),
                sampleRateHz = audioStream.intValue("sample_rate")?.takeIf { it > 0 },
                channels = audioStream.intValue("channels")?.takeIf { it > 0 },
                bitrateBps = bitrateBps?.takeIf { it > 0L },
                subtitleTrackCount = subtitleCount,
                title = mergedTags["title"]?.takeIf { it.isNotBlank() },
                artist = (mergedTags["artist"] ?: mergedTags["album_artist"])?.takeIf { it.isNotBlank() },
                album = mergedTags["album"]?.takeIf { it.isNotBlank() },
                track = mergedTags["track"]?.takeIf { it.isNotBlank() },
                hasEmbeddedCover = hasEmbeddedCover,
            )
        }

        private fun JsonObject?.orEmptyTags(): Map<String, String> {
            if (this == null) return emptyMap()
            return entries.mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull?.trim()
                if (text.isNullOrEmpty()) null else key.lowercase() to text
            }.toMap()
        }

        private fun JsonObject?.stringValue(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject?.intValue(key: String): Int? =
            (this?.get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

        private fun JsonObject?.longValue(key: String): Long? =
            (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

        private fun JsonObject?.doubleValue(key: String): Double? =
            (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

        internal fun parseFrameRate(raw: String?): Double? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('/')
            val fps = if (parts.size == 2) {
                val num = parts[0].toDoubleOrNull() ?: return null
                val den = parts[1].toDoubleOrNull() ?: return null
                if (den <= 0.0) return null else num / den
            } else {
                raw.toDoubleOrNull() ?: return null
            }
            return fps.takeIf { it in 1.0..240.0 }
        }
    }
}
