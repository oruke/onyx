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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * 侧边栏状态委托，负责目录树和操作系统快速访问位置的加载。
 *
 * @param scope 与根组件生命周期绑定的协程作用域。
 * @param fileRepository 统一文件查询仓库。
 * @param systemQuickAccessService 操作系统快速访问读取服务。
 */
internal class SidebarDelegate(
    private val scope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val systemQuickAccessService: SystemQuickAccessService,
) {
    /** 侧栏目录树的可变状态。 */
    private val _sidebarTreeState = MutableStateFlow(
        SidebarTreeState(roots = buildSidebarRootNodes())
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
            refreshSystemQuickAccessLocations()
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
     * 确保指定路径在侧边栏中可见（展开所有祖先节点）。
     *
     * @param location 需要在目录树中展开的本地位置。
     */
    suspend fun ensureLocationVisible(location: String) {
        val normalizedLocation = normalizeTreeLocation(location) ?: return
        buildAncestorLocations(normalizedLocation)
            .dropLast(1)
            .forEach { ancestorLocation ->
                val node = _sidebarTreeState.value.findNode(ancestorLocation) ?: return@forEach
                if (!node.expanded) {
                    _sidebarTreeState.value = _sidebarTreeState.value.updateNode(ancestorLocation) { currentNode ->
                        currentNode.copy(expanded = true)
                    }
                }
                if (node.loadState != SidebarTreeNodeLoadState.READY) {
                    loadChildren(ancestorLocation)
                }
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
         * 根据宿主文件系统根目录构造侧栏初始节点。
         *
         * @return 排序、去重后的文件系统根节点。
         */
        fun buildSidebarRootNodes(): List<SidebarTreeNode> {
            return FileSystems.getDefault()
                .rootDirectories
                .map { rootPath -> rootPath.normalize().toAbsolutePath() }
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

/**
 * 构造从文件系统根目录到目标位置的完整祖先链。
 *
 * @param location 目标本地目录位置。
 * @return 按根目录到目标目录顺序排列的位置列表。
 */
private fun buildAncestorLocations(location: String): List<String> {
    val path = Path.of(location).normalize().toAbsolutePath()
    val chain = ArrayDeque<String>()
    var current: Path? = path
    while (current != null) {
        chain.addFirst(current.toString().ifBlank { "/" })
        current = current.parent
    }
    return chain.toList()
}

/**
 * 将本地位置规范化为目录树使用的绝对路径。
 *
 * @param location 待规范化位置。
 * @return 规范化位置；非法路径返回 null。
 */
private fun normalizeTreeLocation(location: String): String? {
    return runCatching {
        Path.of(location).normalize().toAbsolutePath().toString().ifBlank { "/" }
    }.getOrNull()
}
