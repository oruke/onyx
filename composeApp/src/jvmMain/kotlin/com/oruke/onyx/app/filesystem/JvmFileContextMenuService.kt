package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class JvmFileContextMenuService(
    private val openWithService: OpenWithService,
    private val systemMenuService: SystemMenuService,
) : FileContextMenuService {
    private val sectionCache = ConcurrentHashMap<FileContextMenuCacheKey, List<FileContextMenuSection>>()

    override fun supportsOpenWith(entry: VFile): Boolean {
        return openWithService.supports(entry)
    }

    override suspend fun listSections(request: FileContextMenuRequest): List<FileContextMenuSection> {
        val entries = request.entries
        if (entries.isEmpty()) return emptyList()
        val cacheKey = entries.toContextMenuCacheKey()
        sectionCache[cacheKey]?.let { sections -> return sections }

        val sections = coroutineScope {
            val singleEntry = entries.singleOrNull()
            val openWithDeferred: Deferred<List<OpenWithApp>>? =
                if (singleEntry != null && openWithService.supports(singleEntry)) {
                    async {
                        runCatching { openWithService.listApps(singleEntry) }.getOrDefault(emptyList())
                    }
                } else {
                    null
                }
            val systemDeferred = async {
                runCatching { systemMenuService.listActions(entries) }.getOrDefault(emptyList())
            }

            val openWithItems = openWithDeferred
                ?.await()
                .orEmpty()
                .map { app -> app.toContextMenuItem() }
            val systemItems = systemDeferred
                .await()
                .map { action -> action.toContextMenuItem() }

            buildList {
                if (singleEntry != null && openWithService.supports(singleEntry)) {
                    add(
                        FileContextMenuSection(
                            kind = FileContextMenuSectionKind.OPEN_WITH,
                            items = openWithItems + FileContextMenuItem(
                                id = OPEN_WITH_CHOOSER_ID,
                                displayName = "",
                                label = FileContextMenuLabel.OPEN_WITH_OTHER,
                                command = FileContextMenuCommand.OpenWithChooser,
                            ),
                        )
                    )
                }
                if (systemItems.isNotEmpty()) {
                    add(FileContextMenuSection(FileContextMenuSectionKind.SYSTEM, systemItems))
                }
            }
        }
        sectionCache[cacheKey] = sections
        return sections
    }

    override suspend fun execute(
        command: FileContextMenuCommand,
        entries: List<VFile>,
    ): Result<Unit> {
        return when (command) {
            is FileContextMenuCommand.OpenWith -> {
                val entry = entries.singleOrNull()
                    ?: return Result.failure(IllegalStateException("Open with requires a single entry"))
                openWithService.openWith(entry, command.app)
            }

            FileContextMenuCommand.OpenWithChooser -> {
                val entry = entries.singleOrNull()
                    ?: return Result.failure(IllegalStateException("Open with chooser requires a single entry"))
                openWithService.openWithChooser(entry)
            }

            is FileContextMenuCommand.SystemAction -> {
                systemMenuService.execute(command.action, entries)
            }
        }
    }

    private fun OpenWithApp.toContextMenuItem(): FileContextMenuItem {
        return FileContextMenuItem(
            id = "open-with:$id",
            displayName = displayName,
            iconPath = iconPath,
            command = FileContextMenuCommand.OpenWith(this),
        )
    }

    private fun SystemMenuAction.toContextMenuItem(): FileContextMenuItem {
        return FileContextMenuItem(
            id = "system:$id",
            displayName = displayName,
            iconPath = iconPath,
            command = if (children.isEmpty()) FileContextMenuCommand.SystemAction(this) else null,
            children = children.map { child -> child.toContextMenuItem() },
        )
    }

    private fun List<VFile>.toContextMenuCacheKey(): FileContextMenuCacheKey {
        return FileContextMenuCacheKey(
            count = size,
            signatures = map { entry ->
                val extension = entry.name.substringAfterLast('.', "")
                    .takeIf { value -> value.isNotBlank() }
                    ?.lowercase()
                FileContextMenuEntrySignature(
                    kind = entry.kind.name,
                    extension = extension,
                )
            },
        )
    }

    private data class FileContextMenuCacheKey(
        val count: Int,
        val signatures: List<FileContextMenuEntrySignature>,
    )

    private data class FileContextMenuEntrySignature(
        val kind: String,
        val extension: String?,
    )

    private companion object {
        const val OPEN_WITH_CHOOSER_ID = "open-with:chooser"
    }
}
