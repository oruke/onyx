package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.vfs.api.SystemMenuService

/**
 * JVM Desktop 外部打开服务。
 *
 * @property materializer 系统文件物化服务；可将远程文件或压缩包条目转换为本地临时文件后再交给 Desktop API。
 */
internal class JvmDesktopExternalOpenService(
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

internal class JvmTextClipboardService : TextClipboardService {
    override suspend fun copyText(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }
}
