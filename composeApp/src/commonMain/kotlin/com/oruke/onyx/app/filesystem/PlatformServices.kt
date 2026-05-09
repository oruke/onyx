package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile

data class VfsBreadcrumb(
    val label: String,
    val location: String,
)

interface VfsPathService {
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

    fun buildBreadcrumbs(location: String): List<VfsBreadcrumb>
}

interface TerminalLauncherService {
    suspend fun openTerminal(location: String): Result<Unit>
}

data class PreviewTextRequest(
    val entry: VFile,
    val maxBytes: Long,
    val maxLines: Int,
)

sealed interface PreviewTextResult {
    data class Text(
        val value: String,
    ) : PreviewTextResult

    data object TooLarge : PreviewTextResult

    data object Unavailable : PreviewTextResult
}

interface PreviewService {
    suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult
}

interface FileTypeService {
    fun isImageFileName(fileName: String): Boolean

    fun isArchiveFileName(fileName: String): Boolean

    fun isTextPreviewFileName(fileName: String): Boolean
}

interface ArchiveEntryOpenService {
    suspend fun openArchiveEntry(entry: VFile): Result<VFile>
}

interface ThumbnailService {
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

interface ImageMetadataService {
    suspend fun readImageSize(entry: VFile): IntSize?
}
