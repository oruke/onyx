package com.oruke.onyx.app.component

import com.oruke.onyx.vfs.api.FileContextMenuCommand
import com.oruke.onyx.vfs.api.FileContextMenuItem
import com.oruke.onyx.vfs.api.FileContextMenuLabel
import com.oruke.onyx.vfs.api.FileContextMenuSection
import com.oruke.onyx.vfs.api.FileContextMenuSectionKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 右键菜单内置文案语义，由 UI 层映射到 i18n 资源。
 */
internal enum class PaneContextMenuText {
    OPEN,
    OPEN_IN_NEW_TAB,
    OPEN_WITH,
    OPEN_WITH_OTHER,
    RENAME,
    NEW_FILE,
    NEW_DIRECTORY,
    DELETE_SELECTED,
    EXTRACT_HERE,
    EXTRACT_TO_DIRECTORY,
    EXTRACT_SMART,
    BATCH_RENAME,
    COPY_PATH,
    COPY,
    CUT,
    PASTE,
    UNDO,
    REDO,
    REFRESH,
    OPEN_TERMINAL,
    CLOSE_MENU,
}

/**
 * 右键菜单图标语义，由 UI 层映射到当前图标库。
 */
internal enum class PaneContextMenuIcon {
    OPEN,
    OPEN_IN_NEW_TAB,
    EDIT,
    FILE,
    FOLDER,
    DELETE,
    EXTRACT,
    COPY,
    CUT,
    PASTE,
    UNDO,
    REDO,
    REFRESH,
    TERMINAL,
    CLOSE,
    EXECUTE,
    OPEN_WITH,
}

/**
 * 统一右键菜单命令，覆盖面板命令、跨组件动作和平台系统动作。
 */
internal sealed interface PaneContextMenuCommand {
    /**
     * 面板内命令。
     *
     * @property command 面板命令 ID。
     */
    data class Pane(
        val command: PaneCommand,
    ) : PaneContextMenuCommand

    /** 批量重命名当前选择项。 */
    data object BatchRename : PaneContextMenuCommand

    /** 在当前位置解压当前选择项。 */
    data object ExtractSelection : PaneContextMenuCommand

    /** 解压到同名目录。 */
    data object ExtractToDirectory : PaneContextMenuCommand

    /** 使用智能策略解压。 */
    data object ExtractSmart : PaneContextMenuCommand

    /** 复制当前选择项路径。 */
    data object CopyPath : PaneContextMenuCommand

    /** 在当前目录打开终端。 */
    data object OpenTerminal : PaneContextMenuCommand

    /**
     * 平台右键菜单命令。
     *
     * @property command 文件上下文菜单命令。
     */
    data class FileContext(
        val command: FileContextMenuCommand,
    ) : PaneContextMenuCommand
}

/**
 * 统一右键菜单节点。
 */
internal sealed interface PaneContextMenuNode {
    /** 菜单分隔线。 */
    data object Divider : PaneContextMenuNode

    /**
     * 可点击或可展开菜单项。
     *
     * @property id 菜单节点稳定 ID。
     * @property text 内置文案语义，平台菜单项可为空。
     * @property displayName 平台菜单项显示名称。
     * @property icon 图标语义。
     * @property iconPath 平台动态图标路径或标识。
     * @property enabled 菜单项是否可点击。
     * @property command 点击时执行的统一菜单命令。
     * @property children 级联子菜单节点。
     */
    data class Item(
        val id: String,
        val text: PaneContextMenuText? = null,
        val displayName: String? = null,
        val icon: PaneContextMenuIcon,
        val iconPath: String? = null,
        val enabled: Boolean = true,
        val command: PaneContextMenuCommand? = null,
        val children: List<PaneContextMenuNode> = emptyList(),
    ) : PaneContextMenuNode
}

/**
 * 统一右键菜单模型。
 *
 * @property nodes 菜单节点列表。
 */
internal data class PaneContextMenuModel(
    val nodes: List<PaneContextMenuNode>,
)

/**
 * 构建统一右键菜单需要的上下文。
 *
 * @property entries 本次右键菜单作用的文件条目。
 * @property canPaste 当前是否允许粘贴。
 * @property canUndo 当前是否允许撤销。
 * @property canRedo 当前是否允许重做。
 * @property canExtractSelection 当前选择项是否包含可解压文件。
 * @property contextMenuSections 平台菜单来源，包括打开方式与系统右键菜单。
 */
internal data class PaneContextMenuBuildInput(
    val entries: List<VFile>,
    val canPaste: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val canExtractSelection: Boolean,
    val contextMenuSections: List<FileContextMenuSection>,
)

/**
 * 右键菜单执行时需要 Root 或跨面板参与的动作集合。
 *
 * @property onBatchRename 执行批量重命名。
 * @property onExtractSelection 在当前位置解压选择项。
 * @property onExtractToDirectory 解压到同名目录。
 * @property onExtractSmart 使用智能策略解压。
 * @property onFileContextMenuCommand 执行平台右键菜单命令。
 * @property onOpenTerminal 在指定目录打开终端。
 */
internal data class PaneContextMenuExternalActions(
    val onBatchRename: () -> Unit,
    val onExtractSelection: () -> Unit,
    val onExtractToDirectory: () -> Unit,
    val onExtractSmart: () -> Unit,
    val onFileContextMenuCommand: (FileContextMenuCommand, List<VFile>) -> Unit,
    val onOpenTerminal: (String) -> Unit,
)

/**
 * 右键菜单命令控制器，负责把统一菜单命令分发到面板命令或跨组件动作。
 *
 * @property paneCommandController 面板命令控制器。
 * @property externalActions 右键菜单跨组件动作集合。
 */
internal class PaneContextMenuCommandController(
    private val paneCommandController: PaneCommandController,
    private val externalActions: PaneContextMenuExternalActions,
) {
    /**
     * 执行统一右键菜单命令。
     *
     * @param command 菜单命令。
     * @param entries 本次右键菜单作用的文件条目。
     * @param location 当前面板路径。
     * @return 命令已处理时返回 `true`。
     */
    fun execute(
        command: PaneContextMenuCommand,
        entries: List<VFile>,
        location: String,
    ): Boolean {
        return when (command) {
            is PaneContextMenuCommand.Pane -> {
                command.command == PaneCommand.CLOSE_MENU || paneCommandController.execute(command.command)
            }

            PaneContextMenuCommand.BatchRename -> {
                externalActions.onBatchRename()
                true
            }

            PaneContextMenuCommand.ExtractSelection -> {
                externalActions.onExtractSelection()
                true
            }

            PaneContextMenuCommand.ExtractToDirectory -> {
                externalActions.onExtractToDirectory()
                true
            }

            PaneContextMenuCommand.ExtractSmart -> {
                externalActions.onExtractSmart()
                true
            }

            PaneContextMenuCommand.CopyPath -> {
                paneCommandController.dispatch(PaneIntent.CopySelectedPaths)
                true
            }

            PaneContextMenuCommand.OpenTerminal -> {
                externalActions.onOpenTerminal(location)
                true
            }

            is PaneContextMenuCommand.FileContext -> {
                externalActions.onFileContextMenuCommand(command.command, entries)
                true
            }
        }
    }
}

/**
 * 统一右键菜单模型构建器，负责将内置文件操作与平台菜单合并为单一节点树。
 */
internal object PaneContextMenuModelBuilder {
    /**
     * 构建当前右键菜单模型。
     *
     * @param input 菜单构建上下文。
     * @return 可供 UI 直接渲染的菜单模型。
     */
    fun build(input: PaneContextMenuBuildInput): PaneContextMenuModel {
        val selectedCount = input.entries.size
        val singleEntry = input.entries.singleOrNull()
        val openWithSection = input.contextMenuSections.firstOrNull { section ->
            section.kind == FileContextMenuSectionKind.OPEN_WITH && section.items.isNotEmpty()
        }
        val systemSection = input.contextMenuSections.firstOrNull { section ->
            section.kind == FileContextMenuSectionKind.SYSTEM && section.items.isNotEmpty()
        }

        val nodes = buildList {
            addAll(openNodes(selectedCount, singleEntry, openWithSection, systemSection))
            addAll(fileOperationNodes(input, selectedCount))
            addAll(clipboardNodes(input, selectedCount))
            addAll(utilityNodes())
        }
        return PaneContextMenuModel(nodes)
    }

    /** 构建打开、打开方式和系统扩展菜单节点。 */
    private fun openNodes(
        selectedCount: Int,
        singleEntry: VFile?,
        openWithSection: FileContextMenuSection?,
        systemSection: FileContextMenuSection?,
    ): List<PaneContextMenuNode> = buildList {
        add(paneItem("open", PaneContextMenuText.OPEN, PaneContextMenuIcon.OPEN, PaneCommand.OPEN_SELECTION,
            selectedCount == 1))
        add(paneItem("open-new-tab", PaneContextMenuText.OPEN_IN_NEW_TAB, PaneContextMenuIcon.OPEN_IN_NEW_TAB,
            PaneCommand.OPEN_SELECTION_IN_NEW_TAB, singleEntry?.kind == VFileKind.DIRECTORY))
        openWithSection?.let { section ->
            add(
                PaneContextMenuNode.Item(
                    id = "open-with",
                    text = PaneContextMenuText.OPEN_WITH,
                    icon = PaneContextMenuIcon.OPEN_WITH,
                    children = section.items.map { item -> item.toPaneMenuNode() },
                )
            )
        }
        if (selectedCount > 0 && systemSection != null) {
            add(PaneContextMenuNode.Divider)
            addAll(systemSection.items.map { item -> item.toPaneMenuNode() })
        }
    }

    /** 构建重命名、新建、删除、解压和路径复制节点。 */
    private fun fileOperationNodes(
        input: PaneContextMenuBuildInput,
        selectedCount: Int,
    ): List<PaneContextMenuNode> = buildList {
        add(paneItem("rename", PaneContextMenuText.RENAME, PaneContextMenuIcon.EDIT,
            PaneCommand.RENAME_SELECTION, selectedCount == 1))
        add(paneItem("new-file", PaneContextMenuText.NEW_FILE, PaneContextMenuIcon.FILE, PaneCommand.NEW_FILE))
        add(paneItem("new-directory", PaneContextMenuText.NEW_DIRECTORY, PaneContextMenuIcon.FOLDER,
            PaneCommand.NEW_DIRECTORY))
        add(PaneContextMenuNode.Divider)
        add(paneItem("delete", PaneContextMenuText.DELETE_SELECTED, PaneContextMenuIcon.DELETE,
            PaneCommand.DELETE_SELECTION, selectedCount > 0))
        if (input.canExtractSelection) {
            add(externalItem("extract-here", PaneContextMenuText.EXTRACT_HERE, PaneContextMenuIcon.EXTRACT,
                PaneContextMenuCommand.ExtractSelection))
            add(externalItem("extract-to-directory", PaneContextMenuText.EXTRACT_TO_DIRECTORY,
                PaneContextMenuIcon.EXTRACT, PaneContextMenuCommand.ExtractToDirectory))
            add(externalItem("extract-smart", PaneContextMenuText.EXTRACT_SMART, PaneContextMenuIcon.EXTRACT,
                PaneContextMenuCommand.ExtractSmart))
        }
        if (selectedCount >= 2) {
            add(externalItem("batch-rename", PaneContextMenuText.BATCH_RENAME, PaneContextMenuIcon.EDIT,
                PaneContextMenuCommand.BatchRename))
        }
        add(externalItem("copy-path", PaneContextMenuText.COPY_PATH, PaneContextMenuIcon.COPY,
            PaneContextMenuCommand.CopyPath, selectedCount > 0))
    }

    /** 构建剪贴板和撤销历史节点。 */
    private fun clipboardNodes(
        input: PaneContextMenuBuildInput,
        selectedCount: Int,
    ): List<PaneContextMenuNode> = listOf(
        paneItem("copy", PaneContextMenuText.COPY, PaneContextMenuIcon.COPY,
            PaneCommand.COPY_SELECTION, selectedCount > 0),
        paneItem("cut", PaneContextMenuText.CUT, PaneContextMenuIcon.CUT,
            PaneCommand.CUT_SELECTION, selectedCount > 0),
        paneItem("paste", PaneContextMenuText.PASTE, PaneContextMenuIcon.PASTE, PaneCommand.PASTE, input.canPaste),
        paneItem("undo", PaneContextMenuText.UNDO, PaneContextMenuIcon.UNDO,
            PaneCommand.UNDO_LAST_OPERATION, input.canUndo),
        paneItem("redo", PaneContextMenuText.REDO, PaneContextMenuIcon.REDO,
            PaneCommand.REDO_LAST_OPERATION, input.canRedo),
    )

    /** 构建刷新、终端和关闭节点。 */
    private fun utilityNodes(): List<PaneContextMenuNode> = listOf(
        PaneContextMenuNode.Divider,
        paneItem("refresh", PaneContextMenuText.REFRESH, PaneContextMenuIcon.REFRESH, PaneCommand.REFRESH),
        externalItem("open-terminal", PaneContextMenuText.OPEN_TERMINAL, PaneContextMenuIcon.TERMINAL,
            PaneContextMenuCommand.OpenTerminal),
        paneItem("close", PaneContextMenuText.CLOSE_MENU, PaneContextMenuIcon.CLOSE, PaneCommand.CLOSE_MENU),
    )

    /**
     * 创建面板命令菜单项。
     *
     * @param id 菜单项稳定 ID。
     * @param text 菜单项文案语义。
     * @param icon 菜单项图标语义。
     * @param command 面板命令。
     * @param enabled 是否允许点击。
     * @return 统一菜单节点。
     */
    private fun paneItem(
        id: String,
        text: PaneContextMenuText,
        icon: PaneContextMenuIcon,
        command: PaneCommand,
        enabled: Boolean = true,
    ): PaneContextMenuNode.Item {
        return PaneContextMenuNode.Item(
            id = id,
            text = text,
            icon = icon,
            enabled = enabled,
            command = PaneContextMenuCommand.Pane(command),
        )
    }

    /**
     * 创建跨组件命令菜单项。
     *
     * @param id 菜单项稳定 ID。
     * @param text 菜单项文案语义。
     * @param icon 菜单项图标语义。
     * @param command 统一菜单命令。
     * @param enabled 是否允许点击。
     * @return 统一菜单节点。
     */
    private fun externalItem(
        id: String,
        text: PaneContextMenuText,
        icon: PaneContextMenuIcon,
        command: PaneContextMenuCommand,
        enabled: Boolean = true,
    ): PaneContextMenuNode.Item {
        return PaneContextMenuNode.Item(
            id = id,
            text = text,
            icon = icon,
            enabled = enabled,
            command = command,
        )
    }

    /**
     * 将平台文件菜单项转换为统一菜单节点。
     *
     * @return 统一菜单节点。
     */
    private fun FileContextMenuItem.toPaneMenuNode(): PaneContextMenuNode.Item {
        return PaneContextMenuNode.Item(
            id = id,
            text = when (label) {
                FileContextMenuLabel.OPEN_WITH_OTHER -> PaneContextMenuText.OPEN_WITH_OTHER
                null -> null
            },
            displayName = displayName,
            icon = when (command) {
                FileContextMenuCommand.OpenWithChooser -> PaneContextMenuIcon.OPEN_WITH
                else -> PaneContextMenuIcon.EXECUTE
            },
            iconPath = iconPath,
            command = command?.let { value -> PaneContextMenuCommand.FileContext(value) },
            children = children.map { child -> child.toPaneMenuNode() },
        )
    }
}
