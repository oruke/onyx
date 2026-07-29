package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.SevenZip
import java.io.Closeable

/**
 * 封装 7-Zip 归档与 VFS 输入流，确保两个资源统一关闭。
 *
 * @param input 归档 VFS 输入流。
 * @param archive 7-Zip 归档读取器。
 */
internal class SevenZipArchiveHandle(
    private val input: SevenZipVfsInStream,
    val archive: IInArchive,
) : Closeable {
    /**
     * 依次关闭 7-Zip 归档与底层 VFS 输入流。
     */
    override fun close() {
        try {
            archive.close()
        } finally {
            input.close()
        }
    }
}

/**
 * 从统一 VFS 随机访问服务打开 7-Zip 归档。
 *
 * @param path 归档 VFS 位置。
 * @param randomAccessService 随机访问服务。
 * @param password 可选归档密码。
 * @return 必须通过 `use` 关闭的归档句柄。
 */
internal suspend fun openSevenZipArchive(
    path: String,
    randomAccessService: RoutableVfsRandomAccessService,
    password: String? = null,
): SevenZipArchiveHandle {
    val source = randomAccessService.openRandomAccess(path, VfsRandomAccessMode.READ).getOrThrow()
    var input: SevenZipVfsInStream? = null
    return runCatching {
        SevenZipNativeRuntime.ensureInitialized()
        val archiveInput = SevenZipVfsInStream(source, source.length())
        input = archiveInput
        val archive = if (password != null) {
            SevenZip.openInArchive(null, archiveInput, password)
        } else {
            SevenZip.openInArchive(null, archiveInput)
        }
        SevenZipArchiveHandle(archiveInput, archive)
    }.getOrElse { failure ->
        runCatching {
            input?.close() ?: source.close()
        }.onFailure(failure::addSuppressed)
        throw failure
    }
}
