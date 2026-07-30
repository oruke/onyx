package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream

/**
 * ZIP 压缩包创建服务，负责将统一 VFS 条目打包并写回目标 provider。
 *
 * @param fileRepository 用于递归读取目录子项的统一文件仓库。
 * @param contentServices 用于读取源文件和写入目标归档的内容服务集合。
 */
class ZipArchiveCreationService(
    private val fileRepository: FileRepository,
    private val contentServices: List<RoutableVfsContentService>,
) {
    /**
     * 创建 ZIP 压缩包并写入指定目录。
     *
     * 压缩过程先在本地临时文件中生成完整 ZIP，再通过目标 provider 的流式写入能力上传，
     * 因此源文件和目标目录可以分别来自本地、SMB、WebDAV 或 S3。
     *
     * @param request ZIP 创建请求。
     * @param progressSink 压缩文件完成时的进度接收器。
     * @return 创建成功时返回实际归档名称与条目数量；失败时返回明确异常。
     */
    suspend fun create(
        request: ZipArchiveCreationRequest,
        progressSink: ZipArchiveCreationProgressSink = ZipArchiveCreationProgressSink.NoOp,
    ): Result<ZipArchiveCreationResult> = withContext(Dispatchers.IO) {
        try {
            val archiveName = validateArchiveName(request.archiveName).fileNameOrThrow()
            require(request.entries.isNotEmpty()) { "至少需要选择一个文件或目录" }
            require(request.targetDirectoryLocation.isNotBlank()) { "压缩包目标目录不能为空" }
            val targetContentService = contentServices.firstOrNull { service ->
                service.supports(request.targetDirectoryLocation)
            } ?: error("当前目录不支持写入压缩包: ${request.targetDirectoryLocation}")
            val temporaryArchive = Files.createTempFile(TEMPORARY_ARCHIVE_PREFIX, ZIP_EXTENSION)
            try {
                val entryCount = ZipOutputStream(Files.newOutputStream(temporaryArchive)).use { output ->
                    ZipArchiveEntryWriter(
                        fileRepository = fileRepository,
                        contentServices = contentServices,
                        output = output,
                        progressSink = progressSink,
                    ).writeEntries(request.entries)
                }
                val targetEntry = targetContentService.writeFile(
                    parentLocation = request.targetDirectoryLocation,
                    name = archiveName,
                    chunks = temporaryArchive.readArchiveChunks(),
                    conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
                ).getOrThrow()
                Result.success(
                    ZipArchiveCreationResult(
                        archiveName = targetEntry?.name ?: archiveName,
                        entryCount = entryCount,
                    )
                )
            } finally {
                Files.deleteIfExists(temporaryArchive)
            }
        } catch (failure: CancellationException) {
            // 取消必须沿协程链继续传播，不能被包装成普通压缩失败。
            throw failure
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    companion object {
        /**
         * 校验并规范化用户输入的 ZIP 文件名。
         *
         * @param draft 用户输入的归档名称。
         * @return 空名称、非法名称或带 `.zip` 扩展名的有效文件名。
         */
        fun validateArchiveName(draft: String): ZipArchiveNameValidation {
            val normalized = draft.trim()
            if (normalized.isBlank()) return ZipArchiveNameValidation.Empty
            if (
                normalized == "." ||
                normalized == ".." ||
                normalized.any { character ->
                    character in INVALID_ARCHIVE_FILE_NAME_CHARACTERS || character.code < MIN_PRINTABLE_CODE
                } ||
                normalized.endsWith('.') ||
                normalized.endsWith(' ')
            ) {
                return ZipArchiveNameValidation.Invalid
            }
            val fileName = if (normalized.endsWith(ZIP_EXTENSION, ignoreCase = true)) {
                normalized
            } else {
                "$normalized$ZIP_EXTENSION"
            }
            return if (fileName.dropLast(ZIP_EXTENSION.length).isBlank()) {
                ZipArchiveNameValidation.Invalid
            } else {
                ZipArchiveNameValidation.Valid(fileName)
            }
        }

        /** ZIP 文件扩展名。 */
        private const val ZIP_EXTENSION = ".zip"

        /** Windows 与跨协议位置均不允许的文件名字符。 */
        private const val INVALID_ARCHIVE_FILE_NAME_CHARACTERS = "\\/:*?\"<>|"

        /** 文件名中允许出现的最小可打印字符编码。 */
        private const val MIN_PRINTABLE_CODE = 32

        /** 临时 ZIP 文件名称前缀。 */
        private const val TEMPORARY_ARCHIVE_PREFIX = "onyx-archive-"
    }
}

/**
 * ZIP 压缩包创建请求。
 *
 * @property entries 待打包的统一 VFS 文件或目录条目。
 * @property targetDirectoryLocation 新归档写入的父目录位置。
 * @property archiveName 用户输入的归档名称。
 */
data class ZipArchiveCreationRequest(
    /** 待打包的统一 VFS 文件或目录条目。 */
    val entries: List<VFile>,
    /** 新归档写入的父目录位置。 */
    val targetDirectoryLocation: String,
    /** 用户输入的归档名称。 */
    val archiveName: String,
)

/**
 * ZIP 压缩包创建结果。
 *
 * @property archiveName 目标 provider 最终写入的归档名称。
 * @property entryCount 写入 ZIP 的文件与目录条目总数。
 */
data class ZipArchiveCreationResult(
    /** 目标 provider 最终写入的归档名称。 */
    val archiveName: String,
    /** 写入 ZIP 的文件与目录条目总数。 */
    val entryCount: Int,
)

/**
 * ZIP 归档名称校验结果。
 */
sealed interface ZipArchiveNameValidation {
    /** 用户尚未输入归档名称。 */
    data object Empty : ZipArchiveNameValidation

    /** 用户输入包含不适合作为跨平台归档文件名的字符。 */
    data object Invalid : ZipArchiveNameValidation

    /**
     * 归档名称有效。
     *
     * @property fileName 已补齐 `.zip` 扩展名的文件名。
     */
    data class Valid(
        /** 已补齐 `.zip` 扩展名的文件名。 */
        val fileName: String,
    ) : ZipArchiveNameValidation
}

/**
 * 将归档名称校验结果转换为有效 ZIP 文件名。
 *
 * @return 有效 ZIP 文件名；校验失败时抛出明确异常。
 */
private fun ZipArchiveNameValidation.fileNameOrThrow(): String {
    return when (this) {
        ZipArchiveNameValidation.Empty -> error("压缩包名称不能为空")
        ZipArchiveNameValidation.Invalid -> error("压缩包名称包含非法字符")
        is ZipArchiveNameValidation.Valid -> fileName
    }
}

/**
 * 以固定大小分块读取本地临时 ZIP 文件。
 *
 * @return 可交给目标 provider 流式写入的字节块。
 */
private fun Path.readArchiveChunks(): Flow<ByteArray> {
    return flow {
        Files.newInputStream(this@readArchiveChunks).use { input ->
            val buffer = ByteArray(ARCHIVE_CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                emit(buffer.copyOf(read))
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** 临时 ZIP 文件读取块大小。 */
private const val ARCHIVE_CHUNK_SIZE = 64 * 1024
