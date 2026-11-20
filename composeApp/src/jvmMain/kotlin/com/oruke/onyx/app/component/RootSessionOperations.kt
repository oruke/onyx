package com.oruke.onyx.app.component

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 纳秒转毫秒换算常量。 */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * 恢复设置与会话；恢复成功或用户明确修改设置后才启用自动持久化。
 *
 * @return 无返回值。
 */
internal suspend fun DefaultRootComponent.restorePersistedState() {
    val startNanos = System.nanoTime()
    var restoreError: I18nMessage? = null
    var needsInitialPersist = false
    val initialLocation = launchConfiguration.initialLocation?.takeIf(String::isNotBlank)
    if (launchConfiguration.persistsMainSession) {
        val (settingsError, sessionResult) = coroutineScope {
            val settingsDeferred = async(start = CoroutineStart.UNDISPATCHED) { restoreSettings() }
            val sessionDeferred = async(start = CoroutineStart.UNDISPATCHED) { sessionRepository.loadSession() }
            settingsDeferred.await() to sessionDeferred.await()
        }
        restoreError = settingsError
        sessionResult.fold(
            onSuccess = { session ->
                if (session != null && settings.value.restoreSessionOnStartup) {
                    applySession(session)
                } else {
                    needsInitialPersist = true
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
        needsInitialPersist = true
    }
    if (restoreError == null) {
        // 只有完整恢复成功才写入启动后的快照，避免默认状态覆盖暂时不可读的旧数据。
        persistenceReady = true
        val previousSettings = settings.value
        recordRecentLocations(listOf(primaryPane.state.value.location, secondaryPane.state.value.location))
        if (settings.value != previousSettings) {
            needsInitialPersist = true
        }
    }
    sessionRestoreState.value = restoreError?.let(SessionRestoreState::Failed) ?: SessionRestoreState.Ready
    mutableState.value = initialRootState()
    val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
    OnyxLogger.info("Startup", "恢复设置与会话完成，耗时 ${elapsedMs}ms")
    if (restoreError == null && needsInitialPersist) {
        persistCurrentState()
    }
}

/** 组件销毁时同步落盘的最大等待上限（毫秒），防止慢 I/O 或锁竞争永久卡死窗口退出。 */
internal const val FLUSH_ON_DESTROY_TIMEOUT_MS = 1_500L

/** 独立于组件生命周期的后台落盘作用域，使超时放行窗口退出后 SQLite 写入仍可安全完成不产生半写。 */
private val destroyFlushScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
)

/**
 * 在独立 IO 协程（[kotlinx.coroutines.NonCancellable]）中执行落盘，同时在调用线程上以可取消的
 * `join()` 进行有界超时等待；即使底层 JDBC/SQLite 阻塞超过 [timeoutMillis]，调用方也能准时返回。
 *
 * @param timeoutMillis 最长阻塞等待毫秒数。
 * @param persistBlock 实际执行落盘的挂起块。
 * @return 在超时内完成落盘返回 `true`，超时放行返回 `false`。
 */
internal fun flushPersistedStateWithBoundedTimeout(
    timeoutMillis: Long = FLUSH_ON_DESTROY_TIMEOUT_MS,
    persistBlock: suspend () -> Unit,
): Boolean {
    val flushJob = destroyFlushScope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { persistBlock() }.onFailure { failure ->
                OnyxLogger.warn("Shutdown", "窗口销毁后台落盘失败", failure)
            }
        }
    }
    val completed = runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            flushJob.join()
            true
        }
    }
    if (completed == null) {
        OnyxLogger.warn("Shutdown", "窗口销毁落盘超过 ${timeoutMillis}ms，已放弃阻塞等待以避免退出卡死")
        return false
    }
    return true
}

/**
 * 组件销毁前在有界超时内刷新尚未落盘的设置与会话：
 * 先在当前线程捕获不可变快照，再派发至独立 IO 协程落盘并有界等待 `join()`，
 * 既避免丢失最后时刻的窗口尺寸/标签变更，也防止磁盘阻塞或 SQLite 锁竞争拖死 AWT 主线程。
 */
internal fun DefaultRootComponent.flushPersistedStateOnDestroy() {
    if (!persistenceReady) return
    val settingsSnapshot = settings.value
    val sessionSnapshot = if (launchConfiguration.persistsMainSession) {
        buildSessionSnapshot()
    } else {
        null
    }
    flushPersistedStateWithBoundedTimeout(FLUSH_ON_DESTROY_TIMEOUT_MS) {
        if (sessionSnapshot != null) {
            sessionManager.persist(settingsSnapshot, sessionSnapshot)
        } else {
            sessionManager.saveSettings(settingsSnapshot)
        }
    }
}

/**
 * 立即异步刷盘保存当前设置与会话快照，确保主窗口隐藏驻留后台时立刻落盘。
 */
internal fun DefaultRootComponent.flushPersistedStateNow() {
    if (!persistenceReady) return
    scope.launch {
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
                val sanitized = loadedSettings.sanitizeRootSettings()
                settings.value = sanitized
                syncGlobalSortAndTabDefaults(sanitized)
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
