package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.archive.ArchiveService

/** 恢复设置与会话，并在完成后启用自动持久化。 */
internal suspend fun DefaultRootComponent.restorePersistedState() {
    var restoreError: I18nMessage? = restoreSettings()
    sessionRepository.loadSession().fold(
        onSuccess = { session ->
            if (session != null) {
                applySession(session)
            } else {
                applySettingsDefaults()
            }
        },
        onFailure = { failure ->
            restoreError = restoreError ?: failure.toI18nMessage(MessageKey.MSG_RESTORE_SESSION_FAILED)
            applySettingsDefaults()
        },
    )
    sessionRestoreState.value = restoreError?.let(SessionRestoreState::Failed) ?: SessionRestoreState.Ready
    persistenceReady = true
    recordRecentLocations(listOf(primaryPane.state.value.location, secondaryPane.state.value.location))
    persistCurrentState()
}

/**
 * 恢复设置并规范化 UI 缩放。
 *
 * @return 设置加载失败详情。
 */
private suspend fun DefaultRootComponent.restoreSettings(): I18nMessage? {
    var failureMessage: I18nMessage? = null
    settingsRepository.loadSettings().fold(
        onSuccess = { loadedSettings ->
            if (loadedSettings != null) {
                settings.value = loadedSettings.copy(
                    uiScale = loadedSettings.uiScale.coerceIn(MIN_UI_SCALE_PERCENT, MAX_UI_SCALE_PERCENT),
                )
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

/** 应用设置中的默认布局与视图模式。 */
private fun DefaultRootComponent.applySettingsDefaults() {
    layoutMode.value = settings.value.defaultLayoutMode
    val defaultViewMode = settings.value.defaultViewMode
    primaryPane.setViewMode(defaultViewMode)
    secondaryPane.setViewMode(defaultViewMode)
}

/** 持久化当前设置与会话快照。 */
internal suspend fun DefaultRootComponent.persistCurrentState() {
    sessionManager.persist(settings.value, buildSessionSnapshot())
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
