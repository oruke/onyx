package com.oruke.onyx.app.platform

import com.oruke.onyx.app.filesystem.JvmMemoryStatus
import com.oruke.onyx.app.filesystem.MemoryConfigService
import com.oruke.onyx.core.model.OnyxSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM 启动堆内存配置服务，负责定位并原子读写 jpackage 生成的 `Onyx.cfg` 中的 `-Xmx` 参数。
 *
 * @param launcherConfigPathProvider 解析当前运行实例对应 `Onyx.cfg` 路径的提供器；开发运行期返回 `null`。
 * @param runtimeMemoryProvider 提供 `(maxHeapBytes, usedHeapBytes)` 的运行期内存采样函数。
 */
internal class JvmMemoryConfigService(
    /** 定位当前安装包 `Onyx.cfg` 文件路径的函数。 */
    private val launcherConfigPathProvider: () -> Path? = ::locateDefaultLauncherConfigPath,
    /** 读取当前 JVM 最大堆与已用堆字节数的采样函数。 */
    private val runtimeMemoryProvider: () -> Pair<Long, Long> = ::sampleCurrentRuntimeMemory,
) : MemoryConfigService {
    /** 记录最近一次写回 `Onyx.cfg` 失败的诊断文案，便于设置页重新打开时可见。 */
    @Volatile
    private var lastWriteError: String? = null

    /**
     * 读取当前进程生效堆内存及 `Onyx.cfg` 中配置的 `-Xmx` 值。
     *
     * @return 包含实时内存与启动配置状态的快照。
     */
    override suspend fun readStatus(): JvmMemoryStatus = withContext(Dispatchers.IO) {
        buildStatus(lastWriteError)
    }

    /**
     * 将最大堆内存上限原子写回 `Onyx.cfg` 的 `[JavaOptions]` 区块。
     *
     * - 当存在打包安装配置文件时，替换已有 `java-options=-Xmx...` 行或在 `[JavaOptions]` 尾部追加。
     * - 当处于开发环境（无 `Onyx.cfg`）时，仅返回当前状态，不伪造成功写入。
     *
     * @param maxHeapMb 目标最大堆内存（单位 MB）。
     * @return 更新后的内存配置状态；若文件不可写或 I/O 失败返回显式失败。
     */
    override suspend fun applyMaxHeapMemoryMb(maxHeapMb: Int): Result<JvmMemoryStatus> = withContext(Dispatchers.IO) {
        val clampedMb = clampHeapMemoryMb(maxHeapMb)
        val configPath = launcherConfigPathProvider()
        if (configPath == null || !Files.isRegularFile(configPath)) {
            lastWriteError = null
            return@withContext Result.success(buildStatus(null))
        }
        runCatching {
            val originalContent = Files.readString(configPath, StandardCharsets.UTF_8)
            val updatedContent = updateXmxInCfgContent(originalContent, clampedMb)
            if (updatedContent != originalContent) {
                writeTextAtomically(configPath, updatedContent)
            }
            lastWriteError = null
            buildStatus(null)
        }.onFailure { failure ->
            lastWriteError = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName
        }
    }

    /**
     * 组合当前运行期堆采样与 `Onyx.cfg` 解析结果。
     *
     * @param writeError 最近一次写回错误摘要。
     * @return 完整的内存状态快照。
     */
    private fun buildStatus(writeError: String?): JvmMemoryStatus {
        val (maxBytes, usedBytes) = runtimeMemoryProvider()
        val configPath = launcherConfigPathProvider()?.takeIf(Files::isRegularFile)
        val configuredMb = configPath?.let { path ->
            runCatching {
                parseXmxMbFromCfgContent(Files.readString(path, StandardCharsets.UTF_8))
            }.getOrNull()
        }
        val writable = configPath != null && Files.isWritable(configPath)
        return JvmMemoryStatus(
            activeMaxHeapMb = bytesToMegabytes(maxBytes),
            usedHeapMb = bytesToMegabytes(usedBytes),
            configuredMaxHeapMb = configuredMb,
            launcherConfigWritable = writable,
            launcherConfigPath = configPath?.toAbsolutePath()?.normalize()?.toString(),
            lastWriteError = writeError,
        )
    }

    internal companion object {
        /** 字节到 MB 的换算常量。 */
        private const val BYTES_PER_MB = 1024L * 1024L

        /** 字节到 KB 的换算常量。 */
        private const val KB_PER_MB = 1024L

        /** GB 到 MB 的换算常量。 */
        private const val MB_PER_GB = 1024L

        /** jpackage 配置文件名。 */
        private const val LAUNCHER_CONFIG_FILE_NAME = "Onyx.cfg"

        /** jpackage JVM 参数区块头。 */
        private const val JAVA_OPTIONS_SECTION = "[JavaOptions]"

        /** jpackage 选项前缀。 */
        private const val JAVA_OPTION_PREFIX = "java-options="

        /** 匹配 `-Xmx` 数值与可选单位后缀（`k`/`m`/`g`）的表达式。 */
        private val XMX_VALUE_REGEX = Regex("^-Xmx(\\d+)([kKmMgG])?$")

        /**
         * 将最大堆内存值约束到合法区间内。
         *
         * @param valueMb 待规范化的内存上限（MB）。
         * @return 约束后的内存上限（MB）。
         */
        fun clampHeapMemoryMb(valueMb: Int): Int = valueMb.coerceIn(
            OnyxSettings.MIN_MAX_HEAP_MEMORY_MB,
            OnyxSettings.MAX_MAX_HEAP_MEMORY_MB,
        )

        /**
         * 从 `Onyx.cfg` 文本内容中解析 `[JavaOptions]` 下的 `-Xmx` 上限（以最后一个生效声明为准）。
         *
         * @param content `Onyx.cfg` 原始文本。
         * @return 解析得到的 MB 数值；未配置或格式无效时返回 `null`。
         */
        fun parseXmxMbFromCfgContent(content: String): Int? {
            var inJavaOptions = false
            var resolvedMb: Int? = null
            for (rawLine in content.lineSequence()) {
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inJavaOptions = trimmed.equals(JAVA_OPTIONS_SECTION, ignoreCase = true)
                } else if (inJavaOptions && trimmed.startsWith(JAVA_OPTION_PREFIX)) {
                    val option = trimmed.removePrefix(JAVA_OPTION_PREFIX).trim()
                    parseXmxOptionToMb(option)?.let { parsed -> resolvedMb = parsed }
                }
            }
            return resolvedMb
        }

        /**
         * 更新 `Onyx.cfg` 内容中的 `-Xmx` 启动参数，保留其余参数顺序及原始换行风格。
         *
         * 1. 若 `[JavaOptions]` 内已有 `-Xmx` 声明，在首个声明位置替换为新值并清理多余重复项；
         * 2. 若 `[JavaOptions]` 存在但尚无 `-Xmx`，则插入至该区块末尾；
         * 3. 若文件缺少 `[JavaOptions]` 区块，则在文件尾部追加该区块与参数。
         *
         * @param content 原始 `Onyx.cfg` 文本。
         * @param maxHeapMb 目标最大堆内存（MB）。
         * @return 更新后的配置文件完整文本。
         */
        fun updateXmxInCfgContent(content: String, maxHeapMb: Int): String {
            val clampedMb = clampHeapMemoryMb(maxHeapMb)
            val lineSeparator = if ("\r\n" in content) "\r\n" else "\n"
            val hadTrailingNewline = content.endsWith("\n")
            val rawLines = splitCfgLines(content, hadTrailingNewline)
            val newOptionLine = "${JAVA_OPTION_PREFIX}-Xmx${clampedMb}m"

            val rewriteState = rewriteJavaOptionsLines(
                rawLines = rawLines,
                newOptionLine = newOptionLine,
                javaOptionsSection = JAVA_OPTIONS_SECTION,
                isXmxLine = ::isXmxOptionLine,
            )
            appendMissingXmxSection(
                output = rewriteState.output,
                sawJavaOptionsSection = rewriteState.sawJavaOptionsSection,
                wroteXmx = rewriteState.wroteXmx,
                javaOptionsSection = JAVA_OPTIONS_SECTION,
                newOptionLine = newOptionLine,
            )

            return rewriteState.output.joinToString(lineSeparator) + if (hadTrailingNewline) lineSeparator else ""
        }

        /**
         * 解析单个 `-Xmx` 选项字符串并换算为 MB。
         *
         * @param option 单个 JVM 选项（如 `-Xmx1024m`、`-Xmx2g`）。
         * @return 换算后的 MB 整数值；不匹配或溢出时返回 `null`。
         */
        internal fun parseXmxOptionToMb(option: String): Int? {
            val match = XMX_VALUE_REGEX.matchEntire(option.trim()) ?: return null
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            if (amount <= 0L) return null
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            val megabytes = when (unit) {
                "g" -> amount * MB_PER_GB
                "m" -> amount
                "k" -> (amount / KB_PER_MB).coerceAtLeast(1L)
                "" -> (amount / BYTES_PER_MB).coerceAtLeast(1L)
                else -> return null
            }
            return megabytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * 判断配置行是否为 `[JavaOptions]` 下的 `-Xmx` 声明。
         *
         * @param trimmedLine 已去除首尾空白的单行配置。
         * @return 匹配 `java-options=-Xmx...` 时返回 `true`。
         */
        private fun isXmxOptionLine(trimmedLine: String): Boolean {
            if (!trimmedLine.startsWith(JAVA_OPTION_PREFIX)) return false
            val option = trimmedLine.removePrefix(JAVA_OPTION_PREFIX).trim()
            return XMX_VALUE_REGEX.matches(option)
        }

        /**
         * 根据 jpackage 注入的系统属性定位当前安装实例的 `Onyx.cfg`。
         *
         * @return 存在的 `Onyx.cfg` 路径；开发模式未通过 jpackage 启动时返回 `null`。
         */
        private fun locateDefaultLauncherConfigPath(): Path? {
            val candidates = buildList {
                System.getProperty("compose.application.resources.dir")
                    ?.takeIf(String::isNotBlank)
                    ?.let { resourcesDir ->
                        runCatching { Path.of(resourcesDir).parent?.resolve(LAUNCHER_CONFIG_FILE_NAME) }
                            .getOrNull()
                            ?.let(::add)
                    }
                System.getProperty("jpackage.app-path")
                    ?.takeIf(String::isNotBlank)
                    ?.let { appPath ->
                        runCatching { Path.of(appPath).parent }.getOrNull()?.let { executableDir ->
                            // Windows: <root>/app/Onyx.cfg; Linux: <root>/lib/app/Onyx.cfg;
                            // macOS: Contents/app/Onyx.cfg
                            add(executableDir.resolve("app").resolve(LAUNCHER_CONFIG_FILE_NAME))
                            executableDir.parent?.let { parentDir ->
                                add(parentDir.resolve("lib").resolve("app").resolve(LAUNCHER_CONFIG_FILE_NAME))
                                add(parentDir.resolve("app").resolve(LAUNCHER_CONFIG_FILE_NAME))
                            }
                        }
                    }
            }
            return candidates.firstOrNull { candidate -> Files.isRegularFile(candidate) }
        }

        /**
         * 采样当前 JVM 进程的最大堆与已用堆字节数。
         *
         * @return `(maxHeapBytes, usedHeapBytes)` 二元组。
         */
        private fun sampleCurrentRuntimeMemory(): Pair<Long, Long> {
            val runtime = Runtime.getRuntime()
            val maxBytes = runtime.maxMemory().coerceAtLeast(0L)
            val usedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
            return maxBytes to usedBytes
        }

        /**
         * 将字节数按四舍五入转换为 MB。
         *
         * @param bytes 字节数。
         * @return 对应的 MB 整数值。
         */
        private fun bytesToMegabytes(bytes: Long): Int {
            if (bytes <= 0L) return 0
            return ((bytes + BYTES_PER_MB / 2L) / BYTES_PER_MB)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

        /**
         * 在目标同级目录写入临时文件后原子替换，避免进程中断损坏启动配置。
         *
         * @param target 目标配置文件路径。
         * @param content 新配置文本。
         */
        private fun writeTextAtomically(target: Path, content: String) {
            val parentDir = target.parent ?: target.toAbsolutePath().parent
            val tempFile = Files.createTempFile(parentDir, "onyx-cfg-", ".tmp")
            try {
                Files.writeString(tempFile, content, StandardCharsets.UTF_8)
                try {
                    Files.move(
                        tempFile,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: UnsupportedOperationException) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                runCatching { Files.deleteIfExists(tempFile) }
            }
        }
    }
}

/** 改写 `Onyx.cfg` 的 `[JavaOptions]` 区块结果快照。 */
private class CfgRewriteResult(
    /** 改写后的配置行列表。 */
    val output: MutableList<String>,
    /** 是否在原文中见过 `[JavaOptions]` 区块。 */
    val sawJavaOptionsSection: Boolean,
    /** 是否已经写入目标 `-Xmx` 配置行。 */
    val wroteXmx: Boolean,
)

/**
 * 按原始换行切分 `Onyx.cfg` 行序列并剥离尾随空行。
 *
 * @param content 配置文件原文。
 * @param hadTrailingNewline 原文末尾是否带有换行符。
 * @return 行列表。
 */
private fun splitCfgLines(content: String, hadTrailingNewline: Boolean): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split(Regex("\\r?\\n"))
    return if (hadTrailingNewline && lines.lastOrNull() == "") lines.dropLast(1) else lines
}

/**
 * 遍历配置行替换 `[JavaOptions]` 内首个 `-Xmx` 声明并移除重复项。
 *
 * @param rawLines 原始行列表。
 * @param newOptionLine 目标 `-Xmx` 配置行。
 * @param javaOptionsSection `[JavaOptions]` 区块标题。
 * @param isXmxLine 判断单行是否为 `-Xmx` 配置行的谓词。
 * @return 改写结果快照。
 */
private fun rewriteJavaOptionsLines(
    rawLines: List<String>,
    newOptionLine: String,
    javaOptionsSection: String,
    isXmxLine: (String) -> Boolean,
): CfgRewriteResult {
    val output = mutableListOf<String>()
    var inJavaOptions = false
    var sawJavaOptionsSection = false
    var wroteXmx = false

    for (line in rawLines) {
        val trimmed = line.trim()
        val isSectionHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
        when {
            isSectionHeader -> {
                if (inJavaOptions && !wroteXmx) {
                    output.add(newOptionLine)
                    wroteXmx = true
                }
                inJavaOptions = trimmed.equals(javaOptionsSection, ignoreCase = true)
                if (inJavaOptions) sawJavaOptionsSection = true
                output.add(line)
            }
            inJavaOptions && isXmxLine(trimmed) -> {
                if (!wroteXmx) {
                    output.add(newOptionLine)
                    wroteXmx = true
                }
            }
            else -> output.add(line)
        }
    }
    return CfgRewriteResult(output, sawJavaOptionsSection, wroteXmx)
}

/**
 * 若原文缺少 `-Xmx` 或 `[JavaOptions]` 区块，则在尾部补齐。
 *
 * @param output 正在组装的输出行列表。
 * @param sawJavaOptionsSection 原文是否包含 `[JavaOptions]` 区块。
 * @param wroteXmx 是否已写入 `-Xmx` 行。
 * @param javaOptionsSection `[JavaOptions]` 区块标题。
 * @param newOptionLine 目标 `-Xmx` 配置行。
 */
private fun appendMissingXmxSection(
    output: MutableList<String>,
    sawJavaOptionsSection: Boolean,
    wroteXmx: Boolean,
    javaOptionsSection: String,
    newOptionLine: String,
) {
    if (wroteXmx) return
    if (!sawJavaOptionsSection) {
        if (output.isNotEmpty() && output.last().isNotBlank()) {
            output.add("")
        }
        output.add(javaOptionsSection)
    }
    output.add(newOptionLine)
}
