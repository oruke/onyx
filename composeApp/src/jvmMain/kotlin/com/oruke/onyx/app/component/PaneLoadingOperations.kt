package com.oruke.onyx.app.component

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 切换目录条目的树状内联展开状态。
 *
 * @param directoryLocation 目录 VFS 位置。
 */
internal fun DefaultPaneComponent.toggleInlineExpand(directoryLocation: String) {
    val result = mutableState.value.toggleInlineExpandState(directoryLocation)
    mutableState.value = result.state
    result.loadRequest?.let { request -> loadInlineExpandChildren(request.location, request.depth) }
}

/**
 * 加载内联展开目录的直接子项。
 *
 * @param location 目录 VFS 位置。
 * @param depth 目录树深度。
 */
private fun DefaultPaneComponent.loadInlineExpandChildren(location: String, depth: Int) {
    scope.launch {
        fileRepository.list(location).fold(
            onSuccess = { entries ->
                mutableState.value = mutableState.value.withInlineExpandChildren(location, depth, entries)
            },
            onFailure = {
                mutableState.value = mutableState.value.withInlineExpandFailure(location, depth)
            },
        )
    }
}

/**
 * 加载指定标签目录，并丢弃导航后到达的过期结果。
 *
 * @param tabId 标签 ID。
 * @param location 待读取 VFS 位置。
 */
@Suppress("TooGenericExceptionCaught")
internal fun DefaultPaneComponent.loadTab(tabId: String, location: String) {
    tabLoadJobs[tabId]?.cancel()
    tabLoadJobs[tabId] = scope.launch {
        try {
            val result = fileRepository.list(location)
            val currentTab = tab(tabId)
            if (currentTab == null || currentTab.location != location) return@launch
            result.fold(
                onSuccess = { entries -> applyLoadedEntries(tabId, entries) },
                onFailure = { failure -> applyLoadFailure(tabId, location, failure) },
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            applyLoadFailure(tabId, location, failure)
        } finally {
            tabLoadJobs.remove(tabId)
        }
    }
}

/**
 * 应用目录加载成功结果并消费待聚焦条目。
 *
 * @param tabId 标签 ID。
 * @param entries 已加载条目。
 */
private fun DefaultPaneComponent.applyLoadedEntries(
    tabId: String,
    entries: List<com.oruke.onyx.core.model.VFile>,
) {
    val focusName = pendingFocusEntryName.remove(tabId)
    val focusEntry = focusName?.let { name -> entries.firstOrNull { entry -> entry.name == name } }
    updateTab(tabId) { tab -> tab.withLoadedEntries(entries, focusEntry) }
}

/**
 * 将目录加载失败转换为可见面板状态，并按需请求远程认证。
 *
 * @param tabId 标签 ID。
 * @param location 加载位置。
 * @param failure 失败原因。
 */
private fun DefaultPaneComponent.applyLoadFailure(tabId: String, location: String, failure: Throwable) {
    OnyxLogger.error("PaneComponent", "目录加载失败: $location", failure)
    requestRemoteCredentialsIfSupported(failure)
    pendingFocusEntryName.remove(tabId)
    updateTab(tabId) { tab -> tab.withLoadFailure(failure.toI18nMessage()) }
}

/**
 * 将标签重置到加载态并重新读取当前位置。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.refreshActiveTab(tabId: String) {
    updateTab(tabId) { currentTab ->
        currentTab.navigateToState(currentTab.location, currentTab.title, recordHistory = false)
    }
    tab(tabId)?.let { currentTab -> loadTab(currentTab.id, currentTab.location) }
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
