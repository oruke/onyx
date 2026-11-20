package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile

/** 导航层级及其展示元数据；真实位置始终独立于可本地化的名称。 */
internal data class VfsBreadcrumb(
    /** 普通路径段名称或应用位置标识。 */
    val label: String,
    /** 点击和地址编辑使用的真实 VFS 位置。 */
    val location: String,
    /** 已发现设备的卷名信息，缺少快照时仍可按普通路径导航。 */
    val storageLabel: com.oruke.onyx.core.model.StorageVolumeLabel? = null,
)

internal interface VfsPathService {
    fun normalizeLocation(location: String): String

    fun parentLocation(location: String): String?

    /**
     * 获取界面导航上级，允许设备根目录返回设备概览，不改变文件系统父子关系。
     * @param location 当前访问位置。
     * @return 可导航的父位置；顶层概览为空。
     */
    fun navigationParent(location: String): String? = parentLocation(location)

    fun title(location: String): String

    fun baseName(location: String): String?

    fun label(location: String): String

    /**
     * 计算后代位置相对祖先位置的目录路径段序列。
     *
     * @param ancestor 祖先目录位置。
     * @param descendant 相同或后代目录位置。
     * @return 相对路径段（相同位置返回空列表）；非后代关系时返回 `null`。
     */
    fun relativePathSegments(
        ancestor: String,
        descendant: String,
    ): List<String>? = null

    /**
     * 在父目录位置下按相对目录段解析规范化子目录位置。
     *
     * @param parentLocation 父目录位置。
     * @param childSegments 子目录名称序列。
     * @return 解析后的目标目录位置。
     */
    fun resolveChildDirectory(
        parentLocation: String,
        childSegments: List<String>,
    ): String = normalizeLocation(parentLocation)

    fun directChildName(
        ancestor: String,
        descendant: String,
    ): String?

    fun isDirectParent(
        parent: String,
        child: String,
    ): Boolean

    fun isSameOrChildOf(
        location: String,
        parentLocation: String,
    ): Boolean

    fun resolveTransferOperation(
        sourceLocation: String,
        targetLocation: String,
    ): FileTransferOperation

    fun isLocationAvailable(location: String): Boolean = true

    fun buildBreadcrumbs(location: String): List<VfsBreadcrumb>
}

internal interface TerminalLauncherService {
    suspend fun openTerminal(location: String): Result<Unit>
}

/** 文本预览解码所使用的字符编码选项。 */
internal enum class PreviewTextEncoding(
    /** 面向用户展示的编码名称。 */
    val displayLabel: String,
) {
    AUTO("Auto"),
    UTF_8("UTF-8"),
    UTF_16("UTF-16"),
    GBK("GBK"),
    SHIFT_JIS("Shift-JIS"),
}

internal data class PreviewTextRequest(
    val entry: VFile,
    val maxBytes: Long,
    val maxLines: Int,
    val encoding: PreviewTextEncoding = PreviewTextEncoding.AUTO,
)

internal sealed interface PreviewTextResult {
    data class Text(
        val value: String,
        val detectedEncoding: PreviewTextEncoding = PreviewTextEncoding.UTF_8,
        val truncated: Boolean = false,
        val lineCount: Int = 0,
        val totalBytes: Long? = null,
    ) : PreviewTextResult

    data object TooLarge : PreviewTextResult

    data object Unavailable : PreviewTextResult

    data class Failed(
        val reason: I18nMessage,
    ) : PreviewTextResult
}

internal interface PreviewService {
    suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult
}

internal data class FileHashRequest(
    val entry: VFile,
    val maxBytes: Long,
)

internal sealed interface FileHashResult {
    data class Hash(
        val algorithm: String,
        val value: String,
    ) : FileHashResult

    data object TooLarge : FileHashResult

    data object Unavailable : FileHashResult

    data class Failed(
        val reason: I18nMessage,
    ) : FileHashResult
}

internal interface FileHashService {
    suspend fun readHash(request: FileHashRequest): FileHashResult
}

internal data class ArchiveInfoRequest(
    val entry: VFile,
)

internal sealed interface ArchiveInfoResult {
    data class Info(
        val encrypted: Boolean,
        val canBrowse: Boolean,
        val canExtract: Boolean,
        val canWrite: Boolean,
        val entryCount: Int? = null,
    ) : ArchiveInfoResult

    data object Unavailable : ArchiveInfoResult

    data class Failed(
        val reason: I18nMessage,
    ) : ArchiveInfoResult
}

internal interface ArchiveInfoService {
    suspend fun readInfo(request: ArchiveInfoRequest): ArchiveInfoResult
}

internal interface FileTypeService {
    fun isImageFileName(fileName: String): Boolean

    fun isArchiveFileName(fileName: String): Boolean

    fun isTextPreviewFileName(fileName: String): Boolean

    /** 判断文件名是否为受支持的音频文件。 */
    fun isAudioFileName(fileName: String): Boolean = false

    /** 判断文件名是否为受支持的视频文件。 */
    fun isVideoFileName(fileName: String): Boolean = false
}

internal interface ArchiveEntryOpenService {
    suspend fun openArchiveEntry(entry: VFile): Result<VFile>
}

/**
 * 独立图片查看器加载单张原图时的结构化结果，用于精确展示失败原因与恢复操作。
 */
internal sealed interface ImageLoadOutcome {
    /** 图片解码成功。 */
    data class Loaded(val bitmap: ImageBitmap) : ImageLoadOutcome

    /** 文件大小超出查看器安全解码上限。 */
    data class TooLarge(
        val limitBytes: Long,
        val actualBytes: Long? = null,
    ) : ImageLoadOutcome

    /** 目标文件不存在或已移除。 */
    data object NotFound : ImageLoadOutcome

    /** 当前位置协议不支持查看器直接读取内容。 */
    data object UnsupportedLocation : ImageLoadOutcome

    /** 文件存在但解码失败或内容损坏。 */
    data class DecodeFailed(val reason: String? = null) : ImageLoadOutcome
}

internal interface ThumbnailService {
    /**
     * 同步查询内存缓存中的图片缩略图，避免画廊与预览区命中缓存时闪现占位图。
     *
     * @param location 图片位置。
     * @param maxDimension 最大边长像素。
     * @param modifiedAtEpochMillis 可选文件修改时间戳（用于同名覆盖失效）。
     * @param sizeBytes 可选文件字节数（用于同名覆盖失效）。
     * @return 已缓存的位图；未命中返回 `null`。
     */
    fun peekCachedThumbnail(
        location: String,
        maxDimension: Int,
        modifiedAtEpochMillis: Long? = null,
        sizeBytes: Long? = null,
    ): ImageBitmap? = null

    /**
     * 同步查询内存缓存中的压缩包封面缩略图。
     *
     * @param location 压缩包位置。
     * @param maxDimension 最大边长像素。
     * @param modifiedAtEpochMillis 可选文件修改时间戳。
     * @param sizeBytes 可选文件字节数。
     * @return 已缓存的位图；未命中返回 `null`。
     */
    fun peekCachedArchiveThumbnail(
        location: String,
        maxDimension: Int,
        modifiedAtEpochMillis: Long? = null,
        sizeBytes: Long? = null,
    ): ImageBitmap? = null

    suspend fun loadThumbnail(
        location: String,
        maxDimension: Int,
        modifiedAtEpochMillis: Long? = null,
        sizeBytes: Long? = null,
    ): ImageBitmap?

    suspend fun loadArchiveThumbnail(
        location: String,
        maxDimension: Int,
        modifiedAtEpochMillis: Long? = null,
        sizeBytes: Long? = null,
    ): ImageBitmap?

    /**
     * 为图片查看器加载高分辨率位图并返回结构化失败原因。
     *
     * @param entry 目标图片条目。
     * @param maxDimension 最大边长像素。
     * @return 结构化加载结果。
     */
    suspend fun loadViewerImage(
        entry: VFile,
        maxDimension: Int,
    ): ImageLoadOutcome {
        val loaded = loadThumbnail(
            location = entry.location,
            maxDimension = maxDimension,
            modifiedAtEpochMillis = entry.modifiedAtEpochMillis,
            sizeBytes = entry.sizeBytes,
        )
        return if (loaded != null) {
            ImageLoadOutcome.Loaded(loaded)
        } else {
            ImageLoadOutcome.DecodeFailed()
        }
    }

    fun clearCache()
}

internal interface ImageMetadataService {
    suspend fun readImageSize(entry: VFile): IntSize?
}
