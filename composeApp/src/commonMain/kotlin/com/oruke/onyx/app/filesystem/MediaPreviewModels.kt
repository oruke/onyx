package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 媒体文件大类（音频或视频）。 */
internal enum class MediaKind {
    AUDIO,
    VIDEO,
}

/**
 * 音视频文件的结构化媒体元数据（由 ffprobe 或 JVM 原生音频解析获得）。
 *
 * @property kind 媒体类型（音频或视频）。
 * @property durationMillis 总时长（毫秒）；未知时为 `null`。
 * @property width 视频帧宽度（像素）。
 * @property height 视频帧高度（像素）。
 * @property frameRate 视频帧率（fps）。
 * @property videoCodec 视频编码名称（如 `H264` / `HEVC` / `AV1`）。
 * @property audioCodec 音频编码名称（如 `AAC` / `FLAC` / `OPUS`）。
 * @property sampleRateHz 音频采样率（Hz）。
 * @property channels 音频声道数。
 * @property bitrateBps 总码率（bps）。
 * @property subtitleTrackCount 内嵌字幕轨数量。
 * @property title 媒体标签标题。
 * @property artist 艺术家 / 演唱者。
 * @property album 专辑名称。
 * @property track 音轨序号。
 * @property hasEmbeddedCover 是否内嵌封面图（例如 MP3/FLAC 专辑封面）。
 */
internal data class MediaMetadataInfo(
    val kind: MediaKind,
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitrateBps: Long? = null,
    val subtitleTrackCount: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: String? = null,
    val hasEmbeddedCover: Boolean = false,
)

/** 音视频元数据探测结果。 */
internal sealed interface MediaMetadataResult {
    /** 成功读取结构化媒体信息。 */
    data class Ready(val info: MediaMetadataInfo) : MediaMetadataResult

    /** 宿主机未安装 ffprobe/ffmpeg 等媒体探测工具，已优雅降级。 */
    data object ToolUnavailable : MediaMetadataResult

    /** 远程虚拟协议或归档内媒体，跳过整文件下载探测。 */
    data object RemoteSkipped : MediaMetadataResult

    /** 目标条目不存在或不支持媒体探测。 */
    data object Unavailable : MediaMetadataResult

    /** 探测执行失败并携带诊断信息。 */
    data class Failed(val reason: String) : MediaMetadataResult
}

/**
 * 音视频预览与元数据服务：负责媒体流信息读取、视频代表帧抽取与音频封面提取。
 */
internal interface MediaPreviewService {
    /** 当前宿主机是否具备媒体元数据探测能力（如 `ffprobe`）。 */
    val isProbeAvailable: Boolean

    /** 当前宿主机是否具备视频抽帧与封面提取能力（如 `ffmpeg`）。 */
    val isFrameExtractionAvailable: Boolean

    /**
     * 读取指定音视频条目的结构化媒体元数据。
     *
     * @param entry 目标虚拟文件条目。
     * @return 结构化探测结果。
     */
    suspend fun readMediaMetadata(entry: VFile): MediaMetadataResult

    /**
     * 按时长比例抽取视频关键帧作为预览位图。
     *
     * @param entry 视频文件条目。
     * @param positionRatio 抽帧位置比例（范围 `0f..1f`，例如 `0.10f`、`0.35f`、`0.65f`）。
     * @param durationMillis 已知总时长（毫秒），为空时内部按安全偏移抽取。
     * @param maxDimension 输出预览图最大像素边长。
     * @return 解码后的位图；不可用或失败时返回 `null`。
     */
    suspend fun loadVideoFrame(
        entry: VFile,
        positionRatio: Float,
        durationMillis: Long?,
        maxDimension: Int,
    ): ImageBitmap?

    /**
     * 读取音频文件内嵌专辑封面图。
     *
     * @param entry 音频文件条目。
     * @param maxDimension 输出封面图最大像素边长。
     * @return 专辑封面位图；无内嵌封面或不可用时返回 `null`。
     */
    suspend fun loadAudioCover(
        entry: VFile,
        maxDimension: Int,
    ): ImageBitmap?
}

/** 空实现媒体服务，用于无媒体能力环境或单元测试兜底。 */
internal object NoOpMediaPreviewService : MediaPreviewService {
    override val isProbeAvailable: Boolean = false
    override val isFrameExtractionAvailable: Boolean = false
    override suspend fun readMediaMetadata(entry: VFile): MediaMetadataResult = MediaMetadataResult.ToolUnavailable
    override suspend fun loadVideoFrame(
        entry: VFile,
        positionRatio: Float,
        durationMillis: Long?,
        maxDimension: Int,
    ): ImageBitmap? = null

    override suspend fun loadAudioCover(entry: VFile, maxDimension: Int): ImageBitmap? = null
}

/** 音频预览播放器的结构化错误分类，由 UI 层经三语资源渲染。 */
internal enum class AudioPlaybackError {
    /** 目标音频文件不存在或无法访问。 */
    FILE_NOT_FOUND,

    /** 当前格式需要本机安装 ffmpeg 才能内置试听。 */
    UNSUPPORTED_FORMAT,

    /** 声卡输出或音频解码过程失败。 */
    PLAYBACK_FAILED,
}

/**
 * 预览面板内置轻量音频试听器的当前播放快照。
 *
 * @property location 正在播放或暂停的音频文件位置。
 * @property isPlaying 当前是否处于播放中。
 * @property isLoading 当前是否正在启动或跳转解码流。
 * @property positionMillis 当前播放位置（毫秒）。
 * @property durationMillis 总时长（毫秒）。
 * @property volume 音量比例（`0f..1f`）。
 * @property errorKind 结构化播放错误类别；正常时为 `null`。
 * @property errorDetail 可选底层诊断摘要，用于辅助排查。
 * @property canPlay 当前宿主机是否支持播放指定音频。
 * @property ffmpegAvailable 后台异步探测得到的 ffmpeg 可用状态（未探测完成前为 `null`）。
 */
internal data class AudioPlaybackSnapshot(
    val location: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val volume: Float = 0.85f,
    val errorKind: AudioPlaybackError? = null,
    val errorDetail: String? = null,
    val canPlay: Boolean = true,
    val ffmpegAvailable: Boolean? = null,
)

/**
 * 右侧预览面板内置轻量音频试听播放器。
 */
internal interface AudioPreviewPlayer {
    /** 当前播放状态流。 */
    val state: StateFlow<AudioPlaybackSnapshot>

    /**
     * 判断指定音频条目当前是否可直接在面板内试听。
     *
     * @param entry 目标音频文件条目。
     * @return 支持面板内试听时返回 `true`。
     */
    fun canPlay(entry: VFile): Boolean

    /**
     * 切换播放或暂停指定音频条目。
     *
     * @param entry 目标音频文件条目。
     * @param durationMillis 已知总时长（毫秒）。
     */
    fun togglePlayPause(entry: VFile, durationMillis: Long?)

    /**
     * 将当前或指定音频跳转到目标时间点。
     *
     * @param entry 目标音频文件条目。
     * @param positionMillis 目标播放位置（毫秒）。
     * @param durationMillis 已知总时长（毫秒）。
     */
    fun seekTo(entry: VFile, positionMillis: Long, durationMillis: Long?)

    /**
     * 调整播放音量（`0f..1f`）。
     *
     * @param volume 目标音量比例。
     */
    fun setVolume(volume: Float)

    /** 立即停止播放并释放当前音频输出线与解码进程。 */
    fun stop()

    /** 释放播放器持有的全部后台资源。 */
    fun dispose()
}

/** 空实现音频播放器，用于测试或无音频输出环境。 */
internal object NoOpAudioPreviewPlayer : AudioPreviewPlayer {
    private val backing = MutableStateFlow(AudioPlaybackSnapshot(canPlay = false))
    override val state: StateFlow<AudioPlaybackSnapshot> = backing
    override fun canPlay(entry: VFile): Boolean = false
    override fun togglePlayPause(entry: VFile, durationMillis: Long?) = Unit
    override fun seekTo(entry: VFile, positionMillis: Long, durationMillis: Long?) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun stop() = Unit
    override fun dispose() = Unit
}
