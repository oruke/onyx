package com.oruke.onyx.app.cache

import androidx.compose.ui.awt.ComposeWindow
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.platform.OnyxStartupSplash
import com.oruke.onyx.app.platform.StartupSplashStage
import com.oruke.onyx.app.platform.SystemQuickAccessService
import com.oruke.onyx.app.platform.storage.JvmStorageDeviceService
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.di.fileModule
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileContextMenuService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.SystemMenuService
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ZipArchiveCreationService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.DecoratedWindowStyle
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * 应用启动预加载器，在 AWT、Skiko Direct3D 与 Compose 运行时初始化期间，
 * 利用多核后台线程并行预热 SQLite 设置、会话快照、初始面板目录、存储设备列表、Koin 依赖图与 Jewel 主题类。
 */
internal object OnyxStartupPreloader {
    /** 启动期后台 I/O 协程作用域。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动期后台 CPU 预热协程作用域。 */
    private val computeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 预创建的默认数据库服务实例。 */
    private val preloadedDatabaseService = AtomicReference<OnyxLocalDatabaseService?>(null)

    /** 预创建并预热快照的默认存储设备服务任务。 */
    private val preloadedStorageDeviceService = AtomicReference<Deferred<StorageDeviceService>?>(null)

    /** 预创建并完成单例预热的 Koin 应用容器任务。 */
    private val preloadedKoinApplication = AtomicReference<Deferred<KoinApplication>?>(null)

    /** 预加载的应用设置任务。 */
    private val preloadedSettings = AtomicReference<Deferred<Result<OnyxSettings?>>?>(null)

    /** 预加载的应用会话任务。 */
    private val preloadedSession = AtomicReference<Deferred<Result<AppSessionSnapshot?>>?>(null)

    /** 预加载的首屏本地目录条目任务表（规范化路径 -> 目录读取任务）。 */
    private val preloadedDirectories = ConcurrentHashMap<String, Deferred<Result<List<VFile>>>>()

    /** 预解码的应用图标缓存。 */
    private val preloadedLogoImage = AtomicReference<BufferedImage?>(null)

    /**
     * 在应用主入口启动后台并行预加载，仅在桌面进程启动时调用一次。
     */
    fun start() {
        val databaseService = OnyxLocalDatabaseService()
        if (!preloadedDatabaseService.compareAndSet(null, databaseService)) return

        OnyxStartupSplash.updateStage(StartupSplashStage.PRELOADING_STORAGE)

        ioScope.launch {
            val logo = loadApplicationLogoImage()
            if (logo != null) {
                preloadedLogoImage.set(logo)
                OnyxStartupSplash.updateLogoImage(logo)
            }
        }

        val settingsRepository = SqliteSettingsRepository(
            databaseService = databaseService,
            settingsDatabaseRepository = SettingsDatabaseRepository(),
            useStartupPreload = false,
        )
        val sessionRepository = JsonSessionRepository(useStartupPreload = false)

        preloadedSettings.set(
            ioScope.async {
                val startNanos = System.nanoTime()
                val result = settingsRepository.loadSettings()
                val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                OnyxLogger.info("Startup", "后台预加载设置完成，耗时 ${elapsedMs}ms")
                result
            },
        )

        val sessionDeferred = ioScope.async {
            val startNanos = System.nanoTime()
            val result = sessionRepository.loadSession()
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            OnyxLogger.info("Startup", "后台预加载会话完成，耗时 ${elapsedMs}ms")
            preloadInitialDirectories(ioScope, preloadedDirectories, result.getOrNull())
            result
        }
        preloadedSession.set(sessionDeferred)

        preloadedStorageDeviceService.set(
            ioScope.async {
                val startNanos = System.nanoTime()
                val service = JvmStorageDeviceService()
                service.snapshot()
                val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                OnyxLogger.info("Startup", "后台预热存储设备完成，耗时 ${elapsedMs}ms")
                service
            },
        )

        preloadedKoinApplication.set(
            computeScope.async {
                warmUpKoinAndThemeClasses()
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
     * 获取已预热的默认存储设备服务实例；未启动预加载时创建新实例。
     *
     * @return 默认存储设备服务。
     */
    fun obtainDefaultStorageDeviceService(): StorageDeviceService {
        val deferred = preloadedStorageDeviceService.getAndSet(null) ?: return JvmStorageDeviceService()
        return runBlocking { deferred.await() }
    }

    /**
     * 获取已后台预热的 Koin 应用容器；未启动预加载时现场构建。
     *
     * @return 已装载 `fileModule` 的 [KoinApplication]。
     */
    fun obtainKoinApplication(): KoinApplication {
        val deferred = preloadedKoinApplication.getAndSet(null)
            ?: return koinApplication { modules(fileModule) }
        return runBlocking { deferred.await() }
    }

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

    /**
     * 消费指定目录的一次性预加载文件列表任务。
     *
     * @param location 目录路径。
     * @return 匹配的预加载任务；未命中时返回 `null`。
     */
    fun consumePreloadedDirectory(location: String): Deferred<Result<List<VFile>>>? =
        preloadedDirectories.remove(location)

    /**
     * 读取已预解码的应用图标（若尚未完成则现场读取）。
     *
     * @return 应用图标图像，不可读时返回 `null`。
     */
    fun peekLogoImage(): BufferedImage? = preloadedLogoImage.get() ?: loadApplicationLogoImage()?.also {
        preloadedLogoImage.compareAndSet(null, it)
    }
}

/** 纳秒转毫秒换算常量。 */
private const val NANOS_PER_MILLI = 1_000_000L

/** 根据会话快照提前在后台枚举首屏主次面板的活动本地目录。 */
private fun preloadInitialDirectories(
    scope: CoroutineScope,
    targetMap: ConcurrentHashMap<String, Deferred<Result<List<VFile>>>>,
    session: AppSessionSnapshot?,
) {
    val locations = extractInitialLocalDirectories(session)
    if (locations.isEmpty()) return
    val localProvider = LocalVfsProvider(JvmLocalFileProvider())
    for (location in locations) {
        targetMap.computeIfAbsent(location) { targetLocation ->
            scope.async {
                val startNanos = System.nanoTime()
                val entries = localProvider.list(targetLocation)
                val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                OnyxLogger.info("Startup", "后台预加载首屏目录完成 ($targetLocation)，耗时 ${elapsedMs}ms")
                entries
            }
        }
    }
}

/** 提取首屏需要立即展示的本地目录规范化路径集合（最多 2 个活动面板目录）。 */
private fun extractInitialLocalDirectories(session: AppSessionSnapshot?): Set<String> {
    val rawCandidates = if (session != null) {
        buildList {
            resolveActiveTabLocation(session.primaryPane)?.let(::add)
            if (session.layoutMode != PaneLayoutMode.SINGLE) {
                resolveActiveTabLocation(session.secondaryPane)?.let(::add)
            }
        }
    } else {
        listOfNotNull(System.getProperty("user.home"))
    }
    return rawCandidates
        .asSequence()
        .filter { candidate -> candidate.isNotBlank() && !candidate.contains("://") }
        .mapNotNull { candidate ->
            runCatching {
                Path.of(candidate).toAbsolutePath().normalize().takeIf(Files::isDirectory)?.toString()
            }.getOrNull()
        }
        .toSet()
}

/** 从面板会话快照中提取当前活动标签的目录位置。 */
private fun resolveActiveTabLocation(pane: PaneSessionSnapshot): String? {
    val active = pane.tabs.firstOrNull { tab -> tab.id == pane.activeTabId } ?: pane.tabs.firstOrNull()
    return active?.location?.takeIf(String::isNotBlank)
}

/** 在后台计算线程预构建 Koin 容器并预热核心单例及 Jewel 窗口主题类。 */
private fun warmUpKoinAndThemeClasses(): KoinApplication {
    val startNanos = System.nanoTime()
    runCatching {
        Class.forName(ComponentStyling::class.java.name)
        Class.forName(DecoratedWindowStyle::class.java.name)
        Class.forName(TitleBarStyle::class.java.name)
        Class.forName(ComposeWindow::class.java.name)
    }
    val app = koinApplication {
        modules(fileModule)
    }
    val koin = app.koin
    runCatching {
        koin.get<VfsProviderRegistry>()
        koin.get<FileRepository>()
        koin.get<FileCommandService>()
        koin.get<ArchiveService>()
        koin.get<ZipArchiveCreationService>()
        koin.get<OpenWithService>()
        koin.get<SystemMenuService>()
        koin.get<FileContextMenuService>()
        koin.get<SystemQuickAccessService>()
        koin.get<SettingsRepository>()
        koin.get<SessionRepository>()
        koin.get<TaskPersistenceRepository>()
        koin.get<PlatformMenuCacheMaintenanceService>()
        koin.get<StorageDeviceService>()
        koin.get<VfsPathService>()
    }
    val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
    OnyxLogger.info("Startup", "后台预热 Koin 依赖图与主题类完成，耗时 ${elapsedMs}ms")
    return app
}

/** 从类路径读取并解码应用 Logo PNG。 */
private fun loadApplicationLogoImage(): BufferedImage? {
    val resourcePaths = listOf(
        "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo.png",
        "/drawable/onyx_logo.png",
    )
    return resourcePaths.firstNotNullOfOrNull { path ->
        runCatching {
            OnyxStartupPreloader::class.java.getResourceAsStream(path)?.use(ImageIO::read)
        }.getOrNull()
    }
}
