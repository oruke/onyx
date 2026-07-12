package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.FileTransferProgressSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.util.concurrent.CancellationException

/**
 * 本地文件内容复制、属性保留与部分目标回滚操作。
 */
internal object LocalFileCopyOperations {
    /**
     * 递归复制路径，并在 I/O 失败或任务取消时清理部分目标。
     *
     * @param source 源路径。
     * @param target 目标路径。
     * @param progressSink 字节增量接收器。
     */
    fun copyPathWithRollback(
        source: Path,
        target: Path,
        progressSink: FileTransferProgressSink,
    ) {
        try {
            copyPathRecursively(source, target, progressSink)
        } catch (failure: IOException) {
            rollbackPartialTarget(target, failure)
            throw failure
        } catch (failure: CancellationException) {
            rollbackPartialTarget(target, failure)
            throw failure
        }
    }

    /**
     * 递归复制文件或目录。
     *
     * @param source 源路径。
     * @param target 目标路径。
     * @param progressSink 字节增量接收器。
     */
    private fun copyPathRecursively(
        source: Path,
        target: Path,
        progressSink: FileTransferProgressSink,
    ) {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, CopyFileVisitor(source, target, progressSink))
        } else {
            copyPathContent(source, target, progressSink)
        }
    }

    /**
     * 使用固定缓冲区复制单个文件，并在每次成功写入后上报字节增量。
     *
     * @param source 源文件。
     * @param target 目标文件。
     * @param progressSink 字节增量接收器。
     */
    internal fun copyPathContent(
        source: Path,
        target: Path,
        progressSink: FileTransferProgressSink,
    ) {
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                copyStream(input, output, progressSink)
            }
        }
        copyAttributes(source, target)
    }

    /**
     * 复制流内容并在每次成功写入后上报字节增量。
     *
     * @param input 源输入流。
     * @param output 目标输出流。
     * @param progressSink 字节增量接收器。
     */
    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        progressSink: FileTransferProgressSink,
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read)
                progressSink.onBytesTransferred(read.toLong())
            }
            read = input.read(buffer)
        }
    }

    /**
     * 复制基础时间、POSIX 权限和 DOS 标志。
     *
     * @param source 源路径。
     * @param target 目标路径。
     */
    internal fun copyAttributes(
        source: Path,
        target: Path,
    ) {
        val basicAttributes = Files.readAttributes(
            source,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        Files.getFileAttributeView(
            target,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.setTimes(
            basicAttributes.lastModifiedTime(),
            basicAttributes.lastAccessTime(),
            basicAttributes.creationTime(),
        )
        copyPosixPermissions(source, target)
        copyDosFlags(source, target)
    }

    /**
     * 在源与目标都支持 POSIX 属性视图时复制权限位。
     *
     * @param source 源路径。
     * @param target 目标路径。
     */
    private fun copyPosixPermissions(
        source: Path,
        target: Path,
    ) {
        val sourceView = Files.getFileAttributeView(
            source,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val targetView = Files.getFileAttributeView(
            target,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (sourceView != null && targetView != null) {
            targetView.setPermissions(sourceView.readAttributes().permissions())
        }
    }

    /**
     * 在源与目标都支持 DOS 属性视图时复制桌面文件标志。
     *
     * @param source 源路径。
     * @param target 目标路径。
     */
    private fun copyDosFlags(
        source: Path,
        target: Path,
    ) {
        val sourceView = Files.getFileAttributeView(
            source,
            DosFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val targetView = Files.getFileAttributeView(
            target,
            DosFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (sourceView != null && targetView != null) {
            val attributes = sourceView.readAttributes()
            targetView.setArchive(attributes.isArchive)
            targetView.setHidden(attributes.isHidden)
            targetView.setReadOnly(attributes.isReadOnly)
            targetView.setSystem(attributes.isSystem)
        }
    }

    /**
     * 删除失败传输留下的部分目标，并把清理异常附加到原异常。
     *
     * @param target 部分写入的目标路径。
     * @param failure 原始传输异常。
     */
    private fun rollbackPartialTarget(
        target: Path,
        failure: Exception,
    ) {
        runCatching { LocalPathTransferOperations.deletePathRecursively(target) }
            .onFailure { cleanupFailure -> failure.addSuppressed(cleanupFailure) }
    }

    /** 本地复制缓冲区大小。 */
    private const val COPY_BUFFER_SIZE = 1024 * 1024
}

/**
 * 递归复制目录树的文件访问器。
 */
private class CopyFileVisitor(
    /** 源目录根路径。 */
    private val sourceRoot: Path,
    /** 目标目录根路径。 */
    private val targetRoot: Path,
    /** 实际复制内容的字节增量接收器。 */
    private val progressSink: FileTransferProgressSink,
) : SimpleFileVisitor<Path>() {
    /**
     * 创建目标目录。
     *
     * @param dir 当前源目录。
     * @param attrs 当前目录属性。
     * @return 继续遍历。
     */
    override fun preVisitDirectory(
        dir: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(dir)))
        return FileVisitResult.CONTINUE
    }

    /**
     * 复制单个文件并保留属性。
     *
     * @param file 当前源文件。
     * @param attrs 当前文件属性。
     * @return 继续遍历。
     */
    override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        LocalFileCopyOperations.copyPathContent(
            source = file,
            target = targetRoot.resolve(sourceRoot.relativize(file)),
            progressSink = progressSink,
        )
        return FileVisitResult.CONTINUE
    }

    /**
     * 在目录内容完成后复制目录自身属性。
     *
     * @param dir 当前源目录。
     * @param exc 遍历异常；非空时交给调用方。
     * @return 继续遍历。
     */
    override fun postVisitDirectory(
        dir: Path,
        exc: IOException?,
    ): FileVisitResult {
        if (exc != null) throw exc
        LocalFileCopyOperations.copyAttributes(
            source = dir,
            target = targetRoot.resolve(sourceRoot.relativize(dir)),
        )
        return FileVisitResult.CONTINUE
    }
}
