package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Path

class JvmDesktopExternalOpenService : ExternalOpenService {
    override suspend fun open(entry: VFile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(Desktop.isDesktopSupported()) {
                "Desktop integration is not available"
            }
            val desktop = Desktop.getDesktop()
            check(desktop.isSupported(Desktop.Action.OPEN)) {
                "Open action is not supported on this platform"
            }
            desktop.open(Path.of(entry.location).toFile())
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
