package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.SidebarTreeNode
import com.oruke.onyx.app.component.SidebarTreeNodeLoadState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.app.platform.QUICK_ACCESS_LOG_TAG
import com.oruke.onyx.app.platform.SystemQuickAccessService
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.StorageDeviceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * 侧边栏状态委托，负责目录树和操作系统快速访问位置的加载。
 *
 * @param scope 与根组件生命周期绑定的协程作用域。
 * @param fileRepository 统一文件查询仓库。
 * @param systemQuickAccessService 操作系统快速访问读取服务。
 * @param storageDevices 共享平台存储设备服务，用于在磁盘挂载或卸载时同步更新目录树盘符。
 * @param rootNodesProvider 宿主文件系统根目录构建函数。
 */
internal class SidebarDelegate(
    private val scope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val systemQuickAccessService: SystemQuickAccessService,
    private val storageDevices: StorageDeviceService? = null,
    private val rootNodesProvider: (Set<String>) -> List<SidebarTreeNode> = Companion::buildSidebarRootNodes,
) {
    /** 侧栏目录树的可变状态，初始为空并在后台加载盘符，避免网络盘或休眠盘阻塞启动主线程。 */
    private val _sidebarTreeState = MutableStateFlow(
        SidebarTreeState(roots = emptyList())
    )

    /** 侧栏目录树的只读状态。 */
    val sidebarTreeState: StateFlow<SidebarTreeState> = _sidebarTreeState.asStateFlow()

    /** 操作系统快速访问位置的可变状态。 */
    private val _systemQuickAccessLocations = MutableStateFlow(systemQuickAccessService.initialLocations())

    /** 操作系统快速访问位置的只读状态。 */
    val systemQuickAccessLocations: StateFlow<List<SystemQuickAccessLocation>> =
        _systemQuickAccessLocations.asStateFlow()

    init {
        scope.launch {
            refreshRootNodes()
        }
        if (storageDevices != null) {
            scope.launch {
                storageDevices.observe()
                    .map { result -> result.getOrNull()?.flatMap { it.locations }?.toSet().orEmpty() }
                    .distinctUntilChanged()
                    .collect { locations -> refreshRootNodes(locations) }
            }
        }
        scope.launch {
            refreshSystemQuickAccessLocations()
        }
    }

    /**
     * 重新读取宿主文件系统根目录并与当前目录树合并，新增已挂载盘符、移除已卸载盘符并保留既有节点的展开状态。
     *
     * @param deviceLocations 当前存储设备服务发现的挂载位置集合。
     */
    suspend fun refreshRootNodes(deviceLocations: Set<String> = emptySet()) {
        val freshRoots = withContext(Dispatchers.IO) { rootNodesProvider(deviceLocations) }
        _sidebarTreeState.update { currentState ->
            val existingByLocation = currentState.roots.associateBy { it.location }
            val mergedRoots = freshRoots.map { freshRoot ->
                existingByLocation[freshRoot.location] ?: freshRoot
            }
            if (mergedRoots == currentState.roots) {
                currentState
            } else {
                currentState.copy(roots = mergedRoots)
            }
        }
    }

    /**
     * 展开/折叠侧边栏节点。
     *
     * @param location 目标目录节点位置。
     */
    fun toggleNode(location: String) {
        val node = _sidebarTreeState.value.findNode(location) ?: return
        val expand = !node.expanded
        _sidebarTreeState.value = _sidebarTreeState.value.updateNode(location) { currentNode ->
            currentNode.copy(expanded = expand)
        }
        if (expand && node.loadState != SidebarTreeNodeLoadState.READY) {
            scope.launch {
                loadChildren(location)
            }
        }
    }

    /**
     * 重试加载失败的侧边栏节点。
     *
     * @param location 需要重新加载的目录节点位置。
     */
    fun retryNode(location: String) {
        scope.launch {
            loadChildren(location, forceReload = true)
        }
    }

    /**
     * 从平台服务刷新快速访问位置；失败时保留首帧已显示的用户主目录。
     */
    private suspend fun refreshSystemQuickAccessLocations() {
        systemQuickAccessService.loadLocations().fold(
            onSuccess = { locations -> _systemQuickAccessLocations.value = locations },
            onFailure = { failure ->
                OnyxLogger.warn(
                    tag = QUICK_ACCESS_LOG_TAG,
                    message = "系统快速访问位置刷新失败，保留用户主目录",
                    throwable = failure,
                )
            },
        )
    }

    /**
     * 加载指定目录节点的直接子目录并更新节点状态。
     *
     * @param location 待读取的目录节点位置。
     * @param forceReload 是否忽略已有 READY 或 LOADING 状态强制重试。
     */
    private suspend fun loadChildren(
        location: String,
        forceReload: Boolean = false,
    ) {
        val currentNode = _sidebarTreeState.value.findNode(location) ?: return
        if (!forceReload && currentNode.loadState == SidebarTreeNodeLoadState.LOADING) {
            return
        }
        if (!forceReload && currentNode.loadState == SidebarTreeNodeLoadState.READY) {
            return
        }

        _sidebarTreeState.value = _sidebarTreeState.value.updateNode(location) { node ->
            node.copy(
                expanded = true,
                loadState = SidebarTreeNodeLoadState.LOADING,
                loadError = null,
            )
        }

        fileRepository.list(location).fold(
            onSuccess = { entries ->
                val children = entries
                    .filter { entry -> entry.kind == VFileKind.DIRECTORY }
                    .sortedBy { entry -> entry.name.lowercase() }
                    .map { entry ->
                        SidebarTreeNode(
                            location = entry.location,
                            label = entry.name,
                            expanded = false,
                            loadState = SidebarTreeNodeLoadState.IDLE,
                        )
                    }
                _sidebarTreeState.value = _sidebarTreeState.value.updateNode(location) { node ->
                    node.copy(
                        loadState = SidebarTreeNodeLoadState.READY,
                        loadError = null,
                        children = children,
                    )
                }
            },
            onFailure = { failure ->
                _sidebarTreeState.value = _sidebarTreeState.value.updateNode(location) { node ->
                    node.copy(
                        loadState = SidebarTreeNodeLoadState.FAILURE,
                        loadError = failure.message,
                    )
                }
            },
        )
    }

    companion object {
        /**
         * 根据宿主文件系统根目录与存储设备挂载根路径构造侧栏节点。
         *
         * @param deviceLocations 当前存储设备服务发现的挂载位置集合。
         * @return 排序、去重后的文件系统根节点。
         */
        fun buildSidebarRootNodes(deviceLocations: Iterable<String> = emptyList()): List<SidebarTreeNode> {
            val fsRoots = FileSystems.getDefault()
                .rootDirectories
                .map { rootPath -> rootPath.normalize().toAbsolutePath() }
            val deviceRoots = deviceLocations.mapNotNull { location ->
                runCatching { Paths.get(location).normalize().toAbsolutePath() }
                    .getOrNull()
                    ?.takeIf { path -> path.parent == null }
            }
            return (fsRoots + deviceRoots)
                .distinct()
                .sortedBy { rootPath -> rootPath.toString() }
                .map { rootPath ->
                    SidebarTreeNode(
                        location = rootPath.toString().ifBlank { "/" },
                        label = rootPath.toString().ifBlank { "/" },
                        expanded = false,
                        loadState = SidebarTreeNodeLoadState.IDLE,
                    )
                }
        }
    }
}

/**
 * 在侧栏目录树中递归查找指定位置。
 *
 * @param location 目标目录位置。
 * @return 匹配节点；不存在时返回 null。
 */
internal fun SidebarTreeState.findNode(location: String): SidebarTreeNode? {
    fun List<SidebarTreeNode>.search(): SidebarTreeNode? {
        return firstNotNullOfOrNull { node ->
            node.takeIf { it.location == location } ?: node.children.search()
        }
    }

    return roots.search()
}

/**
 * 递归更新侧栏目录树中的指定节点。
 *
 * @param location 目标目录位置。
 * @param transform 节点状态转换函数。
 * @return 包含更新结果的新目录树状态。
 */
internal fun SidebarTreeState.updateNode(
    location: String,
    transform: (SidebarTreeNode) -> SidebarTreeNode,
): SidebarTreeState {
    fun List<SidebarTreeNode>.update(): List<SidebarTreeNode> {
        return map { node ->
            when {
                node.location == location -> transform(node)
                node.children.isNotEmpty() -> node.copy(children = node.children.update())
                else -> node
            }
        }
    }

    return copy(roots = roots.update())
}
