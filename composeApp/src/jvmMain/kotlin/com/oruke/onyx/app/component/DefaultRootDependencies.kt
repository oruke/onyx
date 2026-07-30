package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.ImageViewerController
import com.oruke.onyx.app.component.delegate.SessionManager
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveInfoService
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.SystemQuickAccessService
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileContentSearchService
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileContextMenuService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.SystemMenuService
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ZipArchiveCreationService
import com.oruke.onyx.vfs.s3.MutableS3ConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** 根组件的文件读写与条目打开依赖。 */
internal data class RootFileDependencies(
    /** 文件查询仓库。 */
    val fileRepository: FileRepository,
    /** 文件写入命令服务。 */
    val fileCommandService: FileCommandService,
    /** 系统文本剪贴板服务。 */
    val textClipboardService: TextClipboardService,
    /** 回收站服务。 */
    val trashService: TrashService,
    /** 外部程序打开服务。 */
    val externalOpenService: ExternalOpenService,
    /** 压缩包服务。 */
    val archiveService: ArchiveService,
    /** ZIP 压缩包创建服务。 */
    val zipArchiveCreationService: ZipArchiveCreationService,
    /** 文件类型识别服务。 */
    val fileTypeService: FileTypeService,
    /** 压缩包条目临时打开服务。 */
    val archiveEntryOpenService: ArchiveEntryOpenService,
    /** 新建条目名称建议服务。 */
    val entryNameSuggestionService: EntryNameSuggestionService,
)

/** 根组件的平台、持久化与路径依赖。 */
internal data class RootPlatformDependencies(
    /** 设置仓库。 */
    val settingsRepository: SettingsRepository,
    /** 会话仓库。 */
    val sessionRepository: SessionRepository,
    /** 系统打开方式服务。 */
    val openWithService: OpenWithService,
    /** 系统右键菜单服务。 */
    val systemMenuService: SystemMenuService,
    /** 文件上下文菜单服务。 */
    val fileContextMenuService: FileContextMenuService,
    /** 外部文件拖放服务。 */
    val externalFileDragService: ExternalFileDragService,
    /** VFS 路径服务。 */
    val pathService: VfsPathService,
    /** VFS Provider 注册表。 */
    val providerRegistry: VfsProviderRegistry,
    /** 终端启动服务。 */
    val terminalLauncherService: TerminalLauncherService,
    /** 操作系统快速访问位置读取服务。 */
    val systemQuickAccessService: SystemQuickAccessService,
)

/** 根组件的预览、元数据与远程认证依赖。 */
internal data class RootInspectionDependencies(
    /** 文本预览服务。 */
    val previewService: PreviewService,
    /** 文件哈希服务。 */
    val fileHashService: FileHashService,
    /** 压缩包信息服务。 */
    val archiveInfoService: ArchiveInfoService,
    /** 缩略图服务。 */
    val thumbnailService: ThumbnailService,
    /** 图片元数据服务。 */
    val imageMetadataService: ImageMetadataService,
    /** 远程连接测试服务。 */
    val connectionTestService: VfsConnectionTestService,
    /** 远程认证存储。 */
    val remoteAuthStore: RemoteAuthStore,
    /** 运行期 S3 Endpoint 配置仓库。 */
    val s3ConnectionRepository: MutableS3ConnectionRepository,
)

/** 根组件的搜索依赖。 */
internal data class RootSearchDependencies(
    /** 文件集合用例。 */
    val fileCollectionUseCase: FileCollectionUseCase,
    /** 文件内容搜索服务。 */
    val fileContentSearchService: FileContentSearchService,
)

/** 根组件运行期共享委托。 */
internal data class RootRuntimeDelegates(
    /** 后台任务编排器。 */
    val taskOrchestrator: TaskOrchestrator,
    /** 文件剪贴板状态管理器。 */
    val clipboardManager: ClipboardManager,
    /** 图片查看器控制器。 */
    val imageViewerController: ImageViewerController,
    /** 设置和会话持久化管理器。 */
    val sessionManager: SessionManager,
    /** 所有窗口共享的即时设置状态。 */
    val settings: MutableStateFlow<OnyxSettings>,
)

/** 默认根组件的全部类型化依赖。 */
internal data class DefaultRootDependencies(
    /** 文件读写依赖。 */
    val files: RootFileDependencies,
    /** 平台与持久化依赖。 */
    val platform: RootPlatformDependencies,
    /** 预览与远程认证依赖。 */
    val inspection: RootInspectionDependencies,
    /** 搜索依赖。 */
    val search: RootSearchDependencies,
    /** 共享运行期委托。 */
    val delegates: RootRuntimeDelegates,
)
