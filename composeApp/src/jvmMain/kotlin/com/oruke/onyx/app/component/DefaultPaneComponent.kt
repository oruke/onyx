package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.TabSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.vfs.api.VfsProviderError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 默认文件面板组件，持有 Decompose 标签栈和面板状态流。
 *
 * 具体导航、选择、编辑、加载与标签操作由同包 operations 扩展负责。
 *
 * @param componentContext Decompose 组件上下文。
 */
internal class DefaultPaneComponent(
    componentContext: ComponentContext,
    /** 面板标识。 */
    internal val paneId: PaneId,
    /** 初始目录位置。 */
    initialLocation: String,
    /** 文件查询仓库。 */
    internal val fileRepository: FileRepository,
    /** 文件写入命令服务。 */
    internal val fileCommandService: FileCommandService,
    /** 系统文本剪贴板服务。 */
    internal val textClipboardService: TextClipboardService,
    /** 外部程序打开服务。 */
    internal val externalOpenService: ExternalOpenService,
    /** VFS 路径解析服务。 */
    internal val pathService: VfsPathService,
    /** 新建条目名称建议服务。 */
    internal val entryNameSuggestionService: EntryNameSuggestionService,
    /** 文件类型识别服务。 */
    internal val fileTypeService: FileTypeService,
    /** 压缩包条目临时提取打开服务。 */
    internal val archiveEntryOpenService: ArchiveEntryOpenService,
    /** 在读取归档内部文件前由根组件完成密码授权的回调。 */
    internal val onPrepareArchiveContentAccess: suspend (entry: VFile) -> Unit = {},
    /** 新标签默认视图模式。 */
    internal val initialViewMode: ViewMode = ViewMode.DETAILS,
    /** 打开图片查看器的跨组件动作。 */
    internal val onOpenImageViewer: ((file: VFile, allImages: List<VFile>) -> Unit)? = null,
    /** 在独立窗口打开目录的跨组件动作。 */
    internal val onOpenDirectoryInNewWindow: (String) -> Unit = {},
    /** 请求远程认证的跨组件动作。 */
    internal val onRemoteAuthenticationRequired: (PaneId, VfsProviderError) -> Unit = { _, _ -> },
    /** 文件重命名完成后的跨组件通知。 */
    internal val onFileRenamed: (source: VFile, renamed: VFile) -> Unit = { _, _ -> },
    /** 应用级共享目录数据，直接构造面板的测试可使用隔离实例。 */
    directoryStore: com.oruke.onyx.shared.filesystem.DirectoryContentStore? = null,
    /** 应用级设备服务；未注入时建立由当前组件关闭的隔离实例。 */
    storageDevices: com.oruke.onyx.vfs.api.StorageDeviceService? = null,
    /** 系统真实目录入口服务。 */
    internal val systemQuickAccessService: com.oruke.onyx.app.platform.SystemQuickAccessService =
        com.oruke.onyx.app.platform.JvmSystemQuickAccessService(),
) : PaneComponent, ComponentContext by componentContext {
    /** 当前面板通过统一接口访问设备，只有本地创建的实例由面板负责关闭。 */
    internal val storageDevices = storageDevices ?: com.oruke.onyx.app.platform.storage.JvmStorageDeviceService().also {
        lifecycle.doOnDestroy(it::close)
    }
    /** 与组件生命周期绑定的主线程协程作用域。 */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { value ->
        lifecycle.doOnDestroy { value.cancel() }
    }

    /** 共享目录内容与按引用计数管理的观察能力。 */
    internal val directories = directoryStore ?: com.oruke.onyx.shared.filesystem.DirectoryContentStore(
        fileRepository, scope, com.oruke.onyx.app.filesystem.JvmDirectoryObservationService(pathService),
    )

    /** 每个标签独立的访问历史和视口记录。 */
    internal val navigationController = com.oruke.onyx.app.component.delegate.PaneNavigationController {
        directories.cached(it)
    }

    /** 各标签离开时的内联展开位置及深度。 */
    internal val tabExpansions = mutableMapOf<String, Map<String, Int>>()

    /** 当前展开目录的观察与加载任务。 */
    internal val inlineDirectoryJobs = mutableMapOf<String, Job>()

    /** 启动时创建的首个标签。 */
    private val initialTab = createTabState(
        location = pathService.normalizeLocation(initialLocation),
        defaultViewMode = initialViewMode,
    )

    /** 面板公开状态的可变来源。 */
    internal val mutableState = MutableStateFlow(
        initialTab.toPaneState(
            paneId = paneId,
            activeTabId = initialTab.id,
        )
    )

    /** 面板公开状态流。 */
    override val state: StateFlow<PaneState> = mutableState.asStateFlow()

    /** 标签显示顺序的可变来源。 */
    internal val mutableTabOrder = MutableStateFlow(listOf(initialTab.id))

    /** 标签显示顺序。 */
    override val tabOrder: StateFlow<List<String>> = mutableTabOrder.asStateFlow()

    /** 显示顺序标签状态列表的可变来源。 */
    internal val mutableTabStates = MutableStateFlow(listOf(initialTab))

    /** 显示顺序的标签状态列表。 */
    override val tabStates: StateFlow<List<PaneTabState>> = mutableTabStates.asStateFlow()

    /** Decompose 标签栈导航器。 */
    internal val tabNavigation = StackNavigation<TabConfig>()

    /** Decompose 标签子组件栈。 */
    override val tabStack: Value<ChildStack<TabConfig, TabComponent>> = childStack(
        source = tabNavigation,
        serializer = null,
        initialStack = { listOf(initialTab.toTabSnapshot().toTabConfig()) },
        key = "PaneTabStack-$paneId",
        childFactory = { config, childContext ->
            DefaultTabComponent(
                componentContext = childContext,
                config = config,
                pathService = pathService,
            )
        },
    )

    /** 每个标签当前执行的目录加载任务。 */
    internal val tabLoadJobs = mutableMapOf<String, Job>()

    /** 当前目录订阅独立于取消中的加载任务，导航时同步释放引用。 */
    internal val directorySubscriptions = mutableMapOf<String, com.oruke.onyx.shared.filesystem.DirectorySubscription>()

    /** 本面板仍在执行的设备操作，切换标签不能丢失忙碌状态或启动重复操作。 */
    internal val deviceOperationJobs = mutableMapOf<String, Job>()

    /** 目录加载成功后需要自动聚焦的条目名称。 */
    internal val pendingFocusEntryName = mutableMapOf<String, String>()

    init {
        refresh()
    }

    /**
     * 将面板 intent 交给分类路由器处理。
     *
     * @param intent 面板 intent。
     */
    override fun dispatch(intent: PaneIntent) {
        dispatchPaneIntent(intent)
    }

    /**
     * 从当前面板移出标签。
     *
     * @param tabId 标签 ID。
     * @return 被移出的标签快照。
     */
    override fun detachTab(tabId: String): PaneTabTransfer? = detachPaneTab(tabId)

    /**
     * 将标签快照插入当前面板。
     *
     * @param tabSnapshot 标签快照。
     * @param targetIndex 目标索引。
     */
    override fun attachTab(tabSnapshot: PaneTabTransfer, targetIndex: Int) {
        attachPaneTab(tabSnapshot, targetIndex)
    }

    /**
     * 恢复当前面板会话。
     *
     * @param snapshot 面板会话快照。
     */
    override fun restoreSession(snapshot: PaneSessionSnapshot) {
        restorePaneSession(snapshot)
    }

    /**
     * 通过组件状态捕获移除命令上下文。
     * @param entries 本次目标条目。
     * @return 匹配当前选择的访问上下文。
     */
    override fun captureRemoval(entries: List<VFile>): PaneRemovalContext? = captureRemovalContext(entries)

    /**
     * 把确认的移除结果交给浏览状态续接流程。
     * @param context 发起上下文。
     * @param removedIds 已消失目标。
     */
    override fun completeRemoval(context: PaneRemovalContext, removedIds: Set<String>) {
        applyRemovalCompletion(context, removedIds)
    }
}
