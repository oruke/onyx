package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.platform.FileTransferable
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 剪贴板管理器 — 管理内部文件剪贴板和系统剪贴板同步。
 */
class ClipboardManager {

    private val _clipboard = MutableStateFlow<ClipboardPayload?>(null)
    val clipboard: StateFlow<ClipboardPayload?> = _clipboard.asStateFlow()

    /** 当前是否有待粘贴内容。 */
    val canPaste: Boolean get() = _clipboard.value != null

    /**
     * 暂存复制条目并同步到系统剪贴板。
     *
     * @param entries 待复制条目。
     */
    fun stageCopy(entries: List<VFile>) {
        if (entries.isEmpty()) return
        _clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.COPY,
            entries = entries,
        )
        writeToSystemClipboard(entries, isCut = false)
    }

    /**
     * 暂存剪切条目并同步到系统剪贴板。
     *
     * @param entries 待剪切条目。
     */
    fun stageCut(entries: List<VFile>) {
        if (entries.isEmpty()) return
        _clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.CUT,
            entries = entries,
        )
        writeToSystemClipboard(entries, isCut = true)
    }

    /**
     * 读取当前内部剪贴板内容。
     *
     * @return 当前剪贴板载荷；没有内容时返回 `null`。
     */
    fun consume(): ClipboardPayload? = _clipboard.value

    /**
     * 在当前载荷为剪切操作时清空内部剪贴板。
     */
    fun clearIfCut() {
        if (_clipboard.value?.operation == ClipboardOperation.CUT) {
            _clipboard.value = null
        }
    }

    /**
     * 无条件清空内部剪贴板。
     */
    fun clear() {
        _clipboard.value = null
    }

    private fun writeToSystemClipboard(entries: List<VFile>, isCut: Boolean) {
        try {
            val files = entries.mapNotNull { entry ->
                // 只处理本地文件（archive:// 等协议无法粘贴到外部）
                val file = java.io.File(entry.location)
                if (file.exists()) file else null
            }
            if (files.isNotEmpty()) {
                val transferable = FileTransferable(
                    files = files,
                    isCut = isCut,
                )
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    transferable, null,
                )
            }
        } catch (e: IllegalStateException) {
            // 剪切板写入失败不应阻断内部操作
            OnyxLogger.warn("ClipboardManager", "系统剪切板写入失败", e)
        } catch (e: java.awt.HeadlessException) {
            OnyxLogger.warn("ClipboardManager", "当前环境没有系统剪切板", e)
        } catch (e: SecurityException) {
            OnyxLogger.warn("ClipboardManager", "系统剪切板访问被拒绝", e)
        }
    }

    /**
     * 内部文件剪贴板载荷。
     */
    data class ClipboardPayload(
        /** 剪贴板操作类型。 */
        val operation: ClipboardOperation,

        /** 暂存的文件条目。 */
        val entries: List<VFile>,
    )

    /**
     * 内部文件剪贴板操作类型。
     */
    enum class ClipboardOperation {
        COPY,
        CUT,
    }
}
