package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 原生命令允许的最长执行时间；设备忙或授权失败必须结束当前请求。 */
private const val DEVICE_COMMAND_TIMEOUT_SECONDS = 20L

/**
 * 从宿主 PATH 查找可执行工具，不执行 Shell，也不推断工具已安装。
 *
 * @param command 不含路径的工具名称。
 * @return 工具的实际可执行位置；未安装时为空。
 */
internal fun storageCommandPath(command: String): Path? =
    System.getenv("PATH")
        .orEmpty()
        .split(java.io.File.pathSeparator)
        .asSequence()
        .filter(String::isNotBlank)
        .map { Path.of(it).resolve(command) }
        .firstOrNull { Files.isExecutable(it) && Files.isRegularFile(it) }

/**
 * 使用独立参数调用系统设备工具，同时读取输出以避免管道死锁，超时后回收本次进程。
 *
 * @param arguments 程序及其原始参数，不拼接 Shell 命令。
 * @return 成功命令的 UTF-8 输出，主要用于系统 plist 协议。
 */
internal fun runStorageCommand(arguments: List<String>): String {
    val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
    process.outputStream.close()
    val output = CompletableFuture.supplyAsync {
        process.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
    try {
        if (!process.waitFor(DEVICE_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_OPERATION_TIMEOUT))
        }
        val text = output.get(DEVICE_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            throw LocalizedException(
                I18nMessage(MessageKey.MSG_DEVICE_OPERATION_FAILED, text.trim())
            )
        }
        return text
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
        output.cancel(true)
    }
}
