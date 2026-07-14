package com.oruke.onyx.app.component

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.oruke.onyx.app.component.delegate.ArchiveActionDelegate
import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.app.component.delegate.FileActionDelegateCallbacks
import com.oruke.onyx.app.component.delegate.FileOperationHistoryDelegate
import com.oruke.onyx.app.component.delegate.FileTransferDelegate
import com.oruke.onyx.app.component.delegate.RootFileOperationHistoryFacade
import com.oruke.onyx.app.component.delegate.RootRemoteConnectionManager
import com.oruke.onyx.app.component.delegate.RootSearchDelegate
import com.oruke.onyx.app.component.delegate.SidebarDelegate
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneRoleState
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.vfs.api.FileContextMenuRequest
import com.oruke.onyx.vfs.api.FileContextMenuSection
import com.oruke.onyx.vfs.api.OpenWithApp
import com.oruke.onyx.vfs.api.SystemMenuAction
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** 根状态写入持久层前的防抖时长。 */
private const val ROOT_STATE_SAVE_DEBOUNCE_MS = 500L

/** 双面板分割条最小比例。 */
internal const val MIN_PANE_SPLIT_FRACTION = 0.18f

/** 双面板分割条最大比例。 */
internal const val MAX_PANE_SPLIT_FRACTION = 0.82f

/**
 * 默认根组件，负责根状态流、Decompose 子面板和业务委托装配。
 *
 * @param componentContext Decompose 组件上下文。
 * @param dependencies 类型化根组件依赖。
 */
@OptIn(FlowPreview::class)
internal class DefaultRootComponent(
    componentContext: ComponentContext,
    internal val dependencies: DefaultRootDependencies,
) : RootComponent, ComponentContext by componentContext {
    /** 文件查询仓库。 */
    internal val fileRepository = dependencies.files.fileRepository
    /** 文件写入命令服务。 */
    internal val fileCommandService = dependencies.files.fileCommandService
    /** 系统文本剪贴板服务。 */
    internal val textClipboardService = dependencies.files.textClipboardService
    /** 回收站服务。 */
    internal val trashService = dependencies.files.trashService
    /** 外部程序打开服务。 */
    internal val externalOpenService = dependencies.files.externalOpenService
    /** 压缩包服务。 */
    internal val archiveService = dependencies.files.archiveService
    /** 文件类型识别服务。 */
    internal val fileTypeService = dependencies.files.fileTypeService
    /** 压缩包条目打开服务。 */
    internal val archiveEntryOpenService = dependencies.files.archiveEntryOpenService
    /** 新建条目名称建议服务。 */
    internal val entryNameSuggestionService = dependencies.files.entryNameSuggestionService
    /** 设置仓库。 */
    internal val settingsRepository = dependencies.platform.settingsRepository
    /** 会话仓库。 */
    internal val sessionRepository = dependencies.platform.sessionRepository
    /** 系统打开方式服务。 */
    internal val openWithService = dependencies.platform.openWithService
    /** 系统菜单服务。 */
    internal val systemMenuService = dependencies.platform.systemMenuService
    /** 文件上下文菜单服务。 */
    internal val fileContextMenuService = dependencies.platform.fileContextMenuService
    /** 外部文件拖放服务。 */
    internal val externalFileDragService = dependencies.platform.externalFileDragService
    /** VFS 路径服务。 */
    internal val pathService = dependencies.platform.pathService
    /** VFS Provider 注册表。 */
    internal val providerRegistry = dependencies.platform.providerRegistry
    /** 终端启动服务。 */
    internal val terminalLauncherService = dependencies.platform.terminalLauncherService
    /** 操作系统快速访问位置读取服务。 */
    internal val systemQuickAccessService = dependencies.platform.systemQuickAccessService
    /** 文本预览服务。 */
    internal val previewService = dependencies.inspection.previewService
    /** 文件哈希服务。 */
    internal val fileHashService = dependencies.inspection.fileHashService
    /** 压缩包信息服务。 */
    internal val archiveInfoService = dependencies.inspection.archiveInfoService
    /** 缩略图服务。 */
    internal val thumbnailService = dependencies.inspection.thumbnailService
    /** 图片元数据服务。 */
    internal val imageMetadataService = dependencies.inspection.imageMetadataService
    /** 远程连接测试服务。 */
    internal val connectionTestService = dependencies.inspection.connectionTestService
    /** 远程认证存储。 */
    internal val remoteAuthStore = dependencies.inspection.remoteAuthStore
    /** 运行期 S3 Endpoint 配置仓库。 */
    internal val s3ConnectionRepository = dependencies.inspection.s3ConnectionRepository
    /** 文件集合用例。 */
    internal val fileCollectionUseCase = dependencies.search.fileCollectionUseCase
    /** 文件内容搜索服务。 */
    internal val fileContentSearchService = dependencies.search.fileContentSearchService
    /** 后台任务编排器。 */
    internal val taskOrchestrator = dependencies.delegates.taskOrchestrator
    /** 文件剪贴板管理器。 */
    internal val clipboardManager = dependencies.delegates.clipboardManager
    /** 图片查看器控制器。 */
    internal val imageViewerController = dependencies.delegates.imageViewerController
    /** 设置与会话持久化管理器。 */
    internal val sessionManager = dependencies.delegates.sessionManager

    /** 与组件生命周期绑定的主线程协程作用域。 */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { value ->
        lifecycle.doOnDestroy { value.cancel() }
    }

    /** 主面板组件。 */
    override val primaryPane: PaneComponent = createPaneComponent(PaneId.PRIMARY, "primaryPane")

    /** 次面板组件。 */
    override val secondaryPane: PaneComponent = createPaneComponent(PaneId.SECONDARY, "secondaryPane")

    /** 当前布局模式。 */
    internal val layoutMode = MutableStateFlow(PaneLayoutMode.DUAL_VERTICAL)
    /** 双面板分割比例。 */
    internal val paneSplitFraction = MutableStateFlow(0.5f)
    /** 当前活动面板。 */
    internal val activePane = MutableStateFlow(PaneId.PRIMARY)
    /** 当前应用设置。 */
    internal val settings = MutableStateFlow(OnyxSettings())
    /** 会话恢复状态。 */
    internal val sessionRestoreState = MutableStateFlow<SessionRestoreState>(SessionRestoreState.Loading)
    /** 当前根对话框。 */
    internal val dialogState = MutableStateFlow<RootDialogState?>(null)
    /** 是否显示预览面板。 */
    internal val showPreviewPane = MutableStateFlow(false)
    /** 图片查看器状态。 */
    override val imageViewerState: StateFlow<ImageViewerState> = imageViewerController.state
    /** 首次恢复完成后才允许自动持久化。 */
    internal var persistenceReady = false

    /** 侧栏目录树委托。 */
    internal val sidebarDelegate = SidebarDelegate(scope, fileRepository, systemQuickAccessService)

    /** 文件传输委托。 */
    internal val fileTransferDelegate = FileTransferDelegate(
        scope = scope,
        fileCommandService = fileCommandService,
        fileRepository = fileRepository,
        taskOrchestrator = taskOrchestrator,
        clipboardManager = clipboardManager,
        dialogState = dialogState,
        pathService = pathService,
        providerRegistry = providerRegistry,
        onRefreshAllPanes = ::refreshAllPanes,
        onMoveSucceeded = ::recordMoveOperation,
    )

    /** 压缩包动作委托。 */
    internal val archiveActionDelegate = ArchiveActionDelegate(
        archiveService = archiveService,
        taskOrchestrator = taskOrchestrator,
        dialogState = dialogState,
        onRefreshAllPanes = ::refreshAllPanes,
    )

    /** 文件创建、删除与批量重命名委托。 */
    internal val fileActionDelegate = FileActionDelegate(
        fileCommandService = fileCommandService,
        trashService = trashService,
        taskOrchestrator = taskOrchestrator,
        dialogState = dialogState,
        callbacks = FileActionDelegateCallbacks(
            onRefreshAllPanes = ::refreshAllPanes,
            onRefreshPane = { paneId -> paneComponent(paneId).refresh() },
            getPaneState = ::paneState,
            onBatchRenameSucceeded = ::recordBatchRenameOperation,
            onTrashDeleteSucceeded = ::recordTrashDeleteOperation,
        ),
    )

    /** 文件操作历史委托。 */
    internal val fileOperationHistoryDelegate = FileOperationHistoryDelegate(
        fileCommandService,
        fileRepository,
        trashService,
    )

    /** 根组件文件操作历史门面。 */
    internal val fileOperationHistoryFacade = RootFileOperationHistoryFacade(
        scope = scope,
        delegate = fileOperationHistoryDelegate,
        refreshAllPanes = ::refreshAllPanes,
        showOperationFailure = ::showActivePaneOperationFailure,
        activePane = { activePane.value },
        paneState = ::paneState,
        requestTransferSelectedToDirectory = ::requestTransferSelectedToDirectory,
    )

    /** 文件搜索用例。 */
    private val fileSearchUseCase = FileSearchUseCase(fileRepository, fileContentSearchService, providerRegistry)

    /** 搜索面板委托。 */
    internal val searchDelegate = RootSearchDelegate(
        scope = scope,
        fileSearchUseCase = fileSearchUseCase,
        fileCollectionUseCase = fileCollectionUseCase,
        paneState = ::paneState,
        activatePane = ::activatePane,
        paneComponent = ::paneComponent,
        getActivePane = { activePane.value },
    )

    /** 远程连接和认证委托。 */
    internal val remoteConnectionManager = RootRemoteConnectionManager(
        scope = scope,
        connectionTestService = connectionTestService,
        remoteAuthStore = remoteAuthStore,
        dialogState = dialogState,
        remoteConnections = { settings.value.remoteConnections },
        replaceRemoteConnections = { connections ->
            updateSettings(settings.value.copy(remoteConnections = connections))
        },
        paneState = ::paneState,
        paneComponent = ::paneComponent,
        openLocationInActivePane = ::openLocationInActivePane,
    )

    /** 根状态的可变来源。 */
    internal val mutableState = MutableStateFlow(initialRootState())

    /** 根组件公开状态流。 */
    override val state: StateFlow<RootState> = mutableState.asStateFlow()

    init {
        observeRootState()
        observePersistence()
        observeRecentLocations()
        observeSidebarLocation()
    }

    /**
     * 派发根组件 intent。
     *
     * @param intent 待处理 intent。
     */
    override fun dispatch(intent: RootIntent) {
        dispatchRootIntent(intent)
    }

    /** @param entry 文件条目。 @return 当前条目可用的打开方式应用。 */
    override suspend fun listOpenWithApps(entry: VFile): List<OpenWithApp> = openWithService.listApps(entry)

    /** @param entry 文件条目。 @return 当前条目是否支持打开方式菜单。 */
    override fun supportsOpenWith(entry: VFile): Boolean = openWithService.supports(entry)

    /** @param entries 文件条目集合。 @return 当前条目集合可用的系统菜单动作。 */
    override suspend fun listSystemMenuActions(entries: List<VFile>): List<SystemMenuAction> {
        return systemMenuService.listActions(entries)
    }

    /** @param entry 文件条目。 @return 当前条目是否支持上下文打开方式。 */
    override fun supportsContextMenuOpenWith(entry: VFile): Boolean = fileContextMenuService.supportsOpenWith(entry)

    /** @param request 文件上下文菜单请求。 @return 合并后的文件上下文菜单分组。 */
    override suspend fun listContextMenuSections(request: FileContextMenuRequest): List<FileContextMenuSection> {
        return fileContextMenuService.listSections(request)
    }

    /** @param entries 待拖放文件。 @return 外部拖放文件已准备完成时返回 `true`。 */
    override fun prepareExternalDrag(entries: List<VFile>): Boolean {
        return externalFileDragService.preparePendingFiles(entries)
    }

    /** @param fileName 文件名。 @return 文件名是否属于可浏览压缩包。 */
    override fun isArchiveFileName(fileName: String): Boolean = fileTypeService.isArchiveFileName(fileName)

    /** @param fileName 文件名。 @return 文件名是否属于图片。 */
    override fun isImageFileName(fileName: String): Boolean = fileTypeService.isImageFileName(fileName)

    /** @param fileName 文件名。 @return 文件名是否支持文本预览。 */
    override fun isTextPreviewFileName(fileName: String): Boolean = fileTypeService.isTextPreviewFileName(fileName)

    /** @param location VFS 位置。 @return VFS 位置的短显示标签。 */
    override fun locationLabel(location: String): String = pathService.label(location)

    /** @param location VFS 位置。 @return VFS 位置的面包屑。 */
    override fun buildBreadcrumbs(location: String): List<VfsBreadcrumb> = pathService.buildBreadcrumbs(location)

    /**
     * @param sourceLocation 来源位置。
     * @param targetLocation 目标位置。
     * @return 源和目标位置之间适用的传输操作。
     */
    override fun resolveTransferOperation(sourceLocation: String, targetLocation: String) =
        pathService.resolveTransferOperation(sourceLocation, targetLocation)

    /** @param request 远程连接测试请求。 @return 远程连接测试结果。 */
    override suspend fun testRemoteConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        return connectionTestService.testConnection(request)
    }

    /** @param request 文本预览请求。 @return 文本预览结果。 */
    override suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult {
        return previewService.loadTextPreview(request)
    }

    /** @param request 文件哈希请求。 @return 文件哈希结果。 */
    override suspend fun readFileHash(request: FileHashRequest): FileHashResult = fileHashService.readHash(request)

    /** @param request 压缩包信息请求。 @return 压缩包信息。 */
    override suspend fun readArchiveInfo(request: ArchiveInfoRequest): ArchiveInfoResult {
        return archiveInfoService.readInfo(request)
    }

    /**
     * @param location 文件位置。
     * @param maxDimension 最大像素边长。
     * @return 普通文件缩略图。
     */
    override suspend fun loadThumbnail(location: String, maxDimension: Int): ImageBitmap? {
        return thumbnailService.loadThumbnail(location, maxDimension)
    }

    /**
     * @param location 压缩包位置。
     * @param maxDimension 最大像素边长。
     * @return 压缩包内容缩略图。
     */
    override suspend fun loadArchiveThumbnail(location: String, maxDimension: Int): ImageBitmap? {
        return thumbnailService.loadArchiveThumbnail(location, maxDimension)
    }

    /** @param entry 图片条目。 @return 图片像素尺寸。 */
    override suspend fun readImageSize(entry: VFile): IntSize? = imageMetadataService.readImageSize(entry)

    /** 观察各根状态源并生成单一 RootState。 */
    private fun observeRootState() {
        scope.launch {
            val layoutFlow = combine(layoutMode, paneSplitFraction, activePane, showPreviewPane) {
                    mode, fraction, pane, preview -> LayoutSlice(mode, fraction, pane, preview)
            }
            val paneFlow = combine(primaryPane.state, secondaryPane.state) { primary, secondary ->
                primary to secondary
            }
            val contextFlow = rootContextFlow()
            combine(layoutFlow, paneFlow, contextFlow) { layout, panes, context ->
                context.toRootState(layout, panes)
            }.collect { combinedState -> mutableState.value = combinedState }
        }
    }

    /** @return 侧栏、设置、对话框、任务和历史组成的上下文流。 */
    private fun rootContextFlow() = combine(
        combine(
            sidebarDelegate.sidebarTreeState,
            sidebarDelegate.systemQuickAccessLocations,
            settings,
            sessionRestoreState,
        ) { sidebarTree, quickAccessLocations, value, restore ->
            SidebarContextSlice(sidebarTree, quickAccessLocations, value, restore)
        },
        combine(
            dialogState,
            clipboardManager.clipboard,
            taskOrchestrator.tasks,
            searchDelegate.searchState,
            fileOperationHistoryDelegate.state,
        ) { dialog, clipboard, tasks, search, history ->
            RuntimeContextSlice(dialog, clipboard != null, tasks, search, history.toRootOperationHistoryState())
        },
    ) { sidebar, runtime ->
        ContextSlice(
            sidebarTreeState = sidebar.sidebarTreeState,
            systemQuickAccessLocations = sidebar.systemQuickAccessLocations,
            settings = sidebar.settings,
            sessionRestoreState = sidebar.sessionRestoreState,
            runtime = runtime,
        )
    }

    /** 观察可持久化状态并防抖保存。 */
    private fun observePersistence() {
        scope.launch {
            combine(layoutMode, paneSplitFraction, activePane, primaryPane.state, secondaryPane.state, settings) {
                _ -> Unit
            }.debounce(ROOT_STATE_SAVE_DEBOUNCE_MS).collect {
                if (persistenceReady) persistCurrentState()
            }
        }
        scope.launch { restorePersistedState() }
    }

}

/**
 * 创建指定角色的文件面板。
 *
 * @param paneId 面板角色。
 * @param childKey Decompose 子组件键。
 * @return 文件面板组件。
 */
private fun DefaultRootComponent.createPaneComponent(paneId: PaneId, childKey: String): PaneComponent {
    return DefaultPaneComponent(
        componentContext = childContext(childKey),
        paneId = paneId,
        initialLocation = fileRepository.defaultLocation(),
        fileRepository = fileRepository,
        fileCommandService = fileCommandService,
        textClipboardService = textClipboardService,
        externalOpenService = externalOpenService,
        pathService = pathService,
        entryNameSuggestionService = entryNameSuggestionService,
        fileTypeService = fileTypeService,
        archiveEntryOpenService = archiveEntryOpenService,
        onOpenImageViewer = ::openImageViewer,
        onRemoteAuthenticationRequired = ::requestRemoteCredentials,
        onFileRenamed = ::recordRenameOperation,
    )
}

/** @return 当前各状态源组成的初始根状态。 */
private fun DefaultRootComponent.initialRootState(): RootState {
    return RootState(
        layoutMode = layoutMode.value,
        paneSplitFraction = paneSplitFraction.value,
        activePane = activePane.value,
        paneRoles = PaneRoleState.fromSource(activePane.value),
        primaryPane = primaryPane.state.value,
        secondaryPane = secondaryPane.state.value,
        sidebarTreeState = sidebarDelegate.sidebarTreeState.value,
        systemQuickAccessLocations = sidebarDelegate.systemQuickAccessLocations.value,
        settings = settings.value,
        sessionRestoreState = sessionRestoreState.value,
        dialogState = dialogState.value,
        canPaste = clipboardManager.canPaste,
        tasks = taskOrchestrator.tasks.value,
        showPreviewPane = showPreviewPane.value,
        searchState = searchDelegate.searchState.value,
        operationHistoryState = fileOperationHistoryDelegate.state.value.toRootOperationHistoryState(),
    )
}

/** 观察双面板位置并记录最近位置。 */
private fun DefaultRootComponent.observeRecentLocations() {
    scope.launch {
        combine(primaryPane.state, secondaryPane.state) { primary, secondary ->
            listOf(primary.location, secondary.location)
        }.collect { locations ->
            if (persistenceReady) recordRecentLocations(locations)
        }
    }
}

/** 观察活动面板位置并确保侧栏树节点可见。 */
private fun DefaultRootComponent.observeSidebarLocation() {
    scope.launch {
        combine(activePane, primaryPane.state, secondaryPane.state) { pane, primary, secondary ->
            if (pane == PaneId.PRIMARY) primary.location else secondary.location
        }.collect(sidebarDelegate::ensureLocationVisible)
    }
}

/** 布局状态切片。 */
private data class LayoutSlice(
    /** 布局模式。 */
    val mode: PaneLayoutMode,
    /** 面板分割比例。 */
    val fraction: Float,
    /** 活动面板。 */
    val activePane: PaneId,
    /** 是否显示预览面板。 */
    val showPreviewPane: Boolean,
)

/** 侧栏平台位置、目录树与持久化上下文切片。 */
private data class SidebarContextSlice(
    /** 侧栏目录树状态。 */
    val sidebarTreeState: SidebarTreeState,
    /** 操作系统快速访问位置。 */
    val systemQuickAccessLocations: List<SystemQuickAccessLocation>,
    /** 当前应用设置。 */
    val settings: OnyxSettings,
    /** 会话恢复状态。 */
    val sessionRestoreState: SessionRestoreState,
)

/** 根上下文状态切片。 */
private data class ContextSlice(
    /** 侧栏树状态。 */
    val sidebarTreeState: SidebarTreeState,
    /** 操作系统快速访问位置。 */
    val systemQuickAccessLocations: List<SystemQuickAccessLocation>,
    /** 当前设置。 */
    val settings: OnyxSettings,
    /** 会话恢复状态。 */
    val sessionRestoreState: SessionRestoreState,
    /** 运行期上下文切片。 */
    val runtime: RuntimeContextSlice,
)

/** 对话框、任务、搜索与历史状态切片。 */
private data class RuntimeContextSlice(
    /** 当前对话框。 */
    val dialogState: RootDialogState?,
    /** 是否允许粘贴。 */
    val canPaste: Boolean,
    /** 后台任务。 */
    val tasks: List<BackgroundTask>,
    /** 搜索状态。 */
    val searchState: SearchPanelState,
    /** 文件操作历史状态。 */
    val operationHistoryState: OperationHistoryState,
)

/**
 * 将上下文切片与布局、面板状态合成为根状态。
 *
 * @param layout 布局切片。
 * @param panes 主次面板状态。
 * @return 根状态。
 */
private fun ContextSlice.toRootState(layout: LayoutSlice, panes: Pair<PaneState, PaneState>): RootState {
    return RootState(
        layoutMode = layout.mode,
        paneSplitFraction = layout.fraction,
        activePane = layout.activePane,
        paneRoles = PaneRoleState.fromSource(layout.activePane),
        primaryPane = panes.first,
        secondaryPane = panes.second,
        sidebarTreeState = sidebarTreeState,
        systemQuickAccessLocations = systemQuickAccessLocations,
        settings = settings,
        sessionRestoreState = sessionRestoreState,
        dialogState = runtime.dialogState,
        canPaste = runtime.canPaste,
        tasks = runtime.tasks,
        showPreviewPane = layout.showPreviewPane,
        searchState = runtime.searchState,
        operationHistoryState = runtime.operationHistoryState,
    )
}
