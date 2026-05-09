package com.oruke.onyx.di

import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveFileTypeService
import com.oruke.onyx.app.filesystem.ArchiveVfsProvider
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.JsonSessionRepository
import com.oruke.onyx.app.filesystem.JsonSettingsRepository
import com.oruke.onyx.app.filesystem.JvmDesktopExternalOpenService
import com.oruke.onyx.app.filesystem.JvmDesktopTrashService
import com.oruke.onyx.app.filesystem.JvmArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.JvmArchiveFileTypeService
import com.oruke.onyx.app.filesystem.JvmImageMetadataService
import com.oruke.onyx.app.filesystem.JvmLinuxOpenWithService
import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.app.filesystem.JvmPreviewService
import com.oruke.onyx.app.filesystem.JvmTerminalLauncherService
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import com.oruke.onyx.app.filesystem.JvmThumbnailService
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.app.filesystem.LocalVfsProvider
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.OpenWithService
import com.oruke.onyx.app.filesystem.ProviderBackedFileRepository
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.filesystem.VfsProviderRegistry
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
    single { ArchiveService() }
    single { LocalVfsProvider(get()) }
    single { ArchiveVfsProvider(get()) }
    single {
        VfsProviderRegistry(
            listOf(
                get<ArchiveVfsProvider>(),
                get<LocalVfsProvider>(),
            )
        )
    }
    single<FileRepository> { ProviderBackedFileRepository(get()) }
    single<FileCommandService> { get<JvmLocalFileProvider>() }
    single<ExternalOpenService> { JvmDesktopExternalOpenService() }
    single<TrashService> { JvmDesktopTrashService() }
    single<TextClipboardService> { JvmTextClipboardService() }
    single<OpenWithService> { JvmLinuxOpenWithService() }
    single<VfsPathService> { JvmVfsPathService() }
    single<ArchiveFileTypeService> { JvmArchiveFileTypeService() }
    single<ArchiveEntryOpenService> { JvmArchiveEntryOpenService(get(), get()) }
    single<TerminalLauncherService> { JvmTerminalLauncherService() }
    single<PreviewService> { JvmPreviewService() }
    single<ThumbnailService> { JvmThumbnailService() }
    single<ImageMetadataService> { JvmImageMetadataService(get()) }
    single<SettingsRepository> { JsonSettingsRepository() }
    single<SessionRepository> { JsonSessionRepository() }
}
