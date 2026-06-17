package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 预览能力类型。
 */
enum class PreviewCapability {
    TEXT,
    IMAGE,
    PDF,
    AUDIO_VIDEO_METADATA,
    EXIF,
    ENCODING_SELECTION,
    CANCELLABLE_LARGE_FILE,
}

/**
 * 单个文件的预览能力报告。
 *
 * @property entry 文件条目。
 * @property capabilities 支持的预览能力。
 * @property largeFile 是否属于需要可取消加载的大文件。
 */
data class PreviewCapabilityReport(
    val entry: VFile,
    val capabilities: Set<PreviewCapability>,
    val largeFile: Boolean,
)

/**
 * 预览能力分析用例。
 */
class PreviewCapabilityUseCase {
    /**
     * 分析文件条目的预览能力。
     *
     * @param entry 待分析条目。
     * @param largeFileThresholdBytes 大文件阈值。
     * @return 预览能力报告。
     */
    fun analyze(
        entry: VFile,
        largeFileThresholdBytes: Long = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
    ): PreviewCapabilityReport {
        if (entry.kind != VFileKind.FILE) {
            return PreviewCapabilityReport(entry = entry, capabilities = emptySet(), largeFile = false)
        }
        val extension = entry.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val largeFile = (entry.sizeBytes ?: 0L) >= largeFileThresholdBytes
        val capabilities = buildSet {
            if (extension in TEXT_EXTENSIONS) {
                add(PreviewCapability.TEXT)
                add(PreviewCapability.ENCODING_SELECTION)
            }
            if (extension in IMAGE_EXTENSIONS) {
                add(PreviewCapability.IMAGE)
                add(PreviewCapability.EXIF)
            }
            if (extension == "pdf") {
                add(PreviewCapability.PDF)
            }
            if (extension in AUDIO_VIDEO_EXTENSIONS) {
                add(PreviewCapability.AUDIO_VIDEO_METADATA)
            }
            if (largeFile) {
                add(PreviewCapability.CANCELLABLE_LARGE_FILE)
            }
        }
        return PreviewCapabilityReport(
            entry = entry,
            capabilities = capabilities,
            largeFile = largeFile,
        )
    }

    private companion object {
        private const val DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 50L * 1024L * 1024L
        private val TEXT_EXTENSIONS = setOf("txt", "md", "json", "xml", "yaml", "yml", "csv", "log", "kt", "java")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff")
        private val AUDIO_VIDEO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "mp4", "mkv", "mov", "avi", "webm")
    }
}
