package com.oruke.onyx.app.platform

import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.SystemFileMaterializer
import com.oruke.onyx.app.filesystem.systemLocalPathOrNull
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Component
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
 * 系统级文件拖放助手 — 基于 Swing TransferHandler。
 *
 * ## 为什么不用 DragGestureRecognizer？
 * Compose Desktop 的 SkiaLayer 拦截并重新分发所有鼠标事件，
 * 标准 AWT DragGestureRecognizer 无法接收到原始事件序列，
 * 因此 dragGestureRecognized 回调永远不会触发。
 *
 * ## 本方案的工作原理
 * 1. 在 SkiaLayer（Compose 渲染层）上安装自定义 TransferHandler
 * 2. 全局 AWTEventListener 捕获每次 MOUSE_PRESSED 事件
 * 3. Compose 检测到文件拖拽后设置 [pendingDragFiles]
 * 4. 下一次 MOUSE_DRAGGED 事件到来时，调用 TransferHandler.exportAsDrag()
 *    → 绕过手势识别，直接触发系统级 DnD
 * 5. 系统 DnD 接管指针后，Compose 的 awaitPointerEvent() 收不到后续事件，
 *    拖拽循环自然退出
 */
object ExternalDragHelper {

    /** 临时解压根目录 */
    private val tempRootDir: Path by lazy {
        val dir = Path.of(System.getProperty("java.io.tmpdir"), "onyx-drag-${UUID.randomUUID()}")
        Files.createDirectories(dir)
        dir
    }

    /** 待拖放的文件列表 — Compose onFileDragStart 设置，TransferHandler 读取 */
    @Volatile
    var pendingDragFiles: List<File> = emptyList()

    /**
     * 待物化为系统本地文件的 VFS 条目。
     * 在 createTransferable 中才实际执行导出，避免阻塞 Compose 拖拽检测。
     */
    @Volatile
    var pendingMaterializeEntries: List<VFile> = emptyList()

    /** 用于延迟物化远程文件或压缩包条目的服务引用 */
    @Volatile
    var materializerRef: SystemFileMaterializer? = null

    /** 是否正在进行系统级拖放 */
    @Volatile
    var isSystemDragActive: Boolean = false
        private set

    /** 最近一次鼠标按下事件（用于 exportAsDrag） */
    @Volatile
    private var lastMousePressedEvent: MouseEvent? = null

    /** 已安装 TransferHandler 的组件 */
    private var installedComponent: JComponent? = null

    /** 全局事件监听器 */
    private var awtEventListener: AWTEventListener? = null

    /** exportAsDrag 是否已为本次拖拽调用过（防止重复触发） */
    @Volatile
    private var exportTriggered: Boolean = false

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching {
                if (Files.exists(tempRootDir)) {
                    Files.walk(tempRootDir).use { stream ->
                        stream.sorted(Comparator.reverseOrder())
                            .forEach { Files.deleteIfExists(it) }
                    }
                }
            }
        })
    }

    /**
     * 安装外部拖放支持。
     * 应在窗口创建后、Compose UI 渲染前调用。
     *
     * @param window  主窗口（ComposeWindow / JFrame）
     */
    fun install(window: java.awt.Window) {
        val skiaLayer = findSkiaLayer(window)
        if (skiaLayer != null) {
            installOnComponent(skiaLayer)
            OnyxLogger.info("ExternalDragHelper", "已安装到 SkiaLayer: ${skiaLayer.javaClass.name}")
        } else {
            // 回退：尝试 contentPane
            val contentPane = (window as? javax.swing.JFrame)?.contentPane as? JComponent
            if (contentPane != null) {
                installOnComponent(contentPane)
                OnyxLogger.info("ExternalDragHelper", "已安装到 contentPane: ${contentPane.javaClass.name}")
            } else {
                OnyxLogger.warn("ExternalDragHelper", "找不到可用的 JComponent，外部拖放不可用")
                return
            }
        }
        installGlobalMouseListener()
    }

    /** 卸载拖放支持 */
    fun uninstall() {
        awtEventListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
        awtEventListener = null
        installedComponent?.transferHandler = null
        installedComponent = null
    }

    /**
     * 在指定 JComponent 上安装 TransferHandler。
     */
    private fun installOnComponent(component: JComponent) {
        // 保存原来的 TransferHandler（如有）
        val originalHandler = component.transferHandler

        component.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?): Int = COPY_OR_MOVE

            override fun createTransferable(c: JComponent?): Transferable? {
                // 先收集已解析的本地文件
                val localFiles = pendingDragFiles.toMutableList()
                // 延迟物化远程文件和压缩包条目（此方法在 DnD 线程中调用，不阻塞 EDT）
                val materializeEntries = pendingMaterializeEntries
                val materializer = materializerRef
                if (materializeEntries.isNotEmpty() && materializer != null) {
                    for (entry in materializeEntries) {
                        try {
                            val materialized = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                                materializer.materialize(entry)
                            }.getOrThrow()
                            val file = File(materialized.location)
                            if (file.exists()) {
                                localFiles.add(file)
                            }
                        } catch (e: Exception) {
                            OnyxLogger.error("ExternalDragHelper", "延迟物化失败", e)
                        }
                    }
                }
                return if (localFiles.isEmpty()) null else FileTransferable(localFiles)
            }

            override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
                isSystemDragActive = false
                pendingDragFiles = emptyList()
                pendingMaterializeEntries = emptyList()
                materializerRef = null
                exportTriggered = false
            }

            // 保持原有的导入能力（如有）
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
     * 安装全局鼠标事件监听器。
     * 捕获 MOUSE_PRESSED 事件以备 exportAsDrag 使用；
     * 在 MOUSE_DRAGGED 时触发系统拖放。
     */
    private fun installGlobalMouseListener() {
        val listener = AWTEventListener { event ->
            if (event !is MouseEvent) return@AWTEventListener

            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    lastMousePressedEvent = event
                    exportTriggered = false
                }

                MouseEvent.MOUSE_DRAGGED -> {
                    val hasPending = pendingDragFiles.isNotEmpty() || pendingMaterializeEntries.isNotEmpty()
                    if (hasPending && !isSystemDragActive && !exportTriggered) {
                        val pressEvent = lastMousePressedEvent ?: return@AWTEventListener
                        val component = installedComponent ?: return@AWTEventListener

                        // 只有当鼠标接近窗口边界（20px 内）或已离开窗口时才触发系统拖放，
                        // 避免与 Compose 内部面板间拖拽冲突
                        val window = SwingUtilities.getWindowAncestor(component)
                        if (window != null) {
                            val screenPoint = event.locationOnScreen
                            val windowBounds = window.bounds
                            val margin = 20
                            val nearEdge = screenPoint.x <= windowBounds.x + margin ||
                                    screenPoint.x >= windowBounds.x + windowBounds.width - margin ||
                                    screenPoint.y <= windowBounds.y + margin ||
                                    screenPoint.y >= windowBounds.y + windowBounds.height - margin
                            if (!nearEdge) return@AWTEventListener
                        }

                        exportTriggered = true
                        isSystemDragActive = true
                        // 在 EDT 上调用 exportAsDrag
                        SwingUtilities.invokeLater {
                            try {
                                component.transferHandler.exportAsDrag(
                                    component,
                                    pressEvent,
                                    TransferHandler.COPY,
                                )
                            } catch (e: Exception) {
                                OnyxLogger.error("ExternalDragHelper", "exportAsDrag 失败", e)
                                e.printStackTrace()
                                isSystemDragActive = false
                                exportTriggered = false
                            }
                        }
                    }
                }

                MouseEvent.MOUSE_RELEASED -> {
                    lastMousePressedEvent = null
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

    /** 清除待拖放文件（Compose 内部拖放完成时调用） */
    fun clearPending() {
        if (!isSystemDragActive) {
            pendingDragFiles = emptyList()
            pendingMaterializeEntries = emptyList()
            materializerRef = null
            exportTriggered = false
        }
    }

    /**
     * 将 UI 选中的 VFile 预处理为系统拖放所需的待处理项。
     *
     * UI 层只传递虚拟文件对象；本地 File 解析和 archive:// 条目拆解集中在平台层。
     *
     * @return 是否包含压缩包内部条目。包含时内部拖放语义应视为解压。
     */
    fun preparePendingFiles(
        entries: List<VFile>,
        materializer: SystemFileMaterializer,
    ): Boolean {
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
        materializerRef = materializer
        return containsArchiveEntry
    }

    /**
     * 将 VFile 列表转换为 java.io.File 列表。
     * 本地文件直接转换；archive:// 文件先解压到临时目录。
     */
    suspend fun resolveToLocalFiles(
        entries: List<VFile>,
        archiveService: ArchiveService? = null,
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

    // ── 内部工具 ─────────────────────────────────────────────────

    /**
     * 递归查找 ComposeWindow 内部的 SkiaLayer 组件。
     * Compose Desktop (JBR) 的渲染层是一个继承 JPanel 的 SkiaLayer。
     */
    private fun findSkiaLayer(container: java.awt.Container): JComponent? {
        for (component in container.components) {
            val className = component.javaClass.name
            if ("SkiaLayer" in className || "ComposeLayer" in className) {
                return component as? JComponent
            }
            if (component is java.awt.Container) {
                val found = findSkiaLayer(component)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * 多格式 Transferable — 支持 Linux 桌面所有主要 DnD/Clipboard 协议。
     *
     * 提供的 DataFlavor:
     * 1. javaFileListFlavor — Java 应用
     * 2. text/uri-list — 标准 X11 DnD 协议（大多数 Linux 应用）
     * 3. x-special/gnome-copied-files — GNOME 系应用（Nautilus, Nemo 等）
     * 4. text/plain — 文本编辑器等回退
     *
     * @param isCut 是否为剪切操作（影响 gnome-copied-files 的 copy/cut 前缀）
     */
    class FileTransferable(
        private val files: List<File>,
        private val isCut: Boolean = false,
    ) : Transferable {

        companion object {
            /** text/uri-list — 标准 URI 列表格式 */
            val URI_LIST_FLAVOR = DataFlavor("text/uri-list;class=java.lang.String")

            /** x-special/gnome-copied-files — GNOME 应用使用 */
            val GNOME_FILES_FLAVOR = DataFlavor(
                "x-special/gnome-copied-files;class=java.nio.ByteBuffer",
            )

            /** text/plain — 通用文本回退 */
            val TEXT_PLAIN_FLAVOR = DataFlavor.stringFlavor

            private val SUPPORTED_FLAVORS = arrayOf(
                DataFlavor.javaFileListFlavor,
                URI_LIST_FLAVOR,
                GNOME_FILES_FLAVOR,
                TEXT_PLAIN_FLAVOR,
            )
        }

        /** file:// URI 列表 */
        private val uriListString: String by lazy {
            files.joinToString("\r\n") { it.toURI().toString() }
        }

        /** GNOME 格式：第一行 copy/cut，后面是 file:// URI */
        private val gnomeCopiedBytes: java.nio.ByteBuffer by lazy {
            val action = if (isCut) "cut" else "copy"
            val content = action + "\n" + files.joinToString("\n") { it.toURI().toString() }
            java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
        }

        override fun getTransferDataFlavors(): Array<DataFlavor> = SUPPORTED_FLAVORS.clone()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            SUPPORTED_FLAVORS.any { it.equals(flavor) }

        override fun getTransferData(flavor: DataFlavor): Any = when {
            DataFlavor.javaFileListFlavor.equals(flavor) -> files.toList()
            URI_LIST_FLAVOR.equals(flavor) -> uriListString
            GNOME_FILES_FLAVOR.equals(flavor) -> gnomeCopiedBytes.duplicate()
            TEXT_PLAIN_FLAVOR.equals(flavor) -> uriListString
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
    }
}
