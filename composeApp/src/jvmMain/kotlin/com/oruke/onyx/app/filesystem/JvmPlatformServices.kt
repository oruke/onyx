package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * JVM Desktop 外部打开服务。
 *
 * @property materializer 系统文件物化服务；可将远程文件或压缩包条目转换为本地临时文件后再交给 Desktop API。
 */
class JvmDesktopExternalOpenService(
    private val materializer: SystemFileMaterializer? = null,
) : ExternalOpenService {
    /**
     * 使用系统默认应用打开文件。
     *
     * @param entry 待打开条目。
     * @return 打开结果。
     */
    override suspend fun open(entry: VFile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetEntry = materializer
                ?.takeIf { candidate -> candidate.supports(entry) }
                ?.materialize(entry)
                ?.getOrThrow()
                ?: entry
            check(Desktop.isDesktopSupported()) {
                "Desktop integration is not available"
            }
            val desktop = Desktop.getDesktop()
            check(desktop.isSupported(Desktop.Action.OPEN)) {
                "Open action is not supported on this platform"
            }
            desktop.open(Path.of(targetEntry.location).toFile())
        }
    }
}

class JvmDesktopTrashService : TrashService {
    override val isSupported: Boolean
        get() = Desktop.isDesktopSupported() && runCatching {
            Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
        }.getOrDefault(false)

    override suspend fun moveToTrash(entries: List<VFile>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isSupported) {
                "Trash is not supported on this platform"
            }
            val desktop = Desktop.getDesktop()
            entries.forEach { entry ->
                check(desktop.moveToTrash(Path.of(entry.location).toFile())) {
                    "Failed to move ${entry.name} to trash"
                }
            }
        }
    }
}

class JvmTextClipboardService : TextClipboardService {
    override suspend fun copyText(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }
}
