package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.platform.ExternalDragHelper
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

    fun stageCopy(entries: List<VFile>) {
        if (entries.isEmpty()) return
        _clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.COPY,
            entries = entries,
        )
        writeToSystemClipboard(entries, isCut = false)
    }

    fun stageCut(entries: List<VFile>) {
        if (entries.isEmpty()) return
        _clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.CUT,
            entries = entries,
        )
        writeToSystemClipboard(entries, isCut = true)
    }

    fun consume(): ClipboardPayload? = _clipboard.value

    fun clearIfCut() {
        if (_clipboard.value?.operation == ClipboardOperation.CUT) {
            _clipboard.value = null
        }
    }

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
                val transferable = ExternalDragHelper.FileTransferable(
                    files = files,
                    isCut = isCut,
                )
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    transferable, null,
                )
            }
        } catch (e: Exception) {
            // 剪切板写入失败不应阻断内部操作
            OnyxLogger.warn("ClipboardManager", "系统剪切板写入失败", e)
        }
    }

    data class ClipboardPayload(
        val operation: ClipboardOperation,
        val entries: List<VFile>,
    )

    enum class ClipboardOperation {
        COPY,
        CUT,
    }
}
