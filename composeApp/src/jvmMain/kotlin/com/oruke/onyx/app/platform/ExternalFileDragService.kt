package com.oruke.onyx.app.platform

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.app.filesystem.systemLocalPathOrNull
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/**
 * 外部文件拖放服务，负责把 Onyx 的 VFS 选择转换为系统级拖放数据。
 */
interface ExternalFileDragService {
    /** 当前是否已进入系统级拖放流程。 */
    val isSystemDragActive: Boolean

    /**
     * 在窗口上安装系统拖放桥接。
     *
     * @param window 主窗口。
     */
    fun install(window: java.awt.Window)

    /**
     * 卸载系统拖放桥接并清理临时状态。
     */
    fun uninstall()

    /**
     * 永久释放服务持有的协程与 JVM 退出钩子。
     *
     * @return 无返回值。
     */
    fun dispose()

    /**
     * 清理待拖放状态。
     */
    fun clearPending()

    /**
     * 预处理待拖放条目。
     *
     * @param entries 当前选中的 VFS 条目。
     * @return 包含压缩包内部条目时返回 true，调用方应按解压语义处理内部拖放。
     */
    fun preparePendingFiles(entries: List<VFile>): Boolean

    /**
     * 将 VFS 条目解析为本地文件列表。
     *
     * @param entries 待解析条目。
     * @param archiveService 压缩包服务，解析 archive 条目时需要。
     * @return 可交给系统的本地文件列表。
     */
    suspend fun resolveToLocalFiles(
        entries: List<VFile>,
        archiveService: ArchiveService? = null,
    ): List<File>
}

/**
 * JVM/Swing 外部文件拖放服务实现。
 *
 * @param materializer VFS 到系统本地文件的物化服务。
 * @param materializationDispatcher 远程文件后台物化调度器。
 */
class JvmExternalFileDragService(
    private val materializer: SystemFileMaterializer,
    materializationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExternalFileDragService {
    /** 远程文件预物化使用的后台作用域。 */
    private val materializationScope = CoroutineScope(SupervisorJob() + materializationDispatcher)

    /** 临时物化根目录，应用退出或服务卸载时删除。 */
    private val tempRootDir: Path by lazy {
        val dir = Path.of(System.getProperty("java.io.tmpdir"), "onyx-drag-${UUID.randomUUID()}")
        Files.createDirectories(dir)
        dir
    }

    /** 已经解析为本地文件的待拖放条目。 */
    @Volatile
    private var pendingDragFiles: List<File> = emptyList()

    /** 需要在系统拖放开始时延迟物化的 VFS 条目。 */
    @Volatile
    private var pendingMaterializeEntries: List<VFile> = emptyList()

    /** 当前后台物化任务。 */
    @Volatile
    private var materializationJob: Job? = null

    /** 待拖放状态版本，阻止旧物化任务覆盖新选择。 */
    @Volatile
    private var pendingGeneration: Long = 0L

    /** 用户已经拖到窗口边缘，物化完成后应立即尝试系统拖放。 */
    @Volatile
    private var edgeDragRequested: Boolean = false

    /** 最近一次鼠标按下事件，用于触发 Swing exportAsDrag。 */
    @Volatile
    private var lastMousePressedEvent: MouseEvent? = null

    /** exportAsDrag 是否已为本次拖放触发，避免重复发起系统拖放。 */
    @Volatile
    private var exportTriggered: Boolean = false

    /** 当前是否已交给系统拖放接管。 */
    @Volatile
    private var systemDragActive: Boolean = false

    /** 已安装 TransferHandler 的 Swing 组件。 */
    private var installedComponent: JComponent? = null

    /** 安装桥接前组件原有的 TransferHandler。 */
    private var originalTransferHandler: TransferHandler? = null

    /** 全局鼠标监听器，捕获 Compose SkiaLayer 转发前的鼠标事件。 */
    private var awtEventListener: AWTEventListener? = null

    /** JVM 异常退出时清理临时物化目录的钩子。 */
    private val shutdownHook = Thread(::cleanupTempRoot, "onyx-external-drag-cleanup")

    /** 服务是否已永久释放。 */
    private var disposed = false

    override val isSystemDragActive: Boolean
        get() = systemDragActive

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /**
     * 在窗口内查找 Compose 渲染层并安装 Swing TransferHandler。
     *
     * @param window 主窗口。
     */
    override fun install(window: java.awt.Window) {
        check(!disposed) { "External file drag service is already disposed" }
        detachWindowBridge()
        val skiaLayer = findSkiaLayer(window)
        if (skiaLayer != null) {
            installOnComponent(skiaLayer)
            OnyxLogger.info(LOG_TAG, "已安装到 SkiaLayer: ${skiaLayer.javaClass.name}")
        } else {
            val contentPane = (window as? javax.swing.JFrame)?.contentPane as? JComponent
            if (contentPane != null) {
                installOnComponent(contentPane)
                OnyxLogger.info(LOG_TAG, "已安装到 contentPane: ${contentPane.javaClass.name}")
            } else {
                OnyxLogger.warn(LOG_TAG, "找不到可用的 JComponent，外部拖放不可用")
                return
            }
        }
        installGlobalMouseListener()
    }

    /**
     * 卸载监听器、TransferHandler 和待处理状态。
     */
    override fun uninstall() {
        detachWindowBridge()
        clearPending()
        cleanupTempRoot()
    }

    /**
     * 释放窗口桥接、后台物化作用域和当前实例注册的退出钩子。
     *
     * @return 无返回值。
     */
    override fun dispose() {
        if (disposed) return
        disposed = true
        uninstall()
        materializationScope.cancel()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // JVM 已进入关闭阶段时钩子正在执行，不能再从运行时移除。
        } catch (failure: SecurityException) {
            OnyxLogger.warn(LOG_TAG, "外部拖放退出钩子注销失败", failure)
        }
    }

    /**
     * 清理待拖放文件，系统拖放已经激活时由 exportDone 接管清理。
     */
    override fun clearPending() {
        if (!systemDragActive) {
            pendingGeneration += 1L
            materializationJob?.cancel()
            materializationJob = null
            pendingDragFiles = emptyList()
            pendingMaterializeEntries = emptyList()
            edgeDragRequested = false
            exportTriggered = false
        }
    }

    /**
     * 将 VFS 选择拆分为本地文件和需要延迟物化的条目。
     *
     * @param entries 当前选中的 VFS 条目。
     * @return 包含压缩包内部条目时返回 true。
     */
    override fun preparePendingFiles(entries: List<VFile>): Boolean {
        pendingGeneration += 1L
        val generation = pendingGeneration
        materializationJob?.cancel()
        materializationJob = null
        edgeDragRequested = false
        val localFiles = mutableListOf<File>()
        val materializeEntries = mutableListOf<VFile>()
        var containsArchiveEntry = false
        entries.forEach { entry ->
            val parsed = ArchiveService.parseArchiveLocation(entry.location)
            if (parsed?.second?.isNotBlank() == true) {
                containsArchiveEntry = true
            }
            val localPath = entry.systemLocalPathOrNull()
            if (localPath != null && Files.exists(localPath)) {
                localFiles += localPath.toFile()
            } else if (materializer.supports(entry)) {
                materializeEntries += entry
            }
        }
        pendingDragFiles = localFiles
        pendingMaterializeEntries = materializeEntries
        if (materializeEntries.isNotEmpty()) {
            materializationJob = materializationScope.launch {
                val materializedFiles = materializeEntries.mapNotNull { entry ->
                    materializer.materialize(entry)
                        .onFailure { failure ->
                            OnyxLogger.error(LOG_TAG, "后台物化拖放文件失败：${entry.location}", failure)
                        }
                        .getOrNull()
                        ?.location
                        ?.let(::File)
                        ?.takeIf(File::exists)
                }
                if (pendingGeneration != generation) return@launch
                pendingDragFiles = localFiles + materializedFiles
                pendingMaterializeEntries = emptyList()
                materializationJob = null
                if (edgeDragRequested) {
                    SwingUtilities.invokeLater(::startSystemDrag)
                }
            }
        }
        return containsArchiveEntry
    }

    /**
     * 将 VFS 条目解析为本地文件。
     *
     * @param entries 待解析条目。
     * @param archiveService 压缩包服务。
     * @return 可用本地文件列表。
     */
    override suspend fun resolveToLocalFiles(
        entries: List<VFile>,
        archiveService: ArchiveService?,
    ): List<File> = withContext(Dispatchers.IO) {
        entries.mapNotNull { entry ->
            val parsed = ArchiveService.parseArchiveLocation(entry.location)
            if (parsed != null && archiveService != null) {
                val (archivePath, innerPath) = parsed
                if (innerPath.isBlank()) return@mapNotNull null
                val sessionDir = tempRootDir.resolve(UUID.randomUUID().toString())
                Files.createDirectories(sessionDir)
                val result = archiveService.extractEntriesToTemp(
                    archivePath = archivePath,
                    entryPaths = listOf(innerPath),
                    targetDir = sessionDir.toString(),
                )
                if (result.isSuccess) {
                    val extractedName = innerPath.substringAfterLast('/')
                    val extractedFile = sessionDir.resolve(extractedName).toFile()
                    if (extractedFile.exists()) extractedFile else null
                } else {
                    null
                }
            } else {
                val file = File(entry.location)
                if (file.exists()) file else null
            }
        }
    }

    /**
     * 在指定 Swing 组件上安装 TransferHandler。
     *
     * @param component 目标 Swing 组件。
     */
    internal fun installOnComponent(component: JComponent) {
        restoreOriginalTransferHandler()
        val originalHandler = component.transferHandler
        originalTransferHandler = originalHandler
        component.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?): Int = COPY_OR_MOVE

            override fun createTransferable(c: JComponent?): Transferable? {
                val localFiles = pendingDragFiles
                return if (localFiles.isEmpty()) null else FileTransferable(localFiles)
            }

            override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
                systemDragActive = false
                clearPending()
            }

            override fun canImport(support: TransferSupport?): Boolean {
                return originalHandler?.canImport(support) ?: false
            }

            override fun importData(support: TransferSupport?): Boolean {
                return originalHandler?.importData(support) ?: false
            }
        }
        installedComponent = component
    }

    /**
     * 安装全局鼠标监听器，用于在 Compose 内部拖放接近窗口边缘时升级为系统拖放。
     */
    private fun installGlobalMouseListener() {
        val listener = AWTEventListener { event ->
            if (event !is MouseEvent) return@AWTEventListener
            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    lastMousePressedEvent = event
                    exportTriggered = false
                }

                MouseEvent.MOUSE_DRAGGED -> maybeStartSystemDrag(event)

                MouseEvent.MOUSE_RELEASED -> {
                    lastMousePressedEvent = null
                    edgeDragRequested = false
                    exportTriggered = false
                }
            }
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
        awtEventListener = listener
    }

    /**
     * 在满足边缘触发条件时发起系统拖放。
     *
     * @param event 当前鼠标拖动事件。
     */
    private fun maybeStartSystemDrag(event: MouseEvent) {
        val hasPending = pendingDragFiles.isNotEmpty() || pendingMaterializeEntries.isNotEmpty()
        val component = installedComponent
        val canRequestDrag = hasPending &&
            !systemDragActive &&
            !exportTriggered &&
            component != null &&
            isNearWindowEdge(component, event)
        if (canRequestDrag) {
            edgeDragRequested = true
            if (materializationJob?.isActive != true) {
                startSystemDrag()
            }
        }
    }

    /**
     * 使用已经物化完成的本地文件发起 Swing 系统拖放。
     */
    private fun startSystemDrag() {
        val pressEvent = lastMousePressedEvent
        val component = installedComponent
        val canStart = pressEvent != null &&
            component != null &&
            pendingDragFiles.isNotEmpty() &&
            !systemDragActive &&
            !exportTriggered
        if (canStart) {
            exportTriggered = true
            systemDragActive = true
            edgeDragRequested = false
            SwingUtilities.invokeLater {
                try {
                    requireNotNull(component).transferHandler.exportAsDrag(
                        component,
                        requireNotNull(pressEvent),
                        TransferHandler.COPY,
                    )
                } catch (e: java.awt.dnd.InvalidDnDOperationException) {
                    OnyxLogger.error(LOG_TAG, "exportAsDrag 当前不可用", e)
                    systemDragActive = false
                    exportTriggered = false
                } catch (e: IllegalStateException) {
                    OnyxLogger.error(LOG_TAG, "exportAsDrag 失败", e)
                    systemDragActive = false
                    exportTriggered = false
                }
            }
        }
    }

    /**
     * 移除窗口级鼠标监听并恢复组件原有的 TransferHandler。
     */
    private fun detachWindowBridge() {
        awtEventListener?.let { listener ->
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
        awtEventListener = null
        restoreOriginalTransferHandler()
    }

    /**
     * 恢复安装桥接前组件原有的 TransferHandler。
     */
    private fun restoreOriginalTransferHandler() {
        installedComponent?.transferHandler = originalTransferHandler
        installedComponent = null
        originalTransferHandler = null
    }

    /**
     * 判断拖动事件是否已接近窗口边缘。
     *
     * 只有接近边缘时才升级为系统拖放，避免面板内拖拽被误接管。
     *
     * @param component 已安装拖放桥接的组件。
     * @param event 当前鼠标事件。
     * @return 接近窗口边缘时返回 true。
     */
    private fun isNearWindowEdge(
        component: JComponent,
        event: MouseEvent,
    ): Boolean {
        val window = SwingUtilities.getWindowAncestor(component) ?: return true
        val screenPoint = event.locationOnScreen
        val windowBounds = window.bounds
        return screenPoint.x <= windowBounds.x + DRAG_EDGE_MARGIN_PX ||
            screenPoint.x >= windowBounds.x + windowBounds.width - DRAG_EDGE_MARGIN_PX ||
            screenPoint.y <= windowBounds.y + DRAG_EDGE_MARGIN_PX ||
            screenPoint.y >= windowBounds.y + windowBounds.height - DRAG_EDGE_MARGIN_PX
    }

    /**
     * 递归查找 ComposeWindow 内部的 SkiaLayer 或 ComposeLayer。
     *
     * @param container 当前容器。
     * @return 找到的 Swing 组件。
     */
    private fun findSkiaLayer(container: java.awt.Container): JComponent? {
        return container.components.firstNotNullOfOrNull { component ->
            val className = component.javaClass.name
            if ("SkiaLayer" in className || "ComposeLayer" in className) {
                component as? JComponent
            } else {
                (component as? java.awt.Container)?.let(::findSkiaLayer)
            }
        }
    }

    /**
     * 删除服务创建的临时物化目录。
     */
    private fun cleanupTempRoot() {
        runCatching {
            if (Files.exists(tempRootDir)) {
                Files.walk(tempRootDir).use { stream ->
                    stream.sorted(Comparator.reverseOrder())
                        .forEach { path -> Files.deleteIfExists(path) }
                }
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "ExternalFileDragService"
        private const val DRAG_EDGE_MARGIN_PX = 20
    }
}

/**
 * 多格式文件 Transferable，兼容 Java、Linux 桌面协议和通用文本回退。
 *
 * @param files 待传输的本地文件列表。
 * @param isCut 是否为剪切操作。
 */
class FileTransferable(
    private val files: List<File>,
    private val isCut: Boolean = false,
) : Transferable {
    /** file:// URI 列表。 */
    private val uriListString: String by lazy {
        files.joinToString("\r\n") { it.toURI().toString() }
    }

    /** GNOME copied-files 格式内容。 */
    private val gnomeCopiedBytes: java.nio.ByteBuffer by lazy {
        val action = if (isCut) "cut" else "copy"
        val content = action + "\n" + files.joinToString("\n") { it.toURI().toString() }
        java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = SUPPORTED_FLAVORS.clone()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
        return SUPPORTED_FLAVORS.any { candidate -> candidate == flavor }
    }

    override fun getTransferData(flavor: DataFlavor): Any {
        return when {
            DataFlavor.javaFileListFlavor == flavor -> files.toList()
            URI_LIST_FLAVOR == flavor -> uriListString
            GNOME_FILES_FLAVOR == flavor -> gnomeCopiedBytes.duplicate()
            TEXT_PLAIN_FLAVOR == flavor -> uriListString
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
    }

    private companion object {
        /** 标准 URI 列表格式。 */
        private val URI_LIST_FLAVOR = DataFlavor("text/uri-list;class=java.lang.String")

        /** GNOME 文件复制格式。 */
        private val GNOME_FILES_FLAVOR = DataFlavor("x-special/gnome-copied-files;class=java.nio.ByteBuffer")

        /** 通用文本回退格式。 */
        private val TEXT_PLAIN_FLAVOR = DataFlavor.stringFlavor

        /** 当前支持的数据格式。 */
        private val SUPPORTED_FLAVORS = arrayOf(
            DataFlavor.javaFileListFlavor,
            URI_LIST_FLAVOR,
            GNOME_FILES_FLAVOR,
            TEXT_PLAIN_FLAVOR,
        )
    }
}
