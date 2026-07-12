package com.oruke.onyx.app

import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Onyx 统一日志工具。
 *
 * - 异常日志（error / warn）**始终**输出。
 * - 详细日志（info / debug）仅在 [verboseEnabled] 开启时输出。
 * - [verboseEnabled] 默认关闭，每次启动自动重置为 false，
 *   仅在运行时通过设置手动开启。
 */
object OnyxLogger {

    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** 运行时详细日志开关 — 不持久化，每次启动默认 false。 */
    @Volatile
    var verboseEnabled: Boolean = false

    // ── 异常日志 — 始终输出 ────────────────────────────────────────────────

    /**
     * 输出错误日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     * @param throwable 可选异常。
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val ts = LocalDateTime.now().format(formatter)
        val stackTrace = throwable?.let { "\n${stackTraceToString(it)}" } ?: ""
        System.err.println("[$ts] [ERROR] [$tag] $message$stackTrace")
    }

    /**
     * 输出警告日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     * @param throwable 可选异常。
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        val ts = LocalDateTime.now().format(formatter)
        val stackTrace = throwable?.let { "\n${stackTraceToString(it)}" } ?: ""
        System.err.println("[$ts] [WARN]  [$tag] $message$stackTrace")
    }

    // ── 详细日志 — 仅 verboseEnabled = true 时输出 ──────────────────────

    /**
     * 在详细日志开启时输出信息日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     */
    fun info(tag: String, message: String) {
        if (!verboseEnabled) return
        val ts = LocalDateTime.now().format(formatter)
        println("[$ts] [INFO]  [$tag] $message")
    }

    /**
     * 在详细日志开启时输出调试日志。
     *
     * @param tag 日志来源。
     * @param message 日志内容。
     */
    fun debug(tag: String, message: String) {
        if (!verboseEnabled) return
        val ts = LocalDateTime.now().format(formatter)
        println("[$ts] [DEBUG] [$tag] $message")
    }

    /**
     * 将异常堆栈转换为日志文本。
     *
     * @param throwable 目标异常。
     * @return 完整堆栈文本。
     */
    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
