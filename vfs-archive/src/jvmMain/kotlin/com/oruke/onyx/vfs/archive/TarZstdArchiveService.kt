package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
     * 解压全部归档或指定内部路径，在落盘前预检全部待解压条目防止 Tar Slip 路径穿越与符号链接逃逸。
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
        val normalizedInnerPath = if (innerPath.isBlank()) {
            null
        } else {
            innerPath.toSafeTarEntryNameOrNull()
                ?: throw SecurityException("安全拦截：非法的归档内部路径 ($innerPath)")
        }
        val targetDir = File(targetDirectory)
        val stripComponents = normalizedInnerPath?.tarPathDepth() ?: 0
        validateTarArchiveBeforeExtract(
            archivePath = archivePath,
            targetDir = targetDir,
            filterEntries = normalizedInnerPath?.let(::listOf),
            stripComponents = stripComponents,
        )
        targetDir.mkdirs()
        val command = buildList {
            add(tarCommand)
            add("-xf")
            add(archivePath)
            add("-C")
            add(targetDir.absolutePath)
            if (normalizedInnerPath != null) {
                add("--strip-components=$stripComponents")
                add("--")
                add(normalizedInnerPath)
            }
        }
        runTarTextCommand(command, timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS)
    }

    /**
     * 将归档内单个文件读取到内存，支持在流式读取阶段按 [maxBytes] 提前中断以防 OOM，并正确保留 0 字节空文件。
     *
     * @param archivePath 归档物理路径。
     * @param innerPath 归档内部文件路径。
     * @param maxBytes 允许读取的最大字节数；为 `null` 时不限制。
     * @return 文件字节（允许长度为 0 的空文件）；条目不存在、为目录或路径非法时返回 `null`。
     */
    fun extractEntryToBytes(
        archivePath: String,
        innerPath: String,
        maxBytes: Long? = null,
    ): ByteArray? {
        ensureRuntimeAvailable(archivePath)
        val normalizedInnerPath = innerPath.toSafeTarEntryNameOrNull() ?: return null
        val rawMemberName = findRawTarFileMemberName(archivePath, normalizedInnerPath) ?: return null
        return runTarBinaryCommand(
            command = listOf(tarCommand, "-xOf", archivePath, "--", rawMemberName),
            timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS,
            maxStdoutBytes = maxBytes,
        )
    }

    /**
     * 解压归档内指定条目集合，并在落盘前预检所有匹配条目防止路径穿越与选项注入。
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
        val normalizedEntries = entryPaths.map { path ->
            path.toSafeTarEntryNameOrNull()
                ?: throw SecurityException("安全拦截：非法的归档内部路径 ($path)")
        }
        check(normalizedEntries.isNotEmpty()) { "未找到匹配的条目: $entryPaths" }
        val targetDir = File(targetDirectory)
        val parentPrefix = normalizedEntries.commonTarParentPrefix()
        val stripComponents = parentPrefix.tarPathDepth()
        validateTarArchiveBeforeExtract(
            archivePath = archivePath,
            targetDir = targetDir,
            filterEntries = normalizedEntries,
            stripComponents = stripComponents,
        )
        targetDir.mkdirs()
        val command = buildList {
            add(tarCommand)
            add("-xf")
            add(archivePath)
            add("-C")
            add(targetDir.absolutePath)
            if (stripComponents > 0) {
                add("--strip-components=$stripComponents")
            }
            add("--")
            addAll(normalizedEntries)
        }
        runTarTextCommand(command, timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS)
    }

    /**
     * 在执行 `tar -xf` 前预检归档条目列表，拒绝任何绝对路径、父级逃逸、Windows 盘符/ADS、
     * 符号链接及硬链接，避免产生越界或半写入脏文件。
     *
     * @param archivePath 归档物理路径。
     * @param targetDir 解压目标目录。
     * @param filterEntries 若非空则仅检查命中这些前缀的条目；为空时检查全包。
     * @param stripComponents 解压命令传入的 `--strip-components` 层级数。
     */
    private fun validateTarArchiveBeforeExtract(
        archivePath: String,
        targetDir: File,
        filterEntries: List<String>?,
        stripComponents: Int,
    ) {
        val verboseListing = runTarTextCommand(
            listOf(tarCommand, "-tvf", archivePath),
            timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS,
        )
        val rawNames = runTarTextCommand(
            listOf(tarCommand, "-tf", archivePath),
            timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS,
        ).lineSequence()
            .map { line -> line.trimEnd('\r') }
            .filter { line -> line.isNotBlank() }
            .toList()
        val verboseLines = verboseListing.lineSequence()
            .map { line -> line.trimEnd('\r') }
            .filter { line -> line.isNotBlank() }
            .toList()
        if (verboseLines.size != rawNames.size) {
            throw SecurityException("安全拦截：tar 条目清单校验行数不一致 (${verboseLines.size} != ${rawNames.size})")
        }

        validateTarRawEntries(
            rawEntries = rawNames,
            verboseLines = verboseLines,
            targetDir = targetDir,
            filterEntries = filterEntries,
            stripComponents = stripComponents,
        )
    }

    /**
     * 在归档目录清单中精确查找普通文件条目的原始 tar 成员名（兼容 `./` 前缀）。
     *
     * @param archivePath 归档物理路径。
     * @param normalizedTarget 已规范化的内部文件路径。
     * @return 归档中的原始成员名；不存在或为目录时返回 `null`。
     */
    private fun findRawTarFileMemberName(
        archivePath: String,
        normalizedTarget: String,
    ): String? {
        val output = runTarTextCommand(
            listOf(tarCommand, "-tf", archivePath),
            timeoutSeconds = TAR_OPERATION_TIMEOUT_SECONDS,
        )
        for (rawLine in output.lineSequence()) {
            val rawName = rawLine.trimEnd('\r')
            if (rawName.isBlank()) continue
            val unified = rawName.replace('\\', '/')
            if (unified.endsWith("/")) continue
            val normalizedCandidate = unified.stripLeadingDotSlash().trimEnd('/')
            if (normalizedCandidate == normalizedTarget) {
                return rawName
            }
        }
        return null
    }

    /** 系统 tar 单次归档操作的超时秒数。 */
    private companion object {
        private const val TAR_OPERATION_TIMEOUT_SECONDS = 120L
    }
}

/**
 * 校验 tar 归档原始条目列表与 `-tvf` 详情输出，阻断路径穿越、绝对路径、符号链接与硬链接。
 *
 * @param rawEntries `tar -tf` 返回的原始条目路径列表。
 * @param verboseLines `tar -tvf` 返回的详细条目列表（用于检测符号链接/硬链接类型标志）。
 * @param targetDir 目标解压目录。
 * @param filterEntries 可选待解压子集前缀；为 `null` 时校验全包。
 * @param stripComponents 拟应用的 `--strip-components` 剥离层级数。
 */
internal fun validateTarRawEntries(
    rawEntries: List<String>,
    verboseLines: List<String>,
    targetDir: File,
    filterEntries: List<String>?,
    stripComponents: Int,
) {
    rawEntries.forEachIndexed { index, rawEntry ->
        val unified = rawEntry.replace('\\', '/')
        val trimmedForMatch = unified.stripLeadingDotSlash().trimEnd('/')
        val selected = filterEntries == null || filterEntries.any { prefix ->
            trimmedForMatch == prefix || trimmedForMatch.startsWith("$prefix/")
        }
        if (!selected) return@forEachIndexed

        val verboseLine = verboseLines.getOrNull(index)
        val entryTypeChar = verboseLine?.firstOrNull()
        if (entryTypeChar == 'l' || entryTypeChar == 'h') {
            throw SecurityException("安全拦截：禁止解压符号链接或硬链接条目 ($rawEntry)")
        }

        val isDirectory = unified.endsWith("/") || entryTypeChar == 'd'
        val safeRelative = stripTarEntryComponents(unified, stripComponents)
        if (isDirectory && safeRelative != null && safeRelative.isEmpty()) {
            return@forEachIndexed
        }
        val outputFile = safeRelative?.let { relative -> resolveSafeArchiveOutputFile(targetDir, relative) }
            ?: throw SecurityException("安全拦截：tar 归档条目路径越界 ($rawEntry)")
        if (!verifyArchiveOutputParentContained(targetDir, outputFile)) {
            throw SecurityException("安全拦截：目标目录内存在越界符号链接 ($rawEntry)")
        }
    }
}

/**
 * 剥离 tar 条目开头的指定层级并检查越界特征；含绝对路径、盘符、冒号或 `..` 时返回 `null`。
 *
 * @param unifiedPath 统一正斜杠形式的原始 tar 条目路径。
 * @param stripComponents 需剥离的前置路径段数量。
 * @return 剥离后的安全相对路径；非法时返回 `null`。
 */
private fun stripTarEntryComponents(
    unifiedPath: String,
    stripComponents: Int,
): String? {
    val trimmed = unifiedPath.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("/") || TAR_WINDOWS_DRIVE_PREFIX.matches(trimmed)) {
        return null
    }
    val rawSegments = trimmed.split('/').filter { segment -> segment.isNotEmpty() }
    if (rawSegments.any { segment -> segment == ".." || ':' in segment || segment.any { ch -> ch.code < 0x20 } }) {
        return null
    }
    val cleanSegments = rawSegments.filter { segment -> segment != "." }
    if (cleanSegments.size <= stripComponents) {
        return ""
    }
    return cleanSegments.drop(stripComponents).joinToString("/")
}

/** Windows 盘符前缀匹配。 */
private val TAR_WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:.*")

/** 循环剥离相对路径开头的 `./` 前缀。 */
private fun String.stripLeadingDotSlash(): String {
    var current = this
    while (current.startsWith("./")) {
        current = current.removePrefix("./")
    }
    return current
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
        ?.stripLeadingDotSlash()
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
 * 严格校验并规范化调用方传入的 tar 内部路径，拒绝绝对路径、Windows 盘符、冒号、控制字符与 `..` 段。
 *
 * @return 安全的规范化内部路径；路径非法或为空时返回 `null`。
 */
internal fun String.toSafeTarEntryNameOrNull(): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    val unified = trimmed.replace('\\', '/')
    if (unified.startsWith("/") || TAR_WINDOWS_DRIVE_PREFIX.matches(unified)) {
        return null
    }
    val rawSegments = unified.split('/').filter { segment -> segment.isNotEmpty() }
    if (
        rawSegments.any { segment ->
            segment == ".." || ':' in segment || segment.any { ch -> ch.code < 0x20 }
        }
    ) {
        return null
    }
    val segments = rawSegments.filter { segment -> segment != "." }
    if (segments.isEmpty()) return null
    return segments.joinToString("/")
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
    val normalizedInnerPath = innerPath.toSafeTarEntryNameOrNull().orEmpty()
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
 * @param maxStdoutBytes 允许读取的标准输出最大字节数；为空时不限制。
 * @return 标准输出字节。
 */
private fun runTarBinaryCommand(
    command: List<String>,
    timeoutSeconds: Long? = null,
    maxStdoutBytes: Long? = null,
): ByteArray {
    return runTarCommand(command, timeoutSeconds, maxStdoutBytes)
}

/** 销毁或结束子进程后等待流收敛的最大秒数。 */
private const val TAR_STREAM_DRAIN_TIMEOUT_SECONDS = 5L

/**
 * 执行系统 tar 命令并校验退出码，支持超时先销毁进程与流式字节上限中断，避免死锁和僵尸进程。
 *
 * @param command 完整命令参数。
 * @param timeoutSeconds 超时秒数；为空时一直等待进程结束。
 * @param maxStdoutBytes 允许读取的标准输出最大字节数；为空时不限制。
 * @return 标准输出字节。
 */
private fun runTarCommand(
    command: List<String>,
    timeoutSeconds: Long?,
    maxStdoutBytes: Long? = null,
): ByteArray {
    val process = ProcessBuilder(command).start()
    val limitExceeded = AtomicBoolean(false)
    try {
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.use { stream ->
                readStreamBounded(stream, maxStdoutBytes) {
                    limitExceeded.set(true)
                    process.destroyForcibly()
                }
            }
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
        if (!completed) {
            process.destroyForcibly()
            runCatching { stdoutFuture.get(TAR_STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            runCatching { stderrFuture.get(TAR_STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            if (limitExceeded.get()) {
                throw ArchiveExtractLimitExceededException(requireNotNull(maxStdoutBytes))
            }
            error("tar 命令超时: ${command.joinToString(" ")}")
        }
        val stdout = try {
            stdoutFuture.get(TAR_STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is ArchiveExtractLimitExceededException) {
                process.destroyForcibly()
                runCatching { stderrFuture.get(TAR_STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                throw cause
            }
            throw failure
        }
        if (limitExceeded.get()) {
            throw ArchiveExtractLimitExceededException(requireNotNull(maxStdoutBytes))
        }
        val stderr = runCatching {
            String(stderrFuture.get(TAR_STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS), Charsets.UTF_8).trim()
        }.getOrDefault("")
        check(process.exitValue() == 0) {
            stderr.ifBlank { "tar 命令执行失败: ${command.joinToString(" ")}" }
        }
        return stdout
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}

/**
 * 分块读取输入流，当提供上限且超出时立即触发中断回调并抛出超限信号。
 *
 * @param stream 待读取输入流。
 * @param maxBytes 最大允许读取字节数；为空时不限制。
 * @param onLimitExceeded 超限时执行的清理动作。
 * @return 读取到的全部字节。
 */
private fun readStreamBounded(
    stream: InputStream,
    maxBytes: Long?,
    onLimitExceeded: () -> Unit,
): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var totalRead = 0L
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        if (maxBytes != null && totalRead + read.toLong() > maxBytes) {
            onLimitExceeded()
            throw ArchiveExtractLimitExceededException(maxBytes)
        }
        output.write(chunk, 0, read)
        totalRead += read.toLong()
    }
    return output.toByteArray()
}
