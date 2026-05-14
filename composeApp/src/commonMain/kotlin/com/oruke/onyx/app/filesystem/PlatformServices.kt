package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile

internal data class VfsBreadcrumb(
    val label: String,
    val location: String,
)

internal interface VfsPathService {
    fun normalizeLocation(location: String): String

    fun parentLocation(location: String): String?

    fun title(location: String): String

    fun baseName(location: String): String?

    fun label(location: String): String

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

internal data class PreviewTextRequest(
    val entry: VFile,
    val maxBytes: Long,
    val maxLines: Int,
)

internal sealed interface PreviewTextResult {
    data class Text(
        val value: String,
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
}

internal interface ArchiveEntryOpenService {
    suspend fun openArchiveEntry(entry: VFile): Result<VFile>
}

internal interface ThumbnailService {
    suspend fun loadThumbnail(
        location: String,
        maxDimension: Int,
    ): ImageBitmap?

    suspend fun loadArchiveThumbnail(
        location: String,
        maxDimension: Int,
    ): ImageBitmap?

    fun clearCache()
}

internal interface ImageMetadataService {
    suspend fun readImageSize(entry: VFile): IntSize?
}
