package com.oruke.onyx.app

import com.oruke.onyx.app.storage.OnyxDataDirectories
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isRegularFile

/**
 * Onyx 统一日志入口，负责同时输出控制台日志与按日持久化日志。
 *
 * 错误与警告始终输出，信息与调试日志仅在 [verboseEnabled] 开启时输出。
 * 日志初始化失败不会阻止应用启动，错误仍会写入标准错误流。
 */
object OnyxLogger {
    /** 单条日志时间格式。 */
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** 按日切分日志文件的日期格式。 */
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** 日志写入与初始化共用锁，避免多线程输出互相穿插。 */
    private val writeLock = Any()

    /** 日志保留天数，超期文件会在下次启动时清理。 */
    private const val LOG_RETENTION_DAYS = 14L

    /** 当前进程使用的时钟，初始化后保持一致以便测试和日志命名。 */
    private var clock: Clock = Clock.systemDefaultZone()

    /** 当前日志文件写入器；初始化失败时保持为空。 */
    private var fileWriter: PrintWriter? = null

    /** 当前进程实际使用的日志文件路径。 */
    private var logFilePath: Path? = null

    /** 日志系统是否已经执行过初始化。 */
    private var initialized: Boolean = false

    /** 运行时详细日志开关，不持久化且每次启动默认关闭。 */
    @Volatile
    var verboseEnabled: Boolean = false

    /**
     * 初始化按日持久化日志，并返回当前日志文件路径。
     *
     * 该操作必须在创建 UI 前同步完成，确保窗口初始化异常仍能写入文件；仅执行目录创建、
     * 过期文件清理和追加写入器打开，不承担其他启动业务。
     *
     * @param logDirectory 日志目录，默认位于宿主平台的 Onyx 状态目录。
     * @param startupClock 用于文件命名与日志时间戳的时钟。
     * @return 当前日志文件路径；初始化失败时返回 `null`。
     */
    fun initialize(
        logDirectory: Path = OnyxDataDirectories.stateDirectory().resolve("logs"),
        startupClock: Clock = Clock.systemDefaultZone(),
    ): Path? = synchronized(writeLock) {
        if (initialized) return@synchronized logFilePath

        clock = startupClock
        initialized = true
        runCatching {
            Files.createDirectories(logDirectory)
            val today = LocalDate.now(clock)
            runCatching { removeExpiredLogFiles(logDirectory, today) }
                .onFailure { failure -> System.err.println("Onyx 过期日志清理失败：${failure.message}") }
            val path = logDirectory.resolve(logFileName(today))
            fileWriter = PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8, CREATE, APPEND),
                true,
            )
            logFilePath = path
            writeLog(System.out, "INFO", "Startup", "日志文件：${path.toAbsolutePath()}")
            path
        }.getOrElse { failure ->
            System.err.println("Onyx 日志初始化失败：${failure.message}")
            null
        }
    }

    /**
     * 输出错误日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     * @param throwable 可选异常。
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        writeLog(System.err, "ERROR", tag, message, throwable)
    }

    /**
     * 输出警告日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     * @param throwable 可选异常。
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        writeLog(System.err, "WARN", tag, message, throwable)
    }

    /**
     * 在详细日志开启时输出信息日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     */
    fun info(tag: String, message: String) {
        if (verboseEnabled) writeLog(System.out, "INFO", tag, message)
    }

    /**
     * 在详细日志开启时输出调试日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     */
    fun debug(tag: String, message: String) {
        if (verboseEnabled) writeLog(System.out, "DEBUG", tag, message)
    }

    /**
     * 将一条日志原子写入目标控制台流与当前日志文件。
     *
     * @param consoleStream 控制台输出流。
     * @param level 日志级别。
     * @param tag 日志来源。
     * @param message 日志内容。
     * @param throwable 可选异常。
     */
    private fun writeLog(
        consoleStream: PrintStream,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) = synchronized(writeLock) {
        val timestamp = LocalDateTime.now(clock).format(timeFormatter)
        val line = "[$timestamp] [$level] [$tag] $message"
        consoleStream.println(line)
        fileWriter?.println(line)
        throwable?.printStackTrace(consoleStream)
        fileWriter?.let { writer -> throwable?.printStackTrace(writer) }
    }

    /**
     * 删除超出保留期的 Onyx 按日日志，不触碰目录中的其他文件。
     *
     * @param logDirectory 日志目录。
     * @param today 当前日期。
     */
    private fun removeExpiredLogFiles(logDirectory: Path, today: LocalDate) {
        val oldestRetainedDate = today.minusDays(LOG_RETENTION_DAYS - 1)
        Files.list(logDirectory).use { paths ->
            paths.filter { path -> path.isRegularFile() }
                .filter { path -> isExpiredLogFile(path, oldestRetainedDate) }
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    /**
     * 生成指定日期对应的 Onyx 日志文件名。
     *
     * @param date 日志日期。
     * @return 符合按日切分约定的文件名。
     */
    internal fun logFileName(date: LocalDate): String = "onyx-${date.format(dateFormatter)}.log"

    /**
     * 判断路径是否为早于保留边界的 Onyx 日志文件。
     *
     * @param path 待判断文件路径。
     * @param oldestRetainedDate 最早保留日期。
     * @return 仅当文件名符合约定且日期早于边界时返回 `true`。
     */
    internal fun isExpiredLogFile(path: Path, oldestRetainedDate: LocalDate): Boolean {
        val match = logFilePattern.matchEntire(path.fileName.toString()) ?: return false
        val logDate = runCatching { LocalDate.parse(match.groupValues[1], dateFormatter) }.getOrNull()
        return logDate?.isBefore(oldestRetainedDate) == true
    }

    /** Onyx 按日日志文件名约束。 */
    private val logFilePattern = Regex("onyx-(\\d{4}-\\d{2}-\\d{2})\\.log")
}
