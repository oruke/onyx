package com.oruke.onyx.ui

import com.oruke.onyx.app.filesystem.FileContextMenuRequest
import com.oruke.onyx.app.filesystem.FileContextMenuSection
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 面板系统菜单动作加载器，负责异步查询平台右键菜单并按 token 合并回当前菜单。
 *
 * @param coroutineScope 与 PaneSurface 生命周期一致的协程作用域。
 * @param actions 面板动作集合，提供系统菜单查询入口。
 * @param onShowContextMenu 展示菜单的回调。
 * @param isLatestToken 判断查询结果是否仍属于当前菜单。
 * @param onSectionsLoaded 平台菜单分组加载完成后的回调。
 */
internal class PaneContextMenuPlatformActionLoader(
    private val coroutineScope: CoroutineScope,
    private val actions: PaneActions,
    private val onShowContextMenu: () -> Unit,
    private val isLatestToken: (Int) -> Boolean,
    private val onSectionsLoaded: (List<FileContextMenuSection>) -> Unit,
) {
    /**
     * 加载指定条目的平台右键菜单动作。
     *
     * @param targetEntries 当前右键菜单作用的条目。
     * @param token 当前菜单查询 token。
     */
    fun load(
        targetEntries: List<VFile>,
        token: Int,
    ) {
        val contextMenuQuery = actions.onQueryContextMenuSections
        if (targetEntries.isEmpty() || contextMenuQuery == null) {
            onShowContextMenu()
            return
        }
        onShowContextMenu()
        val sectionsDeferred: Deferred<List<FileContextMenuSection>> = coroutineScope.async(Dispatchers.IO) {
            runCatching {
                contextMenuQuery.invoke(FileContextMenuRequest(targetEntries))
            }.getOrDefault(emptyList())
        }

        coroutineScope.launch {
            val sections = withTimeoutOrNull(CONTEXT_MENU_PLATFORM_ACTION_TIMEOUT_MS) {
                sectionsDeferred.await()
            }
            if (sections == null) {
                sectionsDeferred.cancel()
                return@launch
            }
            if (isLatestToken(token)) {
                onSectionsLoaded(sections)
            }
        }
    }
}

private const val CONTEXT_MENU_PLATFORM_ACTION_TIMEOUT_MS = 2_200L
