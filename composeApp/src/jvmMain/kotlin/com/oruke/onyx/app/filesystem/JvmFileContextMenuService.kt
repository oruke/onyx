package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM 平台文件右键菜单服务，负责合并“打开方式”和系统右键菜单两类扩展动作。
 *
 * @param openWithService 平台“打开方式”服务。
 * @param systemMenuService 平台系统右键菜单服务。
 */
class JvmFileContextMenuService(
    private val openWithService: OpenWithService,
    private val systemMenuService: SystemMenuService,
) : FileContextMenuService {
    /** “打开方式”应用列表缓存；系统菜单不能缓存，因为 Shell 扩展会按路径动态生成菜单项。 */
    private val openWithCache = ConcurrentHashMap<OpenWithCacheKey, List<FileContextMenuItem>>()

    /**
     * 判断指定条目是否支持平台“打开方式”菜单。
     *
     * @param entry 待检查的文件条目。
     * @return `true` 表示可以查询“打开方式”应用列表。
     */
    override fun supportsOpenWith(entry: VFile): Boolean {
        return openWithService.supports(entry)
    }

    /**
     * 查询右键菜单扩展分区。
     *
     * “打开方式”可以按类型缓存；系统菜单必须实时读取，否则火绒、Git、压缩软件等动态菜单会复用错误。
     *
     * @param request 右键菜单查询请求。
     * @return 可展示的右键菜单分区列表。
     */
    override suspend fun listSections(request: FileContextMenuRequest): List<FileContextMenuSection> {
        val entries = request.entries
        if (entries.isEmpty()) return emptyList()

        return coroutineScope {
            val singleEntry = entries.singleOrNull()
            val supportsOpenWith = singleEntry?.let { entry -> openWithService.supports(entry) } == true
            val openWithDeferred: Deferred<List<FileContextMenuItem>>? =
                singleEntry?.takeIf { supportsOpenWith }?.let { entry ->
                    async {
                        listOpenWithItems(entry)
                    }
                }
            val systemDeferred = async {
                runCatching { systemMenuService.listActions(entries) }.getOrDefault(emptyList())
            }

            val openWithItems = openWithDeferred
                ?.let { deferred ->
                    withTimeoutOrNull(OPEN_WITH_SECTION_TIMEOUT_MS) { deferred.await() }
                        ?: emptyList<FileContextMenuItem>().also { deferred.cancel() }
                }
                .orEmpty()
            val systemItems = (
                withTimeoutOrNull(SYSTEM_SECTION_TIMEOUT_MS) { systemDeferred.await() }
                    ?: emptyList<SystemMenuAction>().also { systemDeferred.cancel() }
                )
                .map { action -> action.toContextMenuItem() }

            buildList {
                if (supportsOpenWith) {
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
    }

    /**
     * 执行统一右键菜单命令。
     *
     * @param command 用户点击的菜单命令。
     * @param entries 命令作用的文件条目。
     * @return 命令执行结果。
     */
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

    /**
     * 将“打开方式”应用转换为统一菜单项。
     *
     * @return 可展示与执行的菜单项。
     */
    private fun OpenWithApp.toContextMenuItem(): FileContextMenuItem {
        return FileContextMenuItem(
            id = "open-with:$id",
            displayName = displayName,
            iconPath = iconPath,
            command = FileContextMenuCommand.OpenWith(this),
        )
    }

    /**
     * 查询并缓存“打开方式”菜单项。
     *
     * @param entry 需要查询应用列表的单个文件条目。
     * @return “打开方式”菜单项列表。
     */
    private suspend fun listOpenWithItems(entry: VFile): List<FileContextMenuItem> {
        val cacheKey = entry.toOpenWithCacheKey()
        openWithCache[cacheKey]?.let { items -> return items }
        val items = runCatching { openWithService.listApps(entry) }
            .getOrDefault(emptyList())
            .map { app -> app.toContextMenuItem() }
        openWithCache[cacheKey] = items
        return items
    }

    /**
     * 将系统菜单动作转换为统一菜单项。
     *
     * @return 可展示的系统菜单项。
     */
    private fun SystemMenuAction.toContextMenuItem(): FileContextMenuItem {
        return FileContextMenuItem(
            id = "system:$id",
            displayName = displayName,
            iconPath = iconPath,
            command = if (children.isEmpty()) FileContextMenuCommand.SystemAction(this) else null,
            children = children.map { child -> child.toContextMenuItem() },
        )
    }

    /**
     * 生成“打开方式”缓存键。
     *
     * @return 基于文件类型与扩展名的缓存键。
     */
    private fun VFile.toOpenWithCacheKey(): OpenWithCacheKey {
        return OpenWithCacheKey(
            kind = kind.name,
            extension = name.substringAfterLast('.', "")
                .takeIf { value -> value.isNotBlank() }
                ?.lowercase(),
        )
    }

    /**
     * “打开方式”缓存键。
     *
     * @property kind 文件类型。
     * @property extension 文件扩展名。
     */
    private data class OpenWithCacheKey(
        val kind: String,
        val extension: String?,
    )

    private companion object {
        /** “打开方式”固定入口的菜单 ID。 */
        const val OPEN_WITH_CHOOSER_ID = "open-with:chooser"
        /** “打开方式”来源的单独等待时间，超时后忽略该来源。 */
        const val OPEN_WITH_SECTION_TIMEOUT_MS = 900L
        /** 系统右键菜单来源的单独等待时间，超时后保留其他已完成来源。 */
        const val SYSTEM_SECTION_TIMEOUT_MS = 1_900L
    }
}
