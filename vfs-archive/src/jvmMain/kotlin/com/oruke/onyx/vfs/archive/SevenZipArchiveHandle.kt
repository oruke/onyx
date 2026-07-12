package com.oruke.onyx.vfs.archive

import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.Closeable
import java.io.RandomAccessFile

/**
 * 封装 7-Zip 归档与底层随机访问文件，确保两个资源统一关闭。
 *
 * @param randomAccessFile 归档随机访问文件。
 * @param archive 7-Zip 归档读取器。
 */
internal class SevenZipArchiveHandle(
    private val randomAccessFile: RandomAccessFile,
    val archive: IInArchive,
) : Closeable {
    override fun close() {
        try {
            archive.close()
        } finally {
            randomAccessFile.close()
        }
    }
}

/**
 * 打开 7-Zip 归档并转移底层文件资源所有权。
 *
 * @param path 归档物理路径。
 * @param password 可选归档密码。
 * @return 必须通过 `use` 关闭的归档句柄。
 */
internal fun openSevenZipArchive(
    path: String,
    password: String? = null,
): SevenZipArchiveHandle {
    val randomAccessFile = RandomAccessFile(path, "r")
    return runCatching {
        val input = RandomAccessFileInStream(randomAccessFile)
        val archive = if (password != null) {
            SevenZip.openInArchive(null, input, password)
        } else {
            SevenZip.openInArchive(null, input)
        }
        SevenZipArchiveHandle(randomAccessFile, archive)
    }.getOrElse { failure ->
        randomAccessFile.close()
        throw failure
    }
}
