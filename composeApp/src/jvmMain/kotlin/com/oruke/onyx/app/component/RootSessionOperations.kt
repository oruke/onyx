package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * 恢复设置与会话；恢复成功或用户明确修改设置后才启用自动持久化。
 *
 * @return 无返回值。
 */
internal suspend fun DefaultRootComponent.restorePersistedState() {
    var restoreError: I18nMessage? = null
    val initialLocation = launchConfiguration.initialLocation?.takeIf(String::isNotBlank)
    if (launchConfiguration.persistsMainSession) {
        val (settingsError, sessionResult) = coroutineScope {
            val settingsDeferred = async { restoreSettings() }
            val sessionDeferred = async { sessionRepository.loadSession() }
            settingsDeferred.await() to sessionDeferred.await()
        }
        restoreError = settingsError
        sessionResult.fold(
            onSuccess = { session ->
                if (session != null) {
                    applySession(session)
                } else {
                    applySettingsDefaults(refreshPrimaryPane = initialLocation == null)
                }
            },
            onFailure = { failure ->
                restoreError = restoreError ?: failure.toI18nMessage(MessageKey.MSG_RESTORE_SESSION_FAILED)
                applySettingsDefaults(refreshPrimaryPane = initialLocation == null)
            },
        )
    } else {
        applySettingsDefaults(refreshPrimaryPane = initialLocation == null)
    }
    if (initialLocation != null) {
        activePane.value = PaneId.PRIMARY
        primaryPane.openDirectory(initialLocation)
    }
    sessionRestoreState.value = restoreError?.let(SessionRestoreState::Failed) ?: SessionRestoreState.Ready
    if (restoreError == null) {
        // 只有完整恢复成功才写入启动后的快照，避免默认状态覆盖暂时不可读的旧数据。
        persistenceReady = true
        recordRecentLocations(listOf(primaryPane.state.value.location, secondaryPane.state.value.location))
        persistCurrentState()
    }
}

/**
 * 组件销毁前同步刷新尚未落盘的设置与会话，避免 500ms 防抖在关闭窗口时被取消而丢失尺寸或标签变更。
 */
internal fun DefaultRootComponent.flushPersistedStateOnDestroy() {
    if (!persistenceReady) return
    runBlocking {
        persistCurrentState()
    }
}

/**
 * 恢复设置并规范化 UI 缩放与窗口尺寸。
 *
 * @return 设置加载失败详情。
 */
private suspend fun DefaultRootComponent.restoreSettings(): I18nMessage? {
    var failureMessage: I18nMessage? = null
    settingsRepository.loadSettings().fold(
        onSuccess = { loadedSettings ->
            if (loadedSettings != null) {
                settings.value = loadedSettings.sanitizeRootSettings()
                synchronizeS3ConnectionConfigurations()
            }
        },
        onFailure = { failure ->
            failureMessage = failure.toI18nMessage(MessageKey.MSG_LOAD_SETTINGS_FAILED)
        },
    )
    return failureMessage
}

/** @param snapshot 待应用会话快照。 */
private fun DefaultRootComponent.applySession(snapshot: AppSessionSnapshot) {
    layoutMode.value = snapshot.layoutMode
    paneSplitFraction.value = snapshot.paneSplitFraction.coerceIn(
        MIN_PANE_SPLIT_FRACTION,
        MAX_PANE_SPLIT_FRACTION,
    )
    primaryPane.restoreSession(snapshot.primaryPane)
    secondaryPane.restoreSession(snapshot.secondaryPane)
    activePane.value = snapshot.activePane
}

/**
 * 应用设置中的默认布局与视图模式，并按需触发初始目录读取。
 *
 * @param refreshPrimaryPane 主面板无外部初始位置覆盖时是否立即读取默认目录。
 */
private fun DefaultRootComponent.applySettingsDefaults(refreshPrimaryPane: Boolean = true) {
    layoutMode.value = settings.value.defaultLayoutMode
    val defaultViewMode = settings.value.defaultViewMode
    primaryPane.applyDefaultDirectoryViewMode(defaultViewMode)
    secondaryPane.applyDefaultDirectoryViewMode(defaultViewMode)
    if (refreshPrimaryPane) {
        primaryPane.refresh()
    }
    secondaryPane.refresh()
}

/**
 * 持久化当前设置与会话快照。
 *
 * @return 无返回值。
 */
internal suspend fun DefaultRootComponent.persistCurrentState() {
    if (launchConfiguration.persistsMainSession) {
        sessionManager.persist(settings.value, buildSessionSnapshot())
    } else {
        sessionManager.saveSettings(settings.value)
    }
}

/** @return 当前根状态对应的会话快照。 */
private fun DefaultRootComponent.buildSessionSnapshot(): AppSessionSnapshot {
    return AppSessionSnapshot(
        layoutMode = layoutMode.value,
        paneSplitFraction = paneSplitFraction.value,
        activePane = activePane.value,
        primaryPane = primaryPane.toPaneSessionSnapshot(),
        secondaryPane = secondaryPane.toPaneSessionSnapshot(),
    )
}

/**
 * 将双面板位置写入最近位置设置。
 *
 * @param locations 当前面板位置。
 */
internal fun DefaultRootComponent.recordRecentLocations(locations: List<String>) {
    val nextSettings = settings.value.recordRecentLocations(
        locations = locations,
        isArchiveLocation = ArchiveService::isArchiveLocation,
    )
    if (nextSettings != settings.value) settings.value = nextSettings
}

/**
 * 请求指定面板的远程连接凭据。
 *
 * @param paneId 面板 ID。
 * @param error 认证错误。
 */
internal fun DefaultRootComponent.requestRemoteCredentials(paneId: PaneId, error: VfsProviderError) {
    remoteConnectionManager.requestRemoteCredentials(paneId, error)
}
