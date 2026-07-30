package com.oruke.onyx.app.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.oruke.onyx.app.component.delegate.ImageViewerController
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ZipArchiveCreationService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveInfoService
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileContextMenuService
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.SystemMenuService
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.s3.MutableS3ConnectionRepository
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileContentSearchService
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.SystemQuickAccessService
import org.koin.compose.getKoin

/**
 * 使用明确的应用级与窗口级依赖创建根组件。
 *
 * 生命周期由 [LifecycleRegistry] 驱动，进入组合时恢复，退出组合时销毁组件树。
 *
 * @param applicationRuntime 所有文件管理器窗口共享的应用运行时。
 * @param externalFileDragService 当前窗口独占的外部拖放服务。
 * @param launchConfiguration 当前窗口的启动与会话策略。
 * @return 与当前窗口生命周期绑定的根组件。
 */
@Composable
internal fun rememberRootComponent(
    applicationRuntime: RootApplicationRuntime,
    externalFileDragService: ExternalFileDragService,
    launchConfiguration: RootLaunchConfiguration,
): RootComponent {
    val koin = getKoin()
    val lifecycle = remember { LifecycleRegistry() }
    val component = remember(applicationRuntime, externalFileDragService, launchConfiguration) {
        val componentContext = DefaultComponentContext(lifecycle = lifecycle)
        val imageViewerController = ImageViewerController()
        DefaultRootComponent(
            componentContext = componentContext,
            dependencies = DefaultRootDependencies(
                files = RootFileDependencies(
                    fileRepository = koin.get<FileRepository>(),
                    fileCommandService = koin.get<FileCommandService>(),
                    textClipboardService = koin.get<TextClipboardService>(),
                    trashService = koin.get<TrashService>(),
                    externalOpenService = koin.get<ExternalOpenService>(),
                    archiveService = koin.get<ArchiveService>(),
                    zipArchiveCreationService = koin.get<ZipArchiveCreationService>(),
                    fileTypeService = koin.get<FileTypeService>(),
                    archiveEntryOpenService = koin.get<ArchiveEntryOpenService>(),
                    entryNameSuggestionService = koin.get<EntryNameSuggestionService>(),
                ),
                platform = RootPlatformDependencies(
                    settingsRepository = koin.get<SettingsRepository>(),
                    sessionRepository = koin.get<SessionRepository>(),
                    openWithService = koin.get<OpenWithService>(),
                    systemMenuService = koin.get<SystemMenuService>(),
                    fileContextMenuService = koin.get<FileContextMenuService>(),
                    externalFileDragService = externalFileDragService,
                    pathService = koin.get<VfsPathService>(),
                    providerRegistry = koin.get<VfsProviderRegistry>(),
                    terminalLauncherService = koin.get<TerminalLauncherService>(),
                    systemQuickAccessService = koin.get<SystemQuickAccessService>(),
                ),
                inspection = RootInspectionDependencies(
                    previewService = koin.get<PreviewService>(),
                    fileHashService = koin.get<FileHashService>(),
                    archiveInfoService = koin.get<ArchiveInfoService>(),
                    thumbnailService = koin.get<ThumbnailService>(),
                    imageMetadataService = koin.get<ImageMetadataService>(),
                    connectionTestService = koin.get<VfsConnectionTestService>(),
                    remoteAuthStore = koin.get<RemoteAuthStore>(),
                    s3ConnectionRepository = koin.get<MutableS3ConnectionRepository>(),
                ),
                search = RootSearchDependencies(
                    fileCollectionUseCase = koin.get<FileCollectionUseCase>(),
                    fileContentSearchService = koin.get<FileContentSearchService>(),
                ),
                delegates = RootRuntimeDelegates(
                    taskOrchestrator = applicationRuntime.taskOrchestrator,
                    clipboardManager = applicationRuntime.clipboardManager,
                    imageViewerController = imageViewerController,
                    sessionManager = applicationRuntime.sessionManager,
                    settings = applicationRuntime.settings,
                ),
            ),
            launchConfiguration = launchConfiguration,
        )
    }

    DisposableEffect(component) {
        lifecycle.create()
        lifecycle.resume()
        onDispose {
            lifecycle.destroy()
        }
    }

    return component
}
