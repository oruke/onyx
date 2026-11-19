package com.oruke.onyx.vfs.archive

import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path

/** 归档条目提取超出字节上限时用于中断流式写入的内部信号异常。 */
internal class ArchiveExtractLimitExceededException(
    /** 本次提取允许的最大字节数。 */
    val maxBytes: Long,
) : RuntimeException("Archive entry exceeds maxBytes=$maxBytes")

/** Windows 盘符绝对路径匹配正则（如 `C:/...`）。 */
private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:.*")

/**
 * 校验归档相对路径并解析为严格位于目标目录内部的安全输出文件，防止 Zip Slip 路径穿越。
 *
 * @param targetDirectory 解压目标目录。
 * @param relativePath 已剥离前缀的归档内部相对路径。
 * @return 安全落位于 [targetDirectory] 内部的目标文件；越界或非法路径返回 `null`。
 */
internal fun resolveSafeArchiveOutputFile(
    targetDirectory: File,
    relativePath: String,
): File? {
    val normalizedTargetPath: Path = targetDirectory.toPath().toAbsolutePath().normalize()
    val normalizedRelative = relativePath.replace('\\', '/').trim()
    if (
        normalizedRelative.isEmpty() ||
        normalizedRelative.startsWith("/") ||
        WINDOWS_DRIVE_PREFIX.matches(normalizedRelative)
    ) {
        return null
    }
    val rawSegments = normalizedRelative.split('/').filter { segment -> segment.isNotEmpty() }
    if (
        rawSegments.any { segment ->
            segment == ".." ||
                ':' in segment ||
                segment.any { ch -> ch.code < 0x20 }
        }
    ) {
        return null
    }
    val segments = rawSegments.filter { segment -> segment != "." }
    if (segments.isEmpty()) {
        return null
    }
    val resolvedPath = runCatching {
        normalizedTargetPath.resolve(segments.joinToString("/")).normalize()
    }.getOrNull() ?: return null
    if (!resolvedPath.startsWith(normalizedTargetPath) || resolvedPath == normalizedTargetPath) {
        return null
    }
    return resolvedPath.toFile()
}

/**
 * 校验输出文件最近已存在父目录的真实物理路径仍严格位于目标目录真实路径之内，
 * 防止通过目标目录内预先存在的符号链接向外逃逸写入。
 *
 * @param targetDirectory 解压目标目录。
 * @param outputFile 拟写入或创建的目标文件。
 * @return 物理路径安全时返回 `true`。
 */
internal fun verifyArchiveOutputParentContained(
    targetDirectory: File,
    outputFile: File,
): Boolean {
    if (!targetDirectory.exists()) return true
    val targetRealPath = runCatching { targetDirectory.toPath().toRealPath() }.getOrNull() ?: return false
    var probe: File? = outputFile.parentFile
    while (probe != null && !probe.exists()) {
        probe = probe.parentFile
    }
    val existingAncestor = probe ?: return false
    val ancestorRealPath = runCatching { existingAncestor.toPath().toRealPath() }.getOrNull() ?: return false
    return ancestorRealPath.startsWith(targetRealPath)
}

/**
 * 解压到文件的回调，将归档条目写入目标目录。
 *
 * @param archive 7-Zip 归档读取器。
 * @param targetDirectory 解压目标目录。
 * @param prefix 需要从归档条目中移除的路径前缀。
 * @param password 可选归档密码。
 * @param progressSink 解压字节进度接收器。
 */
internal class FileExtractCallback(
    private val archive: IInArchive,
    private val targetDirectory: File,
    private val prefix: String,
    private val password: String? = null,
    private val progressSink: ArchiveProgressSink = ArchiveProgressSink.NoOp,
) : IArchiveExtractCallback, ICryptoGetTextPassword {
    /** 当前条目的输出流。 */
    private var currentOutputStream: FileOutputStream? = null

    /** 当前条目的目标文件。 */
    private var currentOutputFile: File? = null

    /** 解压过程中收到的条目错误。 */
    val errors = mutableListOf<String>()

    /** 7-Zip 当前解压批次声明的总字节数。 */
    private var totalBytes: Long = 0L

    /** 最近一次已发布的完成字节数。 */
    private var completedBytes: Long = 0L

    override fun getStream(
        index: Int,
        extractAskMode: ExtractAskMode,
    ): ISequentialOutStream? {
        val itemPath = archive.itemPath(index)
            ?.takeIf { extractAskMode == ExtractAskMode.EXTRACT }
        return itemPath?.let { path -> outputStreamFor(index, path) }
    }

    /**
     * 为单个待解压条目创建输出流，目录条目只负责建立目录。
     *
     * @param index 归档条目索引。
     * @param itemPath 规范化后的归档内部路径。
     * @return 文件输出流适配器；目录返回 `null`。
     */
    private fun outputStreamFor(
        index: Int,
        itemPath: String,
    ): ISequentialOutStream? {
        val symLink = archive.getProperty(index, PropID.SYM_LINK) as? String
        if (!symLink.isNullOrBlank()) {
            errors.add("安全拦截：禁止解压符号链接条目 ($itemPath)")
            return null
        }
        val isDirectory = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
        val relativePath = if (prefix.isNotEmpty()) itemPath.removePrefix(prefix) else itemPath
        if (isDirectory && relativePath.trim('/', '\\', ' ').isEmpty()) {
            return null
        }
        val outputFile = resolveSafeArchiveOutputFile(targetDirectory, relativePath)
        if (outputFile == null || !verifyArchiveOutputParentContained(targetDirectory, outputFile)) {
            errors.add("安全拦截：归档条目路径越界 ($itemPath)")
            return null
        }
        return if (isDirectory) {
            outputFile.mkdirs()
            null
        } else {
            outputFile.parentFile?.mkdirs()
            val output = FileOutputStream(outputFile)
            currentOutputStream = output
            currentOutputFile = outputFile
            ISequentialOutStream { data ->
                output.write(data)
                data.size
            }
        }
    }

    override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        currentOutputStream?.close()
        currentOutputStream = null
        if (extractOperationResult != ExtractOperationResult.OK) {
            currentOutputFile?.let { file ->
                if (file.exists()) file.delete()
            }
            errors.add(formatExtractError(extractOperationResult))
        }
        currentOutputFile = null
    }

    /**
     * 记录当前批次总字节数并发布初始进度。
     *
     * @param total 当前批次总字节数。
     */
    override fun setTotal(total: Long) {
        totalBytes = total.coerceAtLeast(0L)
        completedBytes = 0L
        progressSink.onProgress(0L, totalBytes)
    }

    /**
     * 发布 7-Zip 已完成的绝对字节数。
     *
     * @param complete 当前已完成字节数。
     */
    override fun setCompleted(complete: Long) {
        completedBytes = complete.coerceAtLeast(0L)
        progressSink.onProgress(
            completedBytes = completedBytes,
            totalBytes = totalBytes,
        )
    }

    /**
     * 在成功解压后补齐终态进度，兼容不发送最终完成回调的 7-Zip 平台实现。
     *
     * @return 无返回值。
     */
    fun completeProgress() {
        if (totalBytes <= 0L || completedBytes >= totalBytes) return
        completedBytes = totalBytes
        progressSink.onProgress(
            completedBytes = completedBytes,
            totalBytes = totalBytes,
        )
    }

    override fun cryptoGetTextPassword(): String = password ?: ""
}

/**
 * 解压到内存的回调，支持在流式写入期间强制执行最大字节数上限以防 OOM。
 *
 * @param buffer 文件内容输出缓冲区。
 * @param password 可选归档密码。
 * @param maxBytes 允许写入的最大字节数；为 `null` 时不限制。
 */
internal class MemoryExtractCallback(
    private val buffer: ByteArrayOutputStream,
    private val password: String? = null,
    private val maxBytes: Long? = null,
) : IArchiveExtractCallback, ICryptoGetTextPassword {
    /** 解压过程中收到的条目错误。 */
    val errors = mutableListOf<String>()

    /** 是否在流式读取期间触发了最大字节数保护。 */
    var limitExceeded: Boolean = false
        private set

    /** 已写入缓冲区的累计字节数。 */
    private var writtenBytes: Long = 0L

    override fun getStream(
        index: Int,
        extractAskMode: ExtractAskMode,
    ): ISequentialOutStream? {
        if (extractAskMode != ExtractAskMode.EXTRACT) return null
        return ISequentialOutStream { data ->
            val limit = maxBytes
            if (limit != null && writtenBytes + data.size.toLong() > limit) {
                limitExceeded = true
                throw ArchiveExtractLimitExceededException(limit)
            }
            buffer.write(data)
            writtenBytes += data.size.toLong()
            data.size
        }
    }

    override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        if (extractOperationResult != ExtractOperationResult.OK) {
            errors.add(formatExtractError(extractOperationResult))
        }
    }

    override fun setTotal(total: Long) = Unit
    override fun setCompleted(complete: Long) = Unit
    override fun cryptoGetTextPassword(): String = password ?: ""
}

/**
 * 仅验证归档数据的测试回调，不写入任何内容。
 *
 * @param password 可选归档密码。
 */
internal class TestExtractCallback(
    private val password: String? = null,
) : IArchiveExtractCallback, ICryptoGetTextPassword {
    /** 验证过程中收到的条目错误。 */
    val errors = mutableListOf<String>()

    override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? = null
    override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        if (extractOperationResult != ExtractOperationResult.OK) {
            errors.add(formatExtractError(extractOperationResult))
        }
    }

    override fun setTotal(total: Long) = Unit
    override fun setCompleted(complete: Long) = Unit
    override fun cryptoGetTextPassword(): String = password ?: ""
}

/**
 * 将 7-Zip 解压结果转换为用户可读错误。
 *
 * @param result 7-Zip 解压结果。
 * @return 用户可读错误文本。
 */
private fun formatExtractError(result: ExtractOperationResult): String = when (result) {
    ExtractOperationResult.WRONG_PASSWORD -> "密码错误"
    ExtractOperationResult.DATAERROR -> "数据错误（密码可能不正确）"
    ExtractOperationResult.CRCERROR -> "CRC 校验失败（密码可能不正确）"
    else -> result.name
}
