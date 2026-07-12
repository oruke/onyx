package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.SystemMenuAction
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.streams.toList

/**
 * macOS Automator Services 与应用内 Quick Actions 菜单提供器。
 *
 * @param materializer 判断当前 VFS 条目是否可转换为本地文件。
 */
internal class JvmMacSystemMenuProvider(
    private val materializer: SystemFileMaterializer,
) {
    /**
     * 查询当前目标适用的 macOS Services。
     *
     * @param entries 当前菜单目标。
     * @return 可展示的 Workflow 动作。
     */
    fun listActions(entries: List<VFile>): List<SystemMenuAction> {
        if (entries.any { entry -> !materializer.supports(entry) }) return emptyList()
        return macServiceWorkflows().asSequence()
            .mapNotNull { path -> path.toMacServiceAction() }
            .distinctBy { action -> action.displayName to action.command }
            .sortedBy { action -> action.displayName.lowercase(Locale.getDefault()) }
            .toList()
    }

    /**
     * 执行 macOS Automator Workflow Service。
     *
     * @param action 被点击动作。
     * @param entries 当前菜单目标。
     */
    fun execute(action: SystemMenuAction, entries: List<VFile>) {
        val workflow = Path.of(action.command)
        check(Files.isDirectory(workflow)) {
            "macOS Service workflow is not available: ${action.command}"
        }
        entries.map { entry -> entry.requireSystemLocalPath("macOS Services") }.forEach { target ->
            ProcessBuilder("automator", "-i", target.toString(), workflow.toString())
                .directory(target.parent?.toFile() ?: File("."))
                .start()
        }
    }

    /**
     * 将 Automator Workflow 包转换为系统菜单动作。
     *
     * @return 可展示动作。
     */
    private fun Path.toMacServiceAction(): SystemMenuAction? {
        if (!Files.isDirectory(this)) return null
        val displayName = fileName.toString().removeSuffix(".workflow")
            .takeIf { name -> name.isNotBlank() }
            ?: return null
        return SystemMenuAction(
            id = "$ACTION_PREFIX${toAbsolutePath()}",
            displayName = displayName,
            command = toAbsolutePath().toString(),
            iconPath = toAbsolutePath().toString(),
        )
    }

    /**
     * 返回 macOS Services 搜索目录。
     *
     * @return Services 目录候选。
     */
    private fun macServiceDirs(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(
            Path.of(home, "Library/Services"),
            Path.of("/Library/Services"),
            Path.of("/System/Library/Services"),
        )
    }

    /**
     * 汇总独立与应用包内的 Service Workflow。
     *
     * @return 可展示 Workflow 文件。
     */
    private fun macServiceWorkflows(): List<Path> {
        return (macServiceDirs() + macBundledServiceDirs())
            .distinct()
            .flatMap { dir -> dir.workflowFiles() }
    }

    /**
     * 返回应用包内的 macOS Service 目录。
     *
     * @return 应用包 Services 目录。
     */
    private fun macBundledServiceDirs(): List<Path> {
        return macApplicationDirs().flatMap { dir ->
            runCatching {
                Files.list(dir).use { stream ->
                    stream
                        .filter { path -> path.fileName.toString().endsWith(".app", ignoreCase = true) }
                        .map { path -> path.resolve("Contents/Library/Services") }
                        .filter { path -> Files.isDirectory(path) }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * 返回 macOS 应用搜索目录。
     *
     * @return 已存在的应用目录。
     */
    private fun macApplicationDirs(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(
            Path.of(home, "Applications"),
            Path.of("/Applications"),
            Path.of("/System/Applications"),
        ).filter { dir -> Files.isDirectory(dir) }
    }

    /**
     * 读取目录下的 Automator Workflow。
     *
     * @return 当前目录中的 Workflow 文件。
     */
    private fun Path.workflowFiles(): List<Path> {
        if (!Files.isDirectory(this)) return emptyList()
        return runCatching {
            Files.list(this).use { stream ->
                stream.filter { path -> path.fileName.toString().endsWith(".workflow", ignoreCase = true) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    internal companion object {
        /** macOS Automator Service 动作 ID 前缀。 */
        const val ACTION_PREFIX = "macos-service:"
    }
}
