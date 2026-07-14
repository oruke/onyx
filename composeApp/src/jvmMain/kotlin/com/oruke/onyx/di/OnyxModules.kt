package com.oruke.onyx.di

import com.oruke.onyx.app.cache.OnyxLocalDatabaseService
import com.oruke.onyx.app.cache.PlatformMenuCacheMaintenanceService
import com.oruke.onyx.app.cache.PlatformMenuCacheRepository
import com.oruke.onyx.app.cache.PlatformMenuCacheService
import com.oruke.onyx.app.cache.PlatformMenuCacheWarmupService
import com.oruke.onyx.app.cache.PlatformMenuSourceFingerprintService
import com.oruke.onyx.app.cache.PlatformMenuSourceWatchService
import com.oruke.onyx.app.cache.SettingsDatabaseRepository
import com.oruke.onyx.app.cache.SqliteSettingsRepository
import com.oruke.onyx.app.component.EntryNameSuggestionService
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveServiceLogger
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveInfoService
import com.oruke.onyx.vfs.archive.ArchiveVfsProvider
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.shared.filesystem.FileCollectionVfsProvider
import com.oruke.onyx.vfs.api.FileContextMenuService
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.app.filesystem.JsonTaskPersistenceRepository
import com.oruke.onyx.app.filesystem.JvmDesktopExternalOpenService
import com.oruke.onyx.app.filesystem.JvmDesktopTrashService
import com.oruke.onyx.app.filesystem.JvmArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.JvmArchiveInfoService
import com.oruke.onyx.app.filesystem.JvmFileContextMenuService
import com.oruke.onyx.app.filesystem.JvmFileHashService
import com.oruke.onyx.app.filesystem.JvmFileTypeService
import com.oruke.onyx.app.filesystem.JvmImageMetadataService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.app.filesystem.JvmPreviewService
import com.oruke.onyx.app.filesystem.JvmPlatformOpenWithService
import com.oruke.onyx.app.filesystem.JvmRemoteAuthStore
import com.oruke.onyx.app.filesystem.JvmSystemFileMaterializer
import com.oruke.onyx.app.filesystem.JvmSystemMenuService
import com.oruke.onyx.app.filesystem.JvmTerminalLauncherService
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import com.oruke.onyx.app.filesystem.JvmThumbnailService
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.vfs.local.LocalVfsProvider
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.vfs.api.ProviderBackedVfsConnectionTestService
import com.oruke.onyx.vfs.api.ProviderBackedFileCommandService
import com.oruke.onyx.app.filesystem.ProviderBackedFileRepository
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.s3.RemoteAuthStoreS3AuthRepository
import com.oruke.onyx.vfs.s3.MutableS3ConnectionRepository
import com.oruke.onyx.vfs.smb.RemoteAuthStoreSmbAuthRepository
import com.oruke.onyx.vfs.webdav.RemoteAuthStoreWebDavAuthRepository
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.s3.S3AuthRepository
import com.oruke.onyx.vfs.s3.S3ConnectionRepository
import com.oruke.onyx.vfs.s3.S3VfsProvider
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.SystemMenuService
import com.oruke.onyx.vfs.smb.SmbAuthRepository
import com.oruke.onyx.vfs.smb.SmbVfsProvider
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.webdav.WebDavAuthRepository
import com.oruke.onyx.vfs.webdav.WebDavVfsProvider
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.JvmExternalFileDragService
import com.oruke.onyx.app.platform.JvmSystemQuickAccessService
import com.oruke.onyx.app.platform.SystemQuickAccessService
import com.oruke.onyx.shared.usecase.FileContentSearchService
import com.oruke.onyx.shared.usecase.BatchRenamePresetPlanner
import com.oruke.onyx.shared.usecase.DirectoryComparisonUseCase
import com.oruke.onyx.shared.usecase.DirectorySyncPlanner
import com.oruke.onyx.shared.usecase.DuplicateFileFinderUseCase
import com.oruke.onyx.shared.usecase.FileCollectionRepository
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileLabelRuleUseCase
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.shared.usecase.JvmVfsFileContentSearchService
import com.oruke.onyx.shared.usecase.PreviewCapabilityUseCase
import com.oruke.onyx.shared.usecase.UserCommandUseCase
import com.oruke.onyx.ui.ResourceEntryNameSuggestionService
import org.koin.dsl.module

/**
 * 文件系统服务模块 — 所有文件操作相关的服务注册。
 *
 * - JvmLocalFileProvider 提供本地命令实现
 * - VfsProviderRegistry 统一路由本地与压缩包 provider
 * - 其余为平台服务的单例绑定
 */
val fileModule = module {
    single { JvmLocalFileProvider() }
    single {
        ArchiveService(
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
            }
        )
    }
    single { LocalVfsProvider(get()) }
    single { ArchiveVfsProvider(get()) }
    single<RemoteAuthStore> { JvmRemoteAuthStore() }
    single<FileCollectionRepository> { InMemoryFileCollectionRepository() }
    single { FileCollectionVfsProvider(get()) }
    single<SmbAuthRepository> { RemoteAuthStoreSmbAuthRepository(get()) }
    single<WebDavAuthRepository> { RemoteAuthStoreWebDavAuthRepository(get()) }
    single<S3AuthRepository> { RemoteAuthStoreS3AuthRepository(get()) }
    single { MutableS3ConnectionRepository() }
    single<S3ConnectionRepository> { get<MutableS3ConnectionRepository>() }
    single { SmbVfsProvider(authRepository = get()) }
    single { WebDavVfsProvider(authRepository = get()) }
    single { S3VfsProvider(authRepository = get(), connectionRepository = get()) }
    single {
        VfsProviderRegistry(
            listOf(
                get<ArchiveVfsProvider>(),
                get<FileCollectionVfsProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
                get<LocalVfsProvider>(),
            )
        )
    }
    single<FileRepository> { ProviderBackedFileRepository(get()) }
    single<VfsConnectionTestService> {
        ProviderBackedVfsConnectionTestService(
            listOf(
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            )
        )
    }
    single<FileCommandService> {
        ProviderBackedFileCommandService(
            listOf<RoutableFileCommandService>(
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            ),
            contentServices = listOf<RoutableVfsContentService>(
                get<ArchiveVfsProvider>(),
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            ),
            providerRegistry = get(),
        )
    }
    single<ExternalOpenService> { JvmDesktopExternalOpenService(get()) }
    single<TrashService> { JvmDesktopTrashService() }
    single<TextClipboardService> { JvmTextClipboardService() }
    single<SystemFileMaterializer> {
        JvmSystemFileMaterializer(
            archiveService = get(),
            listOf<RoutableVfsContentService>(
                get<ArchiveVfsProvider>(),
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            )
        )
    }
    single<ExternalFileDragService> { JvmExternalFileDragService(get()) }
    single<OpenWithService> { JvmPlatformOpenWithService(get()) }
    single { OnyxLocalDatabaseService() }
    single { PlatformMenuCacheRepository() }
    single { PlatformMenuCacheService(get(), get()) }
    single { PlatformMenuSourceFingerprintService() }
    single { PlatformMenuSourceWatchService(get<PlatformMenuSourceFingerprintService>()) }
    single { PlatformMenuCacheWarmupService(get()) }
    single { PlatformMenuCacheMaintenanceService(get(), get(), get(), get()) }
    single { SettingsDatabaseRepository() }
    single<SystemMenuService> { JvmSystemMenuService(get(), get()) }
    single<FileContextMenuService> { JvmFileContextMenuService(get(), get()) }
    single<VfsPathService> { JvmVfsPathService() }
    single<EntryNameSuggestionService> { ResourceEntryNameSuggestionService() }
    single<FileTypeService> { JvmFileTypeService() }
    single<ArchiveEntryOpenService> { JvmArchiveEntryOpenService(get(), get()) }
    single<TerminalLauncherService> { JvmTerminalLauncherService() }
    single<SystemQuickAccessService> { JvmSystemQuickAccessService() }
    single<PreviewService> {
        JvmPreviewService(
            listOf(
                get<ArchiveVfsProvider>(),
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            )
        )
    }
    single<FileContentSearchService> {
        JvmVfsFileContentSearchService(
            listOf(
                get<ArchiveVfsProvider>(),
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            )
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
    single<ArchiveInfoService> { JvmArchiveInfoService(get()) }
    single<ThumbnailService> {
        JvmThumbnailService(
            contentServices = listOf<RoutableVfsContentService>(
                get<ArchiveVfsProvider>(),
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
                get<WebDavVfsProvider>(),
                get<S3VfsProvider>(),
            ),
            archiveService = get(),
        )
    }
    single<ImageMetadataService> { JvmImageMetadataService(get()) }
    single<SettingsRepository> { SqliteSettingsRepository(get(), get()) }
    single<SessionRepository> { JsonSessionRepository() }
    single<TaskPersistenceRepository> { JsonTaskPersistenceRepository() }
}
