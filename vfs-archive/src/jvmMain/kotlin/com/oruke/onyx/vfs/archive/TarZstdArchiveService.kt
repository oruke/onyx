package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 使用系统 tar 命令处理 `.tar.zst` 与 `.tzst` 归档。
 *
 * 7-Zip-JBinding 16.02 没有稳定的 zstd codec，因此仅这两类归档使用系统 tar。
 *
 * @param tarCommand tar 命令名或可执行文件路径。
 * @param runtimeTimeoutSeconds 运行时能力探测超时秒数。
 */
internal class TarZstdArchiveService(
    private val tarCommand: String,
    private val runtimeTimeoutSeconds: Long,
) {
    /** 系统 tar 能力检测结果缓存，避免每次进入归档都重复启动进程。 */
    @Volatile
    private var runtimeChecked = false

    /**
     * 确认当前运行时可用系统 tar。
     *
     * @param archivePath 归档路径，用于构建用户可读错误。
     */
    fun ensureRuntimeAvailable(archivePath: String) {
        if (runtimeChecked) return
        synchronized(this) {
            if (runtimeChecked) return
            runCatching {
                runTarTextCommand(
                    command = listOf(tarCommand, "--version"),
                    timeoutSeconds = runtimeTimeoutSeconds,
                )
                runtimeChecked = true
            }.getOrElse { failure ->
                throw ArchiveRuntimeException(
                    "无法打开 ${File(archivePath).name}：系统 tar 不可用，无法处理 .tar.zst/.tzst 压缩包",
                    failure,
                )
            }
        }
    }

    /**
     * 列出归档内部目录的直接子条目。
     *
     * @param archivePath 归档物理路径。
     * @param innerPath 归档内部目录路径。
     * @return 当前目录下的直接子条目。
     */
    fun list(
        archivePath: String,
        innerPath: String,
    ): List<VFile> {
        ensureRuntimeAvailable(archivePath)
        val output = runTarTextCommand(
            listOf(tarCommand, "-tf", archivePath),
            timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS,
        )
        val entries = output
            .lineSequence()
            .mapNotNull { line -> line.toTarEntryPath() }
            .toList()
        return entries.toDirectTarChildren(archivePath, innerPath)
    }

    /**
     * 解压全部归档或指定内部路径。
     *
     * @param archivePath 归档物理路径。
     * @param targetDirectory 解压目标目录。
     * @param innerPath 归档内部路径；为空时解压全部内容。
     */
    fun extract(
        archivePath: String,
        targetDirectory: String,
        innerPath: String,
    ) {
        ensureRuntimeAvailable(archivePath)
        val targetDir = File(targetDirectory)
        targetDir.mkdirs()
        val normalizedInnerPath = innerPath.toTarEntryNameOrNull()
        val command = buildList {
            add(tarCommand)
            add("-xf")
            add(archivePath)
            add("-C")
            add(targetDir.absolutePath)
            if (normalizedInnerPath != null) {
                add("--strip-components=${normalizedInnerPath.tarPathDepth()}")
                add(normalizedInnerPath)
            }
        }
        runTarTextCommand(command)
    }

    /**
     * 将归档内单个文件读取到内存。
     *
     * @param archivePath 归档物理路径。
     * @param innerPath 归档内部文件路径。
     * @return 文件字节；目录或空路径返回 `null`。
     */
    fun extractEntryToBytes(
        archivePath: String,
        innerPath: String,
    ): ByteArray? {
        ensureRuntimeAvailable(archivePath)
        val normalizedInnerPath = innerPath.toTarEntryNameOrNull() ?: return null
        return runTarBinaryCommand(
            listOf(tarCommand, "-xOf", archivePath, normalizedInnerPath),
            timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS,
        ).takeIf { bytes -> bytes.isNotEmpty() }
    }

    /**
     * 解压归档内指定条目集合。
     *
     * @param archivePath 归档物理路径。
     * @param entryPaths 需要解压的归档内部条目路径。
     * @param targetDirectory 解压目标目录。
     */
    fun extractEntries(
        archivePath: String,
        entryPaths: List<String>,
        targetDirectory: String,
    ) {
        ensureRuntimeAvailable(archivePath)
        val targetDir = File(targetDirectory)
        targetDir.mkdirs()
        val normalizedEntries = entryPaths.mapNotNull { path -> path.toTarEntryNameOrNull() }
        check(normalizedEntries.isNotEmpty()) { "未找到匹配的条目: $entryPaths" }
        val parentPrefix = normalizedEntries.commonTarParentPrefix()
        val command = buildList {
            add(tarCommand)
            add("-xf")
            add(archivePath)
            add("-C")
            add(targetDir.absolutePath)
            val stripComponents = parentPrefix.tarPathDepth()
            if (stripComponents > 0) {
                add("--strip-components=$stripComponents")
            }
            addAll(normalizedEntries)
        }
        runTarTextCommand(command)
    }

    /** 系统 tar 单次归档操作的超时秒数。 */
    private companion object {
        private const val TAR_OPERATION_TIMEOUT_SECONDS = 120L
    }
}

/**
 * tar 条目路径。
 */
private data class TarEntryPath(
    /** 不带首尾 `/` 的归档内部路径。 */
    val path: String,

    /** 该条目是否来自 tar 目录项。 */
    val isDirectory: Boolean,
)

/**
 * 将 tar 输出行转换为内部条目路径。
 *
 * @return 可用条目；空行或根目录返回 `null`。
 */
private fun String.toTarEntryPath(): TarEntryPath? {
    val normalized = trim()
        .takeIf { value -> value.isNotBlank() }
        ?.replace('\\', '/')
        ?.removePrefix("./")
        ?.trimStart('/')
    val path = normalized?.trim('/')
    return path?.takeIf { value -> value.isNotBlank() }?.let { validPath ->
        TarEntryPath(
            path = validPath,
            isDirectory = normalized.endsWith("/"),
        )
    }
}

/**
 * 将用户选中的内部路径规范化为 tar 可识别的参数。
 *
 * @return 非空内部路径；根路径返回 `null`。
 */
private fun String.toTarEntryNameOrNull(): String? {
    return replace('\\', '/')
        .removePrefix("./")
        .trim('/')
        .takeIf { value -> value.isNotBlank() }
}

/**
 * 从完整条目集合中计算当前目录的直接子节点。
 *
 * @param archivePath 归档物理路径。
 * @param innerPath 当前浏览的归档内部目录。
 * @return 可展示到文件列表的直接子节点。
 */
private fun List<TarEntryPath>.toDirectTarChildren(
    archivePath: String,
    innerPath: String,
): List<VFile> {
    val normalizedInnerPath = innerPath.toTarEntryNameOrNull().orEmpty()
    val prefix = if (normalizedInnerPath.isBlank()) "" else "$normalizedInnerPath/"
    val parentLocation = ArchiveService.archiveLocation(archivePath, normalizedInnerPath)
    val directChildren = linkedMapOf<String, VFile>()
    forEach { entry ->
        entry.toDirectTarChild(archivePath, prefix, parentLocation)?.let { (name, child) ->
            directChildren.putIfAbsent(name, child)
        }
    }
    return directChildren.values.toList()
}

/**
 * 将完整 tar 条目映射为当前目录的直接子节点。
 *
 * @param archivePath 归档物理路径。
 * @param prefix 当前内部目录前缀。
 * @param parentLocation 当前内部目录位置。
 * @return 子节点名称与文件对象；不属于当前目录时返回 `null`。
 */
private fun TarEntryPath.toDirectTarChild(
    archivePath: String,
    prefix: String,
    parentLocation: String,
): Pair<String, VFile>? {
    val relativePath = path
        .takeIf { entryPath -> entryPath.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { entryPath -> entryPath.isNotBlank() }
    val segments = relativePath
        ?.split("/")
        ?.filter { segment -> segment.isNotBlank() }
        ?.takeIf { pathSegments -> pathSegments.isNotEmpty() }
    return segments?.let { pathSegments ->
        val directChildName = pathSegments.first()
        val childIsDirectory = pathSegments.size > 1 || isDirectory
        val childEntryPath = if (prefix.isBlank()) directChildName else prefix + directChildName
        val childLocation = ArchiveService.archiveLocation(archivePath, childEntryPath)
        directChildName to VFile(
            id = childLocation,
            name = directChildName,
            location = childLocation,
            parentLocation = parentLocation,
            kind = if (childIsDirectory) VFileKind.DIRECTORY else VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = if (childIsDirectory) {
                setOf(VFileCapability.LIST_CHILDREN, VFileCapability.READ_METADATA)
            } else {
                setOf(VFileCapability.READ_CONTENT, VFileCapability.READ_METADATA)
            },
        )
    }
}

/**
 * 计算多个 tar 条目的公共父目录前缀。
 *
 * @return 以 `/` 结尾的公共父路径；无公共父目录时返回空字符串。
 */
private fun List<String>.commonTarParentPrefix(): String {
    val parents = map { path ->
        val lastSlash = path.trimEnd('/').lastIndexOf('/')
        if (lastSlash >= 0) path.substring(0, lastSlash + 1) else ""
    }
    val common = parents.minByOrNull { parent -> parent.length } ?: ""
    return if (parents.all { parent -> parent.startsWith(common) }) common else ""
}

/**
 * 计算 tar 路径的层级深度，用于 `--strip-components`。
 *
 * @return 路径段数量。
 */
private fun String.tarPathDepth(): Int {
    val normalized = trim('/').takeIf { value -> value.isNotBlank() } ?: return 0
    return normalized.split('/').count { segment -> segment.isNotBlank() }
}

/**
 * 执行输出文本的系统 tar 命令。
 *
 * @param command 完整命令参数。
 * @param timeoutSeconds 超时秒数；为空时一直等待进程结束。
 * @return 标准输出文本。
 */
private fun runTarTextCommand(
    command: List<String>,
    timeoutSeconds: Long? = null,
): String {
    return String(runTarCommand(command, timeoutSeconds), Charsets.UTF_8).trimEnd()
}

/**
 * 执行输出二进制内容的系统 tar 命令。
 *
 * @param command 完整命令参数。
 * @param timeoutSeconds 超时秒数；为空时一直等待进程结束。
 * @return 标准输出字节。
 */
private fun runTarBinaryCommand(
    command: List<String>,
    timeoutSeconds: Long? = null,
): ByteArray {
    return runTarCommand(command, timeoutSeconds)
}

/**
 * 执行系统 tar 命令并校验退出码。
 *
 * @param command 完整命令参数。
 * @param timeoutSeconds 超时秒数；为空时一直等待进程结束。
 * @return 标准输出字节。
 */
private fun runTarCommand(
    command: List<String>,
    timeoutSeconds: Long?,
): ByteArray {
    val process = ProcessBuilder(command).start()
    val stdoutFuture = CompletableFuture.supplyAsync {
        process.inputStream.use { stream -> stream.readBytes() }
    }
    val stderrFuture = CompletableFuture.supplyAsync {
        process.errorStream.use { stream -> stream.readBytes() }
    }
    val completed = if (timeoutSeconds == null) {
        process.waitFor()
        true
    } else {
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    }
    check(completed) {
        process.destroyForcibly()
        "tar 命令超时: ${command.joinToString(" ")}"
    }
    val stdout = stdoutFuture.get()
    val stderr = String(stderrFuture.get(), Charsets.UTF_8).trim()
    check(process.exitValue() == 0) {
        stderr.ifBlank { "tar 命令执行失败: ${command.joinToString(" ")}" }
    }
    return stdout
}
