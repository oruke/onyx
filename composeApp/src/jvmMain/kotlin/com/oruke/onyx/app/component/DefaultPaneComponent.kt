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
    /** 新标签默认视图模式。 */
    internal val initialViewMode: ViewMode = ViewMode.DETAILS,
    /** 打开图片查看器的跨组件动作。 */
    internal val onOpenImageViewer: ((file: VFile, allImages: List<VFile>) -> Unit)? = null,
    /** 请求远程认证的跨组件动作。 */
    internal val onRemoteAuthenticationRequired: (PaneId, VfsProviderError) -> Unit = { _, _ -> },
    /** 文件重命名完成后的跨组件通知。 */
    internal val onFileRenamed: (source: VFile, renamed: VFile) -> Unit = { _, _ -> },
) : PaneComponent, ComponentContext by componentContext {
    /** 与组件生命周期绑定的主线程协程作用域。 */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { value ->
        lifecycle.doOnDestroy { value.cancel() }
    }

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

    /** 活动目录文件系统监听控制器。 */
    internal val fileWatcherController = PaneFileWatcherController(
        scope = scope,
        onChanged = { location ->
            activeTab()?.takeIf { tab -> tab.location == location }?.let { tab ->
                loadTab(tab.id, tab.location)
            }
        },
        onWatchDegraded = { location, failure ->
            activeTab()?.takeIf { tab -> tab.location == location }?.let { tab ->
                updateFailure(tab.id, com.oruke.onyx.core.model.PaneOperationFeedbackKind.WATCH_DEGRADED,
                    failure.toI18nMessage())
            }
        },
    )

    /** 每个标签当前执行的目录加载任务。 */
    internal val tabLoadJobs = mutableMapOf<String, Job>()

    /** 目录加载成功后需要自动聚焦的条目名称。 */
    internal val pendingFocusEntryName = mutableMapOf<String, String>()

    init {
        refresh()
        fileWatcherController.start(mutableState.value.location)
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
    override fun detachTab(tabId: String): TabSnapshot? = detachPaneTab(tabId)

    /**
     * 将标签快照插入当前面板。
     *
     * @param tabSnapshot 标签快照。
     * @param targetIndex 目标索引。
     */
    override fun attachTab(tabSnapshot: TabSnapshot, targetIndex: Int) {
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
}
