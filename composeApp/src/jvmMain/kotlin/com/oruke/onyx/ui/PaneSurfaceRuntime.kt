package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.oruke.onyx.app.component.PaneCommandController
import com.oruke.onyx.app.component.PaneCommandExecutionContext
import com.oruke.onyx.app.component.PaneCommandExternalActions
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneContextMenuCommandController
import com.oruke.onyx.app.component.PaneContextMenuExternalActions
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.key
import com.oruke.onyx.vfs.api.FileContextMenuSection

/**
 * 面板视图所需的稳定状态、组件和跨组件动作。
 */
internal data class PaneSurfaceModel(
    /** 当前面板状态快照。 */
    val state: PaneState,
    /** 当前面板是否为活动面板。 */
    val active: Boolean,
    /** 面板业务组件。 */
    val component: PaneComponent,
    /** 需要根组件参与的面板动作。 */
    val actions: PaneActions,
    /** 当前生效的命令快捷键映射。 */
    val commandShortcuts: OnyxCommandShortcutMap,
    /** 当前目标是否允许粘贴。 */
    val canPaste: Boolean,
    /** 用户收藏的位置列表。 */
    val favoriteLocations: List<String>,
    /** 激活当前面板的回调。 */
    val onActivate: () -> Unit,
)

/**
 * 面板 UI 使用的文件预览与路径服务。
 */
internal data class PaneSurfaceServices(
    /** 加载普通文件缩略图。 */
    val loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    /** 加载压缩包内容缩略图。 */
    val loadArchiveThumbnail: suspend (String, Int) -> ImageBitmap?,
    /** 读取文件哈希。 */
    val readFileHash: suspend (FileHashRequest) -> FileHashResult,
    /** 读取压缩包信息。 */
    val readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
    /** 将位置解析为面包屑。 */
    val buildBreadcrumbs: (String) -> List<VfsBreadcrumb>,
)

/**
 * 面板 Composable 生命周期内的瞬时交互状态。
 *
 * @param inlineEditActive 初始时是否正在进行内联编辑。
 */
internal class PaneSurfaceUiState(inlineEditActive: Boolean) {
    /** 是否显示当前右键菜单。 */
    var contextMenuVisible by mutableStateOf(false)

    /** 地址栏是否正在文本编辑。 */
    var addressBarEditing by mutableStateOf(false)

    /** 过滤输入框是否持有焦点。 */
    var filterFocused by mutableStateOf(false)

    /** 上一次组合时是否存在内联编辑。 */
    var inlineEditWasActive by mutableStateOf(inlineEditActive)

    /** 右键菜单在面板内的锚点。 */
    var contextMenuOffset by mutableStateOf(IntOffset.Zero)

    /** 当前右键菜单作用的条目 ID。 */
    var contextMenuEntryIds by mutableStateOf<Set<String>>(emptySet())

    /** 平台菜单异步结果的版本标识。 */
    var contextMenuQueryToken by mutableStateOf(0)

    /** 当前平台菜单分组。 */
    var contextMenuSections by mutableStateOf<List<FileContextMenuSection>>(emptyList())

    /** 当前面板的窗口坐标范围。 */
    var paneBounds by mutableStateOf<IntRect?>(null)

    /** 标签栏最近注册的放置区域。 */
    var tabBarDropZone by mutableStateOf<TabDropZone?>(null)
}

/**
 * 面板视图运行时控制器，集中提供命令、菜单、焦点和拖放状态变更。
 */
internal class PaneSurfaceRuntime(
    /** 面板状态与动作模型。 */
    val model: PaneSurfaceModel,
    /** 标签及文件拖放绑定。 */
    val dragBindings: PaneDragBindings,
    /** 文件预览与路径服务。 */
    val services: PaneSurfaceServices,
    /** 当前组合的瞬时 UI 状态。 */
    val ui: PaneSurfaceUiState,
    /** 面板主键盘焦点请求器。 */
    val focusRequester: FocusRequester,
    /** 过滤输入框焦点请求器。 */
    val filterFocusRequester: FocusRequester,
    /** 面板命令控制器。 */
    val commands: PaneCommandController,
    /** 面板右键菜单命令控制器。 */
    val menuCommands: PaneContextMenuCommandController,
    /** 平台右键菜单动作加载器。 */
    val platformActionLoader: PaneContextMenuPlatformActionLoader,
) {
    /** 当前面板状态。 */
    val state: PaneState
        get() = model.state

    /**
     * 派发面板 intent。
     *
     * @param intent 待处理的面板 intent。
     */
    fun dispatch(intent: PaneIntent) {
        commands.dispatch(intent)
    }

    /**
     * 执行统一 UI 命令。
     *
     * @param command 待执行命令。
     * @return 命令已处理时返回 `true`。
     */
    fun executeCommand(command: OnyxCommand): Boolean {
        val paneCommand = command.toPaneCommand()
        if (paneCommand != null) {
            return commands.execute(paneCommand)
        }
        return when (command) {
            OnyxCommand.QuickOpen -> {
                model.actions.onShowQuickOpen()
                true
            }
            OnyxCommand.ShowSearchPanel -> {
                model.actions.onShowSearchPanel()
                true
            }
            else -> false
        }
    }

    /**
     * 判断统一 UI 命令当前是否可用。
     *
     * @param command 待判断命令。
     * @return 命令可执行时返回 `true`。
     */
    fun isCommandEnabled(command: OnyxCommand): Boolean {
        val paneCommand = command.toPaneCommand()
        if (paneCommand != null) {
            return commands.isEnabled(paneCommand)
        }
        return when (command) {
            OnyxCommand.QuickOpen,
            OnyxCommand.ShowSearchPanel -> true
            else -> false
        }
    }

    /** 打开过滤输入框。 */
    fun openFilterInput() {
        commands.openFilterInput()
    }

    /** 关闭过滤输入框并把键盘焦点还给文件面板。 */
    fun closeFilterInput() {
        commands.closeFilterInput()
        ui.filterFocused = false
        focusRequester.requestFocus()
    }

    /** 用当前面板边界更新标签栏放置区域。 */
    fun reportTabDropZone() {
        val zone = ui.tabBarDropZone ?: return
        dragBindings.tabDropZones[state.paneId] = zone.copy(bounds = ui.paneBounds ?: zone.bounds)
    }

    /**
     * 注册文件放置区域。
     *
     * @param zone 文件放置区域。
     */
    fun updateFileDropZone(zone: FileDropZone) {
        dragBindings.fileDropZones[zone.key] = zone
    }

    /**
     * 展示文件条目的右键菜单，并启动平台菜单查询。
     *
     * @param entryId 右键命中的条目 ID。
     * @param entrySelected 命中条目是否已选中。
     * @param pointerPosition 菜单锚点位置。
     * @param readyEntries 当前面板已加载条目。
     */
    fun showEntryContextMenu(
        entryId: String,
        entrySelected: Boolean,
        pointerPosition: IntOffset,
        readyEntries: List<VFile>,
    ) {
        model.onActivate()
        ui.contextMenuVisible = false
        ui.contextMenuOffset = pointerPosition
        val targetIds = if (entrySelected && state.selectedEntryIds.isNotEmpty()) {
            state.selectedEntryIds
        } else {
            setOf(entryId)
        }
        ui.contextMenuEntryIds = targetIds
        ui.contextMenuSections = emptyList()
        val nextToken = ui.contextMenuQueryToken + 1
        ui.contextMenuQueryToken = nextToken
        platformActionLoader.load(
            targetEntries = readyEntries.filter { entry -> entry.id in targetIds },
            token = nextToken,
        )
        if (!entrySelected) dispatch(PaneIntent.SelectEntry(entryId))
    }

    /**
     * 展示文件列表空白区域的右键菜单。
     *
     * @param pointerPosition 菜单锚点位置。
     */
    fun showBlankContextMenu(pointerPosition: IntOffset) {
        ui.contextMenuVisible = false
        ui.contextMenuOffset = pointerPosition
        ui.contextMenuEntryIds = emptySet()
        ui.contextMenuSections = emptyList()
        ui.contextMenuQueryToken += 1
        ui.contextMenuVisible = true
    }
}

/**
 * 为当前组合构建面板运行时控制器，并保留需要跨重组维持的焦点与菜单状态。
 *
 * @param model 面板状态与动作模型。
 * @param dragBindings 标签及文件拖放绑定。
 * @param services 文件预览与路径服务。
 * @return 当前组合可使用的面板运行时控制器。
 */
@Composable
internal fun rememberPaneSurfaceRuntime(
    model: PaneSurfaceModel,
    dragBindings: PaneDragBindings,
    services: PaneSurfaceServices,
): PaneSurfaceRuntime {
    val ui = remember { PaneSurfaceUiState(model.state.inlineEditState != null) }
    val focusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val commands = PaneCommandController(
        state = model.state,
        component = model.component,
        executionContext = PaneCommandExecutionContext(model.canPaste, model.actions.canUndo, model.actions.canRedo),
        externalActions = PaneCommandExternalActions(
            onBeginCreateDirectory = model.actions.onBeginCreateDirectory,
            onCopySelection = model.actions.onCopySelection,
            onCutSelection = model.actions.onCutSelection,
            onPaste = model.actions.onPaste,
            onUndo = model.actions.onUndo,
            onRedo = model.actions.onRedo,
            onDeleteSelection = model.actions.onDeleteSelection,
            onToggleFavoriteLocation = model.actions.onToggleFavoriteLocation,
            onOpenSettings = model.actions.onOpenSettings,
        ),
    )
    val menuCommands = PaneContextMenuCommandController(
        paneCommandController = commands,
        externalActions = PaneContextMenuExternalActions(
            onBatchRename = model.actions.onBatchRename,
            onExtractSelection = model.actions.onExtractSelection,
            onExtractToDirectory = model.actions.onExtractToDirectory,
            onExtractSmart = model.actions.onExtractSmart,
            onCreateZipArchive = model.actions.onCreateZipArchive,
            onFileContextMenuCommand = model.actions.onFileContextMenuCommand,
            onOpenTerminal = model.actions.onOpenTerminal,
        ),
    )
    val platformActionLoader = PaneContextMenuPlatformActionLoader(
        coroutineScope = coroutineScope,
        actions = model.actions,
        onShowContextMenu = { ui.contextMenuVisible = true },
        isLatestToken = { token -> ui.contextMenuQueryToken == token },
        onSectionsLoaded = { sections -> ui.contextMenuSections = sections },
    )
    return PaneSurfaceRuntime(
        model,
        dragBindings,
        services,
        ui,
        focusRequester,
        filterFocusRequester,
        commands,
        menuCommands,
        platformActionLoader,
    )
}
