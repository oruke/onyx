package com.oruke.onyx.app.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.ImageViewerController
import com.oruke.onyx.app.component.delegate.SessionManager
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveInfoService
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.OpenWithService
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.RemoteAuthStore
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.app.filesystem.TaskPersistenceRepository
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.app.filesystem.VfsConnectionTestService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.filesystem.VfsProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.getKoin

/**
 * Composable factory — 通过 Koin 获取所有服务依赖，通过 Decompose ComponentContext 管理生命周期。
 *
 * 生命周期由 [LifecycleRegistry] 驱动：
 * - Composable 进入组合 → create + resume
 * - Composable 退出组合 → destroy（自动取消组件内部 CoroutineScope）
 */
@Composable
fun rememberRootComponent(): RootComponent {
    val koin = getKoin()
    val lifecycle = remember { LifecycleRegistry() }
    val component = remember {
        val componentContext = DefaultComponentContext(lifecycle = lifecycle)
        // 创建 delegate 实例
        val delegateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val taskOrchestrator = TaskOrchestrator(
            scope = delegateScope,
            taskRepository = koin.get<TaskPersistenceRepository>(),
        )
        val clipboardManager = ClipboardManager()
        val imageViewerController = ImageViewerController()
        val sessionManager = SessionManager(
            settingsRepository = koin.get<SettingsRepository>(),
            sessionRepository = koin.get<SessionRepository>(),
        )
        DefaultRootComponent(
            componentContext = componentContext,
            fileRepository = koin.get<FileRepository>(),
            fileCommandService = koin.get<FileCommandService>(),
            textClipboardService = koin.get<TextClipboardService>(),
            trashService = koin.get<TrashService>(),
            externalOpenService = koin.get<ExternalOpenService>(),
            settingsRepository = koin.get<SettingsRepository>(),
            sessionRepository = koin.get<SessionRepository>(),
            archiveService = koin.get<ArchiveService>(),
            openWithService = koin.get<OpenWithService>(),
            pathService = koin.get<VfsPathService>(),
            entryNameSuggestionService = koin.get<EntryNameSuggestionService>(),
            providerRegistry = koin.get<VfsProviderRegistry>(),
            fileTypeService = koin.get<FileTypeService>(),
            archiveEntryOpenService = koin.get<ArchiveEntryOpenService>(),
            terminalLauncherService = koin.get<TerminalLauncherService>(),
            previewService = koin.get<PreviewService>(),
            fileHashService = koin.get<FileHashService>(),
            archiveInfoService = koin.get<ArchiveInfoService>(),
            thumbnailService = koin.get<ThumbnailService>(),
            imageMetadataService = koin.get<ImageMetadataService>(),
            connectionTestService = koin.get<VfsConnectionTestService>(),
            remoteAuthStore = koin.get<RemoteAuthStore>(),
            taskOrchestrator = taskOrchestrator,
            clipboardManager = clipboardManager,
            imageViewerController = imageViewerController,
            sessionManager = sessionManager,
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
