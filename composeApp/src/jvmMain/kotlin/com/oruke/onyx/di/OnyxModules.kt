package com.oruke.onyx.di

import com.oruke.onyx.app.component.EntryNameSuggestionService
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveServiceLogger
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveVfsProvider
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.app.filesystem.JsonSettingsRepository
import com.oruke.onyx.app.filesystem.JvmDesktopExternalOpenService
import com.oruke.onyx.app.filesystem.JvmDesktopTrashService
import com.oruke.onyx.app.filesystem.JvmArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.JvmFileTypeService
import com.oruke.onyx.app.filesystem.JvmImageMetadataService
import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.app.filesystem.JvmPreviewService
import com.oruke.onyx.app.filesystem.JvmPlatformOpenWithService
import com.oruke.onyx.app.filesystem.JvmRemoteAuthStore
import com.oruke.onyx.app.filesystem.JvmTerminalLauncherService
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import com.oruke.onyx.app.filesystem.JvmThumbnailService
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.app.filesystem.LocalVfsProvider
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.OpenWithService
import com.oruke.onyx.app.filesystem.ProviderBackedVfsConnectionTestService
import com.oruke.onyx.app.filesystem.ProviderBackedFileCommandService
import com.oruke.onyx.app.filesystem.ProviderBackedFileRepository
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.RemoteAuthStore
import com.oruke.onyx.app.filesystem.RemoteAuthStoreS3AuthRepository
import com.oruke.onyx.app.filesystem.RemoteAuthStoreSmbAuthRepository
import com.oruke.onyx.app.filesystem.RemoteAuthStoreWebDavAuthRepository
import com.oruke.onyx.app.filesystem.RoutableFileCommandService
import com.oruke.onyx.app.filesystem.RoutableVfsContentService
import com.oruke.onyx.app.filesystem.S3AuthRepository
import com.oruke.onyx.app.filesystem.S3VfsProvider
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.app.filesystem.SmbAuthRepository
import com.oruke.onyx.app.filesystem.SmbVfsProvider
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.app.filesystem.VfsConnectionTestService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.filesystem.VfsProviderRegistry
import com.oruke.onyx.app.filesystem.WebDavAuthRepository
import com.oruke.onyx.app.filesystem.WebDavVfsProvider
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
    single<SmbAuthRepository> { RemoteAuthStoreSmbAuthRepository(get()) }
    single<WebDavAuthRepository> { RemoteAuthStoreWebDavAuthRepository(get()) }
    single<S3AuthRepository> { RemoteAuthStoreS3AuthRepository(get()) }
    single { SmbVfsProvider(authRepository = get()) }
    single { WebDavVfsProvider(authRepository = get()) }
    single { S3VfsProvider(authRepository = get()) }
    single {
        VfsProviderRegistry(
            listOf(
                get<ArchiveVfsProvider>(),
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
            ),
            contentServices = listOf<RoutableVfsContentService>(
                get<JvmLocalFileProvider>(),
                get<SmbVfsProvider>(),
            ),
            providerRegistry = get(),
        )
    }
    single<ExternalOpenService> { JvmDesktopExternalOpenService() }
    single<TrashService> { JvmDesktopTrashService() }
    single<TextClipboardService> { JvmTextClipboardService() }
    single<OpenWithService> { JvmPlatformOpenWithService() }
    single<VfsPathService> { JvmVfsPathService() }
    single<EntryNameSuggestionService> { ResourceEntryNameSuggestionService() }
    single<FileTypeService> { JvmFileTypeService() }
    single<ArchiveEntryOpenService> { JvmArchiveEntryOpenService(get(), get()) }
    single<TerminalLauncherService> { JvmTerminalLauncherService() }
    single<PreviewService> { JvmPreviewService() }
    single<ThumbnailService> { JvmThumbnailService() }
    single<ImageMetadataService> { JvmImageMetadataService(get()) }
    single<SettingsRepository> { JsonSettingsRepository() }
    single<SessionRepository> { JsonSessionRepository() }
}
