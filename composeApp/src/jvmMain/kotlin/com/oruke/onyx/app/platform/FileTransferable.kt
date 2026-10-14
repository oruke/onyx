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

    /** 供标准桌面协议读取的 file URI 列表。 */
    private val uriListString: String by lazy { files.joinToString("\r\n") { it.toURI().toString() } }

    /** GNOME 文件传输数据，首行明确 copy 或 cut。 */
    private val gnomeCopiedBytes: ByteBuffer by lazy {
        val action = if (isCut) "cut" else "copy"
        val content = action + "\n" + files.joinToString("\n") { it.toURI().toString() }
        ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
    }

    /** 本载荷实际支持的格式；纯 VFS 载荷只向当前应用提供标识。 */
    private val supportedFlavors: Array<DataFlavor> = buildList {
        if (files.isNotEmpty()) {
            add(DataFlavor.javaFileListFlavor)
            add(FileClipboardFormats.uriList)
            add(FileClipboardFormats.gnomeFiles)
            add(DataFlavor.stringFlavor)
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
            FileClipboardFormats.uriList, DataFlavor.stringFlavor -> uriListString
            FileClipboardFormats.gnomeFiles -> gnomeCopiedBytes.duplicate()
            FileClipboardFormats.preferredDropEffect -> ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(if (isCut) FileClipboardFormats.MOVE_EFFECT else FileClipboardFormats.COPY_EFFECT).array()
            FileClipboardFormats.ownerId -> checkNotNull(clipboardOwnerId)
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
