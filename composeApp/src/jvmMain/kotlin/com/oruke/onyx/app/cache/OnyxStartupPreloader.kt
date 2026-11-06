package com.oruke.onyx.app.cache

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用启动预加载器，在 AWT、Skiko 与 Jewel 主题初始化期间并行预热默认 SQLite 数据库、设置与会话快照。
 */
internal object OnyxStartupPreloader {
    /** 启动期后台 I/O 协程作用域。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 预创建的默认数据库服务实例。 */
    private val preloadedDatabaseService = AtomicReference<OnyxLocalDatabaseService?>(null)

    /** 预加载的应用设置任务。 */
    private val preloadedSettings = AtomicReference<Deferred<Result<OnyxSettings?>>?>(null)

    /** 预加载的应用会话任务。 */
    private val preloadedSession = AtomicReference<Deferred<Result<AppSessionSnapshot?>>?>(null)

    /**
     * 在应用主入口启动后台预加载，仅在桌面进程启动时调用一次。
     *
     * @return 无返回值。
     */
    fun start() {
        val databaseService = OnyxLocalDatabaseService()
        if (!preloadedDatabaseService.compareAndSet(null, databaseService)) return
        val settingsRepository = SqliteSettingsRepository(
            databaseService = databaseService,
            settingsDatabaseRepository = SettingsDatabaseRepository(),
            useStartupPreload = false,
        )
        val sessionRepository = JsonSessionRepository(useStartupPreload = false)
        preloadedSettings.set(
            scope.async {
                val startNanos = System.nanoTime()
                val result = settingsRepository.loadSettings()
                val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                OnyxLogger.info("Startup", "后台预加载设置完成，耗时 ${elapsedMs}ms")
                result
            },
        )
        preloadedSession.set(
            scope.async {
                val startNanos = System.nanoTime()
                val result = sessionRepository.loadSession()
                val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                OnyxLogger.info("Startup", "后台预加载会话完成，耗时 ${elapsedMs}ms")
                result
            },
        )
    }

    /**
     * 获取已预热的默认数据库服务实例；未启动预加载时创建新实例。
     *
     * @return 默认本地数据库服务。
     */
    fun obtainDefaultDatabaseService(): OnyxLocalDatabaseService =
        preloadedDatabaseService.getAndSet(null) ?: OnyxLocalDatabaseService()

    /**
     * 消费一次性预加载的设置结果。
     *
     * @return 若存在尚未消费的预加载任务则返回该任务，否则返回 `null`。
     */
    fun consumePreloadedSettings(): Deferred<Result<OnyxSettings?>>? =
        preloadedSettings.getAndSet(null)

    /**
     * 消费一次性预加载的会话结果。
     *
     * @return 若存在尚未消费的预加载任务则返回该任务，否则返回 `null`。
     */
    fun consumePreloadedSession(): Deferred<Result<AppSessionSnapshot?>>? =
        preloadedSession.getAndSet(null)

    /** 纳秒转毫秒换算常量。 */
    private const val NANOS_PER_MILLI = 1_000_000L
}

