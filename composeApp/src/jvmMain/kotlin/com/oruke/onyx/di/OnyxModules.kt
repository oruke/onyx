package com.oruke.onyx.di

import com.oruke.onyx.app.cache.OnyxLocalDatabaseService
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import com.oruke.onyx.app.cache.PlatformMenuCacheMaintenanceService
import com.oruke.onyx.app.cache.PlatformMenuCacheRepository
import com.oruke.onyx.app.cache.PlatformMenuCacheService
import com.oruke.onyx.app.cache.PlatformMenuCacheWarmupService
import com.oruke.onyx.app.cache.PlatformMenuSourceFingerprintService
import com.oruke.onyx.app.cache.PlatformMenuSourceWatchService
import com.oruke.onyx.app.cache.SettingsDatabaseRepository
import com.oruke.onyx.app.cache.SqliteSettingsRepository
import com.oruke.onyx.app.component.EntryNameSuggestionService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveInfoService
import com.oruke.onyx.app.filesystem.DiskThumbnailCache
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.app.filesystem.JsonTaskPersistenceRepository
import com.oruke.onyx.app.filesystem.JvmArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.JvmArchiveInfoService
import com.oruke.onyx.app.filesystem.JvmDesktopExternalOpenService
import com.oruke.onyx.app.filesystem.JvmDesktopTrashService
import com.oruke.onyx.app.filesystem.JvmFileContextMenuService
import com.oruke.onyx.app.filesystem.JvmFileHashService
import com.oruke.onyx.app.filesystem.JvmFileTypeService
import com.oruke.onyx.app.filesystem.JvmImageMetadataService
import com.oruke.onyx.app.filesystem.JvmPlatformOpenWithService
import com.oruke.onyx.app.filesystem.JvmPreviewService
import com.oruke.onyx.app.filesystem.JvmRemoteAuthStore
import com.oruke.onyx.app.filesystem.JvmSystemFileMaterializer
import com.oruke.onyx.app.filesystem.JvmSystemMenuService
import com.oruke.onyx.app.filesystem.JvmTerminalLauncherService
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import com.oruke.onyx.app.filesystem.JvmThumbnailService
import com.oruke.onyx.app.filesystem.JvmUserCommandLauncherService
import com.oruke.onyx.app.filesystem.LazyRoutedArchiveProvider
import com.oruke.onyx.app.filesystem.LazyS3VfsProvider
import com.oruke.onyx.app.filesystem.LazySmbVfsProvider
import com.oruke.onyx.app.filesystem.LazyWebDavVfsProvider
import com.oruke.onyx.app.filesystem.MemoryConfigService
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.ProviderBackedFileRepository
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.UserCommandLauncherService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.JvmExternalFileDragService
import com.oruke.onyx.app.platform.JvmMemoryConfigService
import com.oruke.onyx.app.platform.JvmSystemQuickAccessService
import com.oruke.onyx.app.platform.SystemQuickAccessService
import com.oruke.onyx.shared.filesystem.FileCollectionVfsProvider
import com.oruke.onyx.shared.usecase.BatchRenamePresetPlanner
import com.oruke.onyx.shared.usecase.DirectoryComparisonUseCase
import com.oruke.onyx.shared.usecase.DirectorySyncPlanner
import com.oruke.onyx.shared.usecase.DuplicateFileFinderUseCase
import com.oruke.onyx.shared.usecase.FileCollectionRepository
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileContentSearchService
import com.oruke.onyx.shared.usecase.FileLabelRuleUseCase
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.shared.usecase.JvmVfsFileContentSearchService
import com.oruke.onyx.shared.usecase.PreviewCapabilityUseCase
import com.oruke.onyx.shared.usecase.UserCommandUseCase
import com.oruke.onyx.ui.ResourceEntryNameSuggestionService
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileContextMenuService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.vfs.api.ProviderBackedFileCommandService
import com.oruke.onyx.vfs.api.ProviderBackedVfsConnectionTestService
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.SystemMenuService
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.api.VfsRandomAccessServiceRegistry
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveServiceLogger
import com.oruke.onyx.vfs.archive.ArchiveVfsProvider
import com.oruke.onyx.vfs.archive.ZipArchiveCreationService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import com.oruke.onyx.vfs.s3.MutableS3ConnectionRepository
import com.oruke.onyx.vfs.s3.RemoteAuthStoreS3AuthRepository
import com.oruke.onyx.vfs.s3.S3AuthRepository
import com.oruke.onyx.vfs.s3.S3ConnectionRepository
import com.oruke.onyx.vfs.s3.S3VfsProvider
import com.oruke.onyx.vfs.smb.RemoteAuthStoreSmbAuthRepository
import com.oruke.onyx.vfs.smb.SmbAuthRepository
import com.oruke.onyx.vfs.smb.SmbVfsProvider
import com.oruke.onyx.vfs.webdav.RemoteAuthStoreWebDavAuthRepository
import com.oruke.onyx.vfs.webdav.WebDavAuthRepository
import com.oruke.onyx.vfs.webdav.WebDavVfsProvider
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * 文件系统服务模块 — 所有文件操作相关的服务注册。
 *
 * - JvmLocalFileProvider 提供本地命令实现
 * - 归档与远程协议（SMB / WebDAV / S3）采用延迟路由代理，首次访问对应协议时再初始化底层客户端与线程池
 * - 其余为平台服务的单例绑定
 */
val fileModule = module {
    single<com.oruke.onyx.vfs.api.StorageDeviceService> {
        OnyxStartupPreloader.obtainDefaultStorageDeviceService()
    } onClose { service -> (service as? java.io.Closeable)?.close() }
    single { com.oruke.onyx.app.filesystem.SystemLocationVfsProvider(get()) }
    single { com.oruke.onyx.app.filesystem.TrashLocationVfsProvider(get()) }
    single { JvmLocalFileProvider() }
    single {
        ArchiveService(
            randomAccessService = get(),
            logger = object : ArchiveServiceLogger {
                override fun warn(
                    tag: String,
                    message: String,
                    throwable: Throwable,
                ) {
                    com.oruke.onyx.app.OnyxLogger.warn(tag, message, throwable)
                }

                override fun error(
                    tag: String,
                    message: String,
                    throwable: Throwable,
                ) {
                    com.oruke.onyx.app.OnyxLogger.error(tag, message, throwable)
                }
            },
        )
    }
    single { LocalVfsProvider(get()) }
    single { ArchiveVfsProvider(get()) }
    single { LazyRoutedArchiveProvider { get<ArchiveVfsProvider>() } }
    single<RemoteAuthStore> { JvmRemoteAuthStore() }
    single<FileCollectionRepository> { InMemoryFileCollectionRepository() }
    single { FileCollectionVfsProvider(get()) }
    single<SmbAuthRepository> { RemoteAuthStoreSmbAuthRepository(get()) }
    single<WebDavAuthRepository> { RemoteAuthStoreWebDavAuthRepository(get()) }
    single<S3AuthRepository> { RemoteAuthStoreS3AuthRepository(get()) }
    single { MutableS3ConnectionRepository() }
    single<S3ConnectionRepository> { get<MutableS3ConnectionRepository>() }
    single { SmbVfsProvider(authRepository = get()) } onClose { provider -> provider?.close() }
    single { WebDavVfsProvider(authRepository = get()) } onClose { provider -> provider?.close() }
    single {
        S3VfsProvider(authRepository = get(), connectionRepository = get())
    } onClose { provider -> provider?.close() }
    single { LazySmbVfsProvider { get<SmbVfsProvider>() } }
    single { LazyWebDavVfsProvider { get<WebDavVfsProvider>() } }
    single { LazyS3VfsProvider { get<S3VfsProvider>() } }
    single {
        ZipArchiveCreationService(
            fileRepository = get(),
            contentServices = listOf<RoutableVfsContentService>(
                get<LazyRoutedArchiveProvider>(),
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
        )
    }
    single<RoutableVfsRandomAccessService> {
        VfsRandomAccessServiceRegistry(
            listOf<RoutableVfsRandomAccessService>(
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
        )
    }
    single {
        VfsProviderRegistry(
            listOf(
                get<LazyRoutedArchiveProvider>(),
                get<FileCollectionVfsProvider>(),
                get<com.oruke.onyx.app.filesystem.SystemLocationVfsProvider>(),
                get<com.oruke.onyx.app.filesystem.TrashLocationVfsProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
                get<LocalVfsProvider>(),
            ),
        )
    }
    single<FileRepository> { ProviderBackedFileRepository(get(), useStartupPreload = true) }
    single<VfsConnectionTestService> {
        ProviderBackedVfsConnectionTestService(
            listOf(
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
        )
    }
    single<FileCommandService> {
        ProviderBackedFileCommandService(
            listOf<RoutableFileCommandService>(
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
            contentServices = listOf<RoutableVfsContentService>(
                get<LazyRoutedArchiveProvider>(),
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
            providerRegistry = get(),
        )
    }
    single<ExternalOpenService> { JvmDesktopExternalOpenService(get()) }
    single<TrashService> { JvmDesktopTrashService(storageDevices = get()) }
    single<TextClipboardService> { JvmTextClipboardService() }
    single<SystemFileMaterializer> {
        JvmSystemFileMaterializer(
            archiveService = get(),
            listOf<RoutableVfsContentService>(
                get<LazyRoutedArchiveProvider>(),
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
        )
    } onClose { service -> (service as? java.io.Closeable)?.close() }
    factory<ExternalFileDragService> { JvmExternalFileDragService(get()) }
    single<OpenWithService> { JvmPlatformOpenWithService(get()) }
    single { OnyxStartupPreloader.obtainDefaultDatabaseService() }
    single { PlatformMenuCacheRepository() }
    single { PlatformMenuCacheService(get(), get()) }
    single { PlatformMenuSourceFingerprintService() }
    single { PlatformMenuSourceWatchService(get<PlatformMenuSourceFingerprintService>()) }
    single { PlatformMenuCacheWarmupService(get()) }
    single { PlatformMenuCacheMaintenanceService(get(), get(), get(), get()) }
    single { SettingsDatabaseRepository() }
    single<SystemMenuService> { JvmSystemMenuService(get(), get()) }
    single<FileContextMenuService> { JvmFileContextMenuService(get(), get()) }
    single<VfsPathService> {
        com.oruke.onyx.app.filesystem.DesktopLocationPathService(storageDevices = get())
    }
    single<EntryNameSuggestionService> { ResourceEntryNameSuggestionService() }
    single<FileTypeService> { JvmFileTypeService() }
    single<ArchiveEntryOpenService> { JvmArchiveEntryOpenService(get(), get()) }
    single<TerminalLauncherService> { JvmTerminalLauncherService() }
    single<SystemQuickAccessService> { JvmSystemQuickAccessService() }
    single<PreviewService> {
        JvmPreviewService(
            contentServices = listOf(
                get<LazyRoutedArchiveProvider>(),
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
            archiveService = get(),
            randomAccessService = get(),
        )
    }
    single<FileContentSearchService> {
        JvmVfsFileContentSearchService(
            listOf(
                get<LazyRoutedArchiveProvider>(),
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
        )
    }
    single<FileHashService> { JvmFileHashService(get()) }
    single { FileCollectionUseCase(get()) }
    single { FileLabelRuleUseCase() }
    single { DirectoryComparisonUseCase(get()) }
    single { DirectorySyncPlanner() }
    single { DuplicateFileFinderUseCase(get()) }
    single { BatchRenamePresetPlanner() }
    single { PreviewCapabilityUseCase() }
    single { UserCommandUseCase() }
    single<UserCommandLauncherService> { JvmUserCommandLauncherService() }
    single<ArchiveInfoService> { JvmArchiveInfoService(get()) }
    single { DiskThumbnailCache() }
    single<ThumbnailService> {
        JvmThumbnailService(
            contentServices = listOf<RoutableVfsContentService>(
                get<LazyRoutedArchiveProvider>(),
                get<JvmLocalFileProvider>(),
                get<LazySmbVfsProvider>(),
                get<LazyWebDavVfsProvider>(),
                get<LazyS3VfsProvider>(),
            ),
            archiveService = get(),
            diskCache = get(),
        )
    }
    single<ImageMetadataService> { JvmImageMetadataService(get()) }
    single<com.oruke.onyx.app.filesystem.MediaPreviewService> {
        com.oruke.onyx.app.filesystem.JvmMediaPreviewService(get())
    }
    factory<com.oruke.onyx.app.filesystem.AudioPreviewPlayer> {
        com.oruke.onyx.app.filesystem.JvmAudioPreviewPlayer()
    }
    single<SettingsRepository> { SqliteSettingsRepository(get(), get(), useStartupPreload = true) }
    single<SessionRepository> { JsonSessionRepository(useStartupPreload = true) }
    single<TaskPersistenceRepository> { JsonTaskPersistenceRepository() }
    single<MemoryConfigService> { JvmMemoryConfigService() }
}
