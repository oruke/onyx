package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SidebarTreeNode
import com.oruke.onyx.app.component.SidebarTreeNodeLoadState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * 侧边栏树状管理委托 — 负责侧边栏文件系统树的构建、展开、加载。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑，自持 SidebarTreeState。
 */
internal class SidebarDelegate(
    private val scope: CoroutineScope,
    private val fileRepository: FileRepository,
) {
    private val _sidebarTreeState = MutableStateFlow(
        SidebarTreeState(roots = buildSidebarRootNodes())
    )
    val sidebarTreeState: StateFlow<SidebarTreeState> = _sidebarTreeState.asStateFlow()

    /**
     * 展开/折叠侧边栏节点。
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
     */
    fun retryNode(location: String) {
        scope.launch {
            loadChildren(location, forceReload = true)
        }
    }

    /**
     * 确保指定路径在侧边栏中可见（展开所有祖先节点）。
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

// ── SidebarTreeState 扩展函数 ─────────────────────────────────────────────

internal fun SidebarTreeState.findNode(location: String): SidebarTreeNode? {
    fun List<SidebarTreeNode>.search(): SidebarTreeNode? {
        return firstNotNullOfOrNull { node ->
            node.takeIf { it.location == location } ?: node.children.search()
        }
    }

    return roots.search()
}

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

private fun normalizeTreeLocation(location: String): String? {
    return runCatching {
        Path.of(location).normalize().toAbsolutePath().toString().ifBlank { "/" }
    }.getOrNull()
}
