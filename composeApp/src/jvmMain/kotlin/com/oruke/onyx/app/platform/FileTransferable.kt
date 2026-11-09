package com.oruke.onyx.app.platform

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 将本地文件转换为 Java、Windows Shell 和 GNOME 可读取的系统传输载荷。
 * @param files 交给系统的本地文件；远端 VFS 载荷可以没有本地条目。
 * @param isCut 是否使用剪切而非复制语义。
 * @param clipboardOwnerId 可选的当前 JVM 剪贴板标识，用于保留完整内部 VFS 载荷。
 */
class FileTransferable(
    /** 交给系统的本地文件；纯远端 VFS 载荷没有本地条目。 */
    private val files: List<File>,
    /** 是否向系统声明剪切语义。 */
    private val isCut: Boolean = false,
    /** 当前 JVM 保留完整 VFS 载荷的可选唯一标识。 */
    private val clipboardOwnerId: String? = null,
) : Transferable {
    init {
        FileClipboardFormats.registerPlatformFormats()
    }

    /** 当前运行环境是否为 Windows。 */
    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** 供标准桌面协议读取的 RFC 8089 / RFC 2483 file URI 列表。 */
    private val uriListString: String by lazy {
        files.joinToString("\r\n") { it.toStandardFileUriString() }
    }

    /** GNOME 文件传输数据，首行明确 copy 或 cut。 */
    private val gnomeCopiedBytes: ByteBuffer by lazy {
        val action = if (isCut) "cut" else "copy"
        val content = action + "\n" + files.joinToString("\n") { it.toStandardFileUriString() }
        ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
    }

    /**
     * 本载荷实际支持的格式；Windows 仅暴露原生 Shell 文件列表与操作标记，
     * 避免浏览器或编辑器将带空格、中文或特殊符号的文件误判为 URL 文本跳转。
     */
    private val supportedFlavors: Array<DataFlavor> = buildList {
        if (files.isNotEmpty()) {
            add(DataFlavor.javaFileListFlavor)
            if (!isWindows) {
                add(FileClipboardFormats.uriList)
                add(FileClipboardFormats.gnomeFiles)
            }
            add(FileClipboardFormats.preferredDropEffect)
        }
        if (clipboardOwnerId != null) add(FileClipboardFormats.ownerId)
    }.toTypedArray()

    /** @return 当前载荷支持的数据格式副本，调用方不能修改内部格式列表。 */
    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors.clone()

    /**
     * 判断当前载荷能否提供指定格式。
     * @param flavor 请求的数据格式。
     * @return 当前载荷支持时返回 true。
     */
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in supportedFlavors

    /**
     * 按系统请求提供文件列表、操作标记或当前 JVM 的载荷标识。
     * @param flavor 请求的数据格式。
     * @return 对应格式的数据；不支持的格式抛出明确异常。
     */
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return when (flavor) {
            DataFlavor.javaFileListFlavor -> files.toList()
            FileClipboardFormats.uriList -> uriListString
            FileClipboardFormats.gnomeFiles -> gnomeCopiedBytes.duplicate()
            FileClipboardFormats.preferredDropEffect -> ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(if (isCut) FileClipboardFormats.MOVE_EFFECT else FileClipboardFormats.COPY_EFFECT).array()
            FileClipboardFormats.ownerId -> checkNotNull(clipboardOwnerId)
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}

/**
 * 将本地文件转换为符合 RFC 8089 / XDG 规范的三斜杠 ASCII `file:///` URI，
 * 确保空格、非 ASCII 字符及 `#`、`%`、`?`、`[`、`]` 等特殊符号均被正确百分号编码。
 */
internal fun File.toStandardFileUriString(): String = toPath().toUri().toASCIIString()
