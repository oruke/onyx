package com.oruke.onyx.app.platform

import com.oruke.onyx.app.filesystem.systemLocalPathOrNull
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 系统剪贴板短暂被其它进程占用时，最多重试三次，不延长为无限等待。 */
private const val CLIPBOARD_ACCESS_RETRY_COUNT = 3

/** 两次系统剪贴板访问之间的等待时间，单位毫秒。 */
private const val CLIPBOARD_ACCESS_RETRY_DELAY_MS = 50L

/** 系统文件剪贴板的一次快照，不携带平台文件对象或已过期的 VFS 元数据。 */
internal data class SystemFileClipboardSnapshot(
    /** 系统当前保存的本地文件路径。 */
    val locations: List<String>,
    /** 当前系统载荷是否明确要求移动。 */
    val isCut: Boolean,
    /** 当前 JVM 的内部载荷标识；外部载荷为 null。 */
    val ownerId: String?,
)

/** JVM 系统文件剪贴板桥接，将平台格式读写隔离在 I/O 线程中。 */
internal class JvmSystemFileClipboardService {
    init {
        FileClipboardFormats.registerPlatformFormats()
    }

    /**
     * 读取当前系统文件载荷；普通文本、图片或空剪贴板返回空文件快照。
     * @return 当前快照或可本地化的系统访问错误，失败时绝不提供旧载荷。
     */
    suspend fun read(): Result<SystemFileClipboardSnapshot> = withSystemFileClipboardAccess {
        Toolkit.getDefaultToolkit().systemClipboard.getContents(null).fileSnapshot()
    }

    /**
     * 发布文件和内部载荷标识；远端条目只在当前 JVM 内保留完整 VFS 语义。
     * @param entries 本次用户明确复制或剪切的条目。
     * @param isCut 是否为剪切。
     * @param ownerId 本次载荷的唯一标识，防止同路径的外部复制沿用旧剪切语义。
     * @return 已发布的系统快照或明确失败。
     */
    suspend fun write(entries: List<VFile>, isCut: Boolean, ownerId: String): Result<SystemFileClipboardSnapshot> =
        withSystemFileClipboardAccess {
            val files = entries.mapNotNull { it.systemLocalPathOrNull()?.toFile() }
            val contents = FileTransferable(files, isCut, ownerId)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(contents, null)
            contents.fileSnapshot()
        }

    /**
     * 仅清理仍与已完成传输一致的系统载荷，保留传输期间新复制的内容。
     * @param expected 启动此次剪切传输时读取的快照。
     * @return 确实清理时为 true，系统已被重写时为 false，访问错误以失败返回。
     */
    suspend fun clearIfUnchanged(expected: SystemFileClipboardSnapshot): Result<Boolean> =
        withSystemFileClipboardAccess {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.getContents(null).fileSnapshot() == expected) {
                clipboard.setContents(StringSelection(""), null)
                true
            } else {
                false
            }
        }
}

/**
 * 在后台线程访问系统剪贴板，只针对 AWT 明确表示暂时占用的状态进行有界重试。
 * @param operation 完整的一次平台访问，清理重试必须重新比较快照，不能清除后来写入的数据。
 * @return 成功结果或三语错误；其它格式错误不重试，协程取消立即传播。
 */
private suspend fun <T> withSystemFileClipboardAccess(operation: () -> T): Result<T> = withContext(Dispatchers.IO) {
    var result = runCatching(operation)
    var retries = 0
    // Windows Clipboard 是进程间独占资源；短暂占用不能被解释为剪贴板为空，也不能使用旧载荷。
    while (result.exceptionOrNull() is IllegalStateException && result.exceptionOrNull() !is CancellationException &&
        retries < CLIPBOARD_ACCESS_RETRY_COUNT) {
        delay(CLIPBOARD_ACCESS_RETRY_DELAY_MS)
        retries++
        result = runCatching(operation)
    }
    result.mapClipboardFailure()
}

/**
 * 将一次系统 Transferable 解析为文件载荷，禁止把普通文本猜测为可操作路径。
 * @return 文件路径、操作类型及当前 JVM 标识；格式损坏时抛出诊断异常。
 */
private fun Transferable?.fileSnapshot(): SystemFileClipboardSnapshot {
    val marker = if (this?.isDataFlavorSupported(FileClipboardFormats.ownerId) == true) {
        getTransferData(FileClipboardFormats.ownerId) as String
    } else null
    val files = if (this?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true) {
        (getTransferData(DataFlavor.javaFileListFlavor) as List<*>).map { (it as File).absolutePath }
    } else emptyList()
    return SystemFileClipboardSnapshot(files.distinct(), this?.fileCutFlag() ?: false, marker)
}

/**
 * 读取当前载荷的明确剪切标记，Windows 使用小端 DWORD，GNOME 使用 cut 操作头。
 * @return 只有当前载荷明确声明移动时为 true，无标记的文件列表按复制处理。
 */
private fun Transferable.fileCutFlag(): Boolean {
    return when {
        isDataFlavorSupported(FileClipboardFormats.preferredDropEffect) -> {
            val bytes = getTransferData(FileClipboardFormats.preferredDropEffect) as ByteArray
            require(bytes.size >= Int.SIZE_BYTES) { "Invalid Preferred DropEffect" }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == FileClipboardFormats.MOVE_EFFECT
        }
        isDataFlavorSupported(FileClipboardFormats.gnomeFiles) -> {
            val buffer = (getTransferData(FileClipboardFormats.gnomeFiles) as ByteBuffer).duplicate()
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull() == "cut"
        }
        else -> false
    }
}

/**
 * 为系统剪贴板访问失败提供统一的三语反馈，同时保留底层诊断和协程取消语义。
 * @return 成功结果原样保留；失败结果携带可本地化的异常。
 */
private fun <T> Result<T>.mapClipboardFailure(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { failure ->
        if (failure is CancellationException) throw failure
        Result.failure(LocalizedException(I18nMessage(MessageKey.MSG_SYSTEM_CLIPBOARD_UNAVAILABLE), failure))
    },
)
