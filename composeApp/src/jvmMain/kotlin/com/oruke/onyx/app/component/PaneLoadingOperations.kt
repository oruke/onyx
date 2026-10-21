package com.oruke.onyx.app.component

import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 切换目录展开，并同步需要的子目录订阅。
 * @param directoryLocation 目录 VFS 位置。
 */
internal fun DefaultPaneComponent.toggleInlineExpand(directoryLocation: String) {
    val result = mutableState.value.toggleInlineExpandState(directoryLocation)
    mutableState.value = result.state
    synchronizeInlineDirectories()
}

/**
 * 为活动访问绑定共享目录数据，离开时释放订阅而非清空列表状态。
 * @param tabId 标签 ID。
 * @param location 目录位置。
 * @param force 是否强制底层校验。
 */
internal fun DefaultPaneComponent.loadTab(tabId: String, location: String, force: Boolean = false) {
    // collectLatest 的取消收尾可能晚于下一次导航，先同步释放订阅，避免快速返回时误用仍被引用的过期缓存。
    directorySubscriptions.values.forEach { it.close() }
    directorySubscriptions.clear()
    tabLoadJobs.values.forEach { it.cancel() }
    tabLoadJobs.clear()
    // 后台标签只保存轻量浏览状态，目录元数据由有界共享存储统一持有。
    tabs().filter { it.id != tabId && it.allEntries.isNotEmpty() }.forEach { inactive ->
        updateTab(inactive.id, derive = false) { value -> value.copy(allEntries = emptyList()).withTabState {
            it.copy(entriesState = PaneEntriesState.Idle)
        } }
    }
    val active = tab(tabId) ?: return
    val visitId = active.tabState.browserState.visitId
    navigationController.activate(active)
    if (com.oruke.onyx.app.filesystem.isComputerLocation(location)) {
        loadComputerLocation(tabId, visitId)
        return
    }
    tabLoadJobs[tabId] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val subscription = directories.subscribe(location)
        directorySubscriptions[tabId] = subscription
        if (force) directories.load(location, force = true)
        try {
            subscription.state.collectLatest { content ->
                applyDirectoryContent(tabId, location, visitId, content)
                content.failure?.let(::requestRemoteCredentialsIfSupported)
            }
        } finally {
            subscription.close()
            if (directorySubscriptions[tabId] === subscription) directorySubscriptions.remove(tabId)
            if (tabLoadJobs[tabId] === currentCoroutineContext()[Job]) tabLoadJobs.remove(tabId)
        }
    }
    synchronizeInlineDirectories()
}

/**
 * 从 SMB/WebDAV 认证错误中触发根组件凭据流程。
 *
 * @param failure 目录加载失败。
 */
private fun DefaultPaneComponent.requestRemoteCredentialsIfSupported(failure: Throwable) {
    val error = (failure as? VfsProviderException)?.error ?: return
    val authError = when (error) {
        is VfsProviderError.AuthenticationRejected -> error
        is VfsProviderError.AuthenticationRequired -> error
        else -> return
    }
    if (authError.protocol == VfsProtocol.SMB || authError.protocol == VfsProtocol.WEBDAV) {
        onRemoteAuthenticationRequired(paneId, authError)
    }
}
