package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

/**
 * 基于 `ffmpeg` PCM 流管道 + JDK `javax.sound.sampled.SourceDataLine` 的轻量音频试听器。
 *
 * - 启动时在 `Dispatchers.IO` 异步探测 `ffmpeg`，绝对不阻塞 UI 主线程；
 * - 支持全格式音频（MP3/FLAC/M4A/OGG/OPUS/WAV/AAC/WMA 等），无 `ffmpeg` 时对 WAV/AIFF/AU 自动回退原生试听；
 * - 流式读取采用跨块 PCM 帧余数保留（carryover），杜绝非 4 字节对齐读取导致的爆音或左右声道翻转；
 * - 使用单调递增会话代次 `sessionId` 与原子状态更新 `StateFlow.update`，彻底消除并发竞态与进程/声卡泄漏。
 */
internal class JvmAudioPreviewPlayer : AudioPreviewPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackLock = Any()
    private val currentSessionId = AtomicLong(0L)
    private val ffmpegProbed = AtomicBoolean(false)
    private val cachedFfmpegCommand = AtomicReference<String?>(null)

    @Volatile
    private var volumeValue: Float = DEFAULT_VOLUME

    private var playbackJob: Job? = null
    private var activeProcess: Process? = null
    private var activeLine: SourceDataLine? = null

    private val mutableState = MutableStateFlow(AudioPlaybackSnapshot(volume = DEFAULT_VOLUME))
    override val state: StateFlow<AudioPlaybackSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch {
            val resolved = JvmMediaPreviewService.resolveExecutable("ffmpeg")
            cachedFfmpegCommand.set(resolved)
            ffmpegProbed.set(true)
            mutableState.update { current ->
                current.copy(ffmpegAvailable = resolved != null)
            }
        }
    }

    override fun canPlay(entry: VFile): Boolean {
        if (entry.kind != VFileKind.FILE) return false
        if (JvmMediaPreviewService.isRemoteOrArchiveLocation(entry.location)) return false
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        if (ext in NATIVE_PCM_EXTENSIONS) return true
        // 后台尚未探测完成前允许点击尝试（后台线程内再实时确认），绝不在 UI 线程同步 fork 进程
        if (!ffmpegProbed.get()) return true
        return cachedFfmpegCommand.get() != null
    }

    override fun togglePlayPause(entry: VFile, durationMillis: Long?) {
        val current = mutableState.value
        if (current.location == entry.location && (current.isPlaying || current.isLoading)) {
            pauseCurrent()
            return
        }
        val startPosition = if (current.location == entry.location) {
            current.positionMillis.coerceAtLeast(0L)
        } else {
            0L
        }
        startPlayback(entry, startPosition, durationMillis ?: current.durationMillis)
    }

    override fun seekTo(entry: VFile, positionMillis: Long, durationMillis: Long?) {
        val effectiveDuration = (durationMillis ?: mutableState.value.durationMillis).coerceAtLeast(0L)
        val clampedPosition = if (effectiveDuration > 0L) {
            positionMillis.coerceIn(0L, effectiveDuration)
        } else {
            positionMillis.coerceAtLeast(0L)
        }
        val current = mutableState.value
        val shouldResume = current.location == entry.location && (current.isPlaying || current.isLoading)
        if (shouldResume) {
            startPlayback(entry, clampedPosition, effectiveDuration)
        } else {
            mutableState.update { snapshot ->
                snapshot.copy(
                    location = entry.location,
                    positionMillis = clampedPosition,
                    durationMillis = effectiveDuration,
                    errorKind = null,
                    errorDetail = null,
                )
            }
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        volumeValue = clamped
        mutableState.update { snapshot -> snapshot.copy(volume = clamped) }
    }

    override fun stop() {
        currentSessionId.incrementAndGet()
        cancelRunningPlayback()
        mutableState.update { snapshot ->
            snapshot.copy(
                location = null,
                isPlaying = false,
                isLoading = false,
                positionMillis = 0L,
                durationMillis = 0L,
                volume = volumeValue,
                errorKind = null,
                errorDetail = null,
                canPlay = true,
            )
        }
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }

    private fun pauseCurrent() {
        currentSessionId.incrementAndGet()
        cancelRunningPlayback()
        mutableState.update { snapshot ->
            snapshot.copy(
                isPlaying = false,
                isLoading = false,
            )
        }
    }

    private fun cancelRunningPlayback() {
        synchronized(playbackLock) {
            playbackJob?.cancel()
            playbackJob = null
            activeProcess?.let { process ->
                if (process.isAlive) process.destroyForcibly()
            }
            activeProcess = null
            activeLine?.let { line ->
                runCatching { line.stop() }
                runCatching { line.flush() }
                runCatching { line.close() }
            }
            activeLine = null
        }
    }

    private fun resolveFfmpegOnIoThread(): String? {
        if (ffmpegProbed.get()) return cachedFfmpegCommand.get()
        val resolved = JvmMediaPreviewService.resolveExecutable("ffmpeg")
        cachedFfmpegCommand.set(resolved)
        ffmpegProbed.set(true)
        mutableState.update { snapshot -> snapshot.copy(ffmpegAvailable = resolved != null) }
        return resolved
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startPlayback(entry: VFile, startPositionMillis: Long, durationMillis: Long) {
        val sessionId = currentSessionId.incrementAndGet()
        cancelRunningPlayback()
        val safeStart = if (durationMillis > 0L && startPositionMillis >= durationMillis - 200L) {
            0L
        } else {
            startPositionMillis.coerceAtLeast(0L)
        }

        mutableState.update { snapshot ->
            snapshot.copy(
                location = entry.location,
                isPlaying = false,
                isLoading = true,
                positionMillis = safeStart,
                durationMillis = durationMillis.coerceAtLeast(0L),
                volume = volumeValue,
                errorKind = null,
                errorDetail = null,
                canPlay = true,
            )
        }

        synchronized(playbackLock) {
            if (sessionId != currentSessionId.get()) return
            playbackJob = scope.launch {
                runPlaybackSession(
                    sessionId = sessionId,
                    entry = entry,
                    safeStartMillis = safeStart,
                    durationMillis = durationMillis.coerceAtLeast(0L),
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun kotlinx.coroutines.CoroutineScope.runPlaybackSession(
        sessionId: Long,
        entry: VFile,
        safeStartMillis: Long,
        durationMillis: Long,
    ) {
        var process: Process? = null
        var line: SourceDataLine? = null
        try {
            if (sessionId != currentSessionId.get() || !isActive) return
            val path = runCatching { Path.of(entry.location) }.getOrNull()
            if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
                updateStateForSession(sessionId) { snapshot ->
                    snapshot.copy(
                        isPlaying = false,
                        isLoading = false,
                        errorKind = AudioPlaybackError.FILE_NOT_FOUND,
                        errorDetail = null,
                    )
                }
                return
            }
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            val ffmpeg = resolveFfmpegOnIoThread()
            if (ffmpeg == null && ext !in NATIVE_PCM_EXTENSIONS) {
                updateStateForSession(sessionId) { snapshot ->
                    snapshot.copy(
                        isPlaying = false,
                        isLoading = false,
                        canPlay = false,
                        errorKind = AudioPlaybackError.UNSUPPORTED_FORMAT,
                        errorDetail = null,
                    )
                }
                return
            }

            val (pcmFormat, pcmStream) = if (ffmpeg != null) {
                val createdProcess = spawnFfmpegPcmProcess(ffmpeg, path, safeStartMillis)
                if (!registerProcessForSession(sessionId, createdProcess)) {
                    createdProcess.destroyForcibly()
                    return
                }
                process = createdProcess
                STANDARD_PCM_FORMAT to createdProcess.inputStream
            } else {
                openNativePcmStream(path.toFile(), safeStartMillis)
            }

            pcmStream.use { input ->
                val dataLineInfo = DataLine.Info(SourceDataLine::class.java, pcmFormat)
                val openedLine = (AudioSystem.getLine(dataLineInfo) as SourceDataLine).apply {
                    open(pcmFormat, LINE_BUFFER_BYTES)
                    start()
                }
                if (!registerLineForSession(sessionId, openedLine)) {
                    runCatching { openedLine.stop() }
                    runCatching { openedLine.close() }
                    return
                }
                line = openedLine

                updateStateForSession(sessionId) { snapshot ->
                    snapshot.copy(
                        isPlaying = true,
                        isLoading = false,
                        errorKind = null,
                        errorDetail = null,
                    )
                }

                streamAlignedPcmToLine(
                    sessionId = sessionId,
                    input = input,
                    line = openedLine,
                    frameSize = pcmFormat.frameSize.coerceAtLeast(2),
                    bytesPerSecond = (pcmFormat.sampleRate * pcmFormat.frameSize).toDouble().coerceAtLeast(1.0),
                    startPositionMillis = safeStartMillis,
                    durationMillis = durationMillis,
                )

                if (sessionId == currentSessionId.get() && isActive) {
                    runCatching { openedLine.drain() }
                    updateStateForSession(sessionId) { snapshot ->
                        snapshot.copy(
                            isPlaying = false,
                            isLoading = false,
                            positionMillis = 0L,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionId == currentSessionId.get() && isActive) {
                updateStateForSession(sessionId) { snapshot ->
                    snapshot.copy(
                        isPlaying = false,
                        isLoading = false,
                        errorKind = AudioPlaybackError.PLAYBACK_FAILED,
                        errorDetail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName,
                    )
                }
            }
        } finally {
            process?.let { proc ->
                if (proc.isAlive) proc.destroyForcibly()
            }
            line?.let { audioLine ->
                runCatching { audioLine.stop() }
                runCatching { audioLine.close() }
            }
            synchronized(playbackLock) {
                if (activeProcess === process) activeProcess = null
                if (activeLine === line) activeLine = null
            }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.streamAlignedPcmToLine(
        sessionId: Long,
        input: InputStream,
        line: SourceDataLine,
        frameSize: Int,
        bytesPerSecond: Double,
        startPositionMillis: Long,
        durationMillis: Long,
    ) {
        val aligner = PcmFrameAligner(frameSize = frameSize, chunkCapacity = STREAM_CHUNK_BYTES)
        val rawBuffer = ByteArray(STREAM_CHUNK_BYTES)
        var streamedBytes = 0L
        var lastProgressEmit = 0L

        while (isActive && sessionId == currentSessionId.get()) {
            val read = input.read(rawBuffer)
            if (read < 0) break
            if (read == 0) continue
            val alignedLength = aligner.pushAndAlign(rawBuffer, read)
            if (alignedLength <= 0) continue
            val output = aligner.alignedBuffer
            applyVolumeScale16BitLe(output, alignedLength, volumeValue)
            line.write(output, 0, alignedLength)
            streamedBytes += alignedLength
            val now = System.currentTimeMillis()
            if (now - lastProgressEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                lastProgressEmit = now
                val currentPos = startPositionMillis + ((streamedBytes / bytesPerSecond) * 1000.0).toLong()
                val clampedPos = if (durationMillis > 0L) {
                    currentPos.coerceAtMost(durationMillis)
                } else {
                    currentPos
                }
                updateStateForSession(sessionId) { snapshot ->
                    snapshot.copy(positionMillis = clampedPos)
                }
            }
        }
    }

    private fun registerProcessForSession(sessionId: Long, process: Process): Boolean {
        synchronized(playbackLock) {
            if (sessionId != currentSessionId.get()) return false
            activeProcess = process
            return true
        }
    }

    private fun registerLineForSession(sessionId: Long, line: SourceDataLine): Boolean {
        synchronized(playbackLock) {
            if (sessionId != currentSessionId.get()) return false
            activeLine = line
            return true
        }
    }

    private fun updateStateForSession(
        sessionId: Long,
        transform: (AudioPlaybackSnapshot) -> AudioPlaybackSnapshot,
    ) {
        if (sessionId != currentSessionId.get()) return
        mutableState.update { snapshot ->
            if (sessionId != currentSessionId.get()) snapshot else transform(snapshot)
        }
    }

    private fun spawnFfmpegPcmProcess(ffmpeg: String, path: Path, startPositionMillis: Long): Process {
        val command = buildList {
            add(ffmpeg)
            add("-v")
            add("error")
            if (startPositionMillis > 0L) {
                add("-ss")
                add("%.3f".format(Locale.US, startPositionMillis / 1000.0))
            }
            add("-i")
            add(path.toString())
            add("-vn")
            add("-f")
            add("s16le")
            add("-acodec")
            add("pcm_s16le")
            add("-ar")
            add(STANDARD_SAMPLE_RATE.toInt().toString())
            add("-ac")
            add(STANDARD_CHANNELS.toString())
            add("pipe:1")
        }
        return ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun openNativePcmStream(file: File, startPositionMillis: Long): Pair<AudioFormat, InputStream> {
        val rawStream = AudioSystem.getAudioInputStream(file)
        var convertedStream: InputStream? = null
        try {
            val sourceFormat = rawStream.format
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.sampleRate.takeIf { it > 0f } ?: STANDARD_SAMPLE_RATE,
                16,
                sourceFormat.channels.coerceIn(1, 2),
                sourceFormat.channels.coerceIn(1, 2) * 2,
                sourceFormat.sampleRate.takeIf { it > 0f } ?: STANDARD_SAMPLE_RATE,
                false,
            )
            val pcmStream = AudioSystem.getAudioInputStream(targetFormat, rawStream)
            convertedStream = pcmStream
            if (startPositionMillis > 0L) {
                val bytesToSkip = ((startPositionMillis / 1000.0) * targetFormat.sampleRate * targetFormat.frameSize).toLong()
                val alignedSkip = bytesToSkip - (bytesToSkip % targetFormat.frameSize)
                if (alignedSkip > 0L) {
                    var remaining = alignedSkip
                    while (remaining > 0L) {
                        val skipped = pcmStream.skip(remaining)
                        if (skipped <= 0L) break
                        remaining -= skipped
                    }
                }
            }
            return targetFormat to pcmStream
        } catch (failure: Throwable) {
            runCatching { convertedStream?.close() }
            runCatching { rawStream.close() }
            throw failure
        }
    }

    /**
     * 跨块 PCM 帧对齐缓冲器：保留每次管道读取末尾不足 1 帧（如 4 字节）的余数字节到下次拼接，
     * 确保绝不丢弃任何采样字节造成爆音或声道互换。
     */
    internal class PcmFrameAligner(
        private val frameSize: Int,
        chunkCapacity: Int = STREAM_CHUNK_BYTES,
    ) {
        private val safeFrameSize = frameSize.coerceAtLeast(1)
        private val carry = ByteArray(safeFrameSize)
        private var carryCount = 0

        /** 每次调用 [pushAndAlign] 后存放已对齐帧数据的连续缓冲区。 */
        var alignedBuffer: ByteArray = ByteArray(chunkCapacity + safeFrameSize)
            private set

        /**
         * 将新读取的 [input] 前 [length] 字节与上次余数拼接，返回本次可安全写入声卡的整数帧字节数。
         */
        fun pushAndAlign(input: ByteArray, length: Int): Int {
            val total = carryCount + length
            if (alignedBuffer.size < total) {
                alignedBuffer = ByteArray(total)
            }
            if (carryCount > 0) {
                System.arraycopy(carry, 0, alignedBuffer, 0, carryCount)
            }
            System.arraycopy(input, 0, alignedBuffer, carryCount, length)
            val alignedLength = total - (total % safeFrameSize)
            val nextCarry = total - alignedLength
            if (nextCarry > 0) {
                System.arraycopy(alignedBuffer, alignedLength, carry, 0, nextCarry)
            }
            carryCount = nextCarry
            return alignedLength
        }
    }

    internal companion object {
        private const val DEFAULT_VOLUME = 0.85f
        private const val STANDARD_SAMPLE_RATE = 44_100f
        private const val STANDARD_CHANNELS = 2
        private const val LINE_BUFFER_BYTES = 16_384
        private const val STREAM_CHUNK_BYTES = 4_096
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L

        private val NATIVE_PCM_EXTENSIONS = setOf("wav", "aiff", "aif", "au")

        private val STANDARD_PCM_FORMAT = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            STANDARD_SAMPLE_RATE,
            16,
            STANDARD_CHANNELS,
            STANDARD_CHANNELS * 2,
            STANDARD_SAMPLE_RATE,
            false,
        )

        /** 对 16-bit 小端有符号 PCM 字节缓冲区应用线性音量缩放。 */
        internal fun applyVolumeScale16BitLe(buffer: ByteArray, length: Int, volume: Float) {
            val gain = volume.coerceIn(0f, 1f)
            if (gain >= 0.999f) return
            if (gain <= 0.001f) {
                buffer.fill(0, 0, length)
                return
            }
            var index = 0
            while (index + 1 < length) {
                val low = buffer[index].toInt() and 0xFF
                val high = buffer[index + 1].toInt()
                val sample = (high shl 8) or low
                val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer[index] = (scaled and 0xFF).toByte()
                buffer[index + 1] = ((scaled ushr 8) and 0xFF).toByte()
                index += 2
            }
        }
    }
}
