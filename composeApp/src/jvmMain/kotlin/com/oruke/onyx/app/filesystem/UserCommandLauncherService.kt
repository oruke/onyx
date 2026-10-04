package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.shared.usecase.UserCommandInvocation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** 启动用户主动配置的外部程序，参数数组与工作目录保持独立。 */
internal interface UserCommandLauncherService {
    /**
     * 启动程序并读取退出状态，不经 Shell 拼接或解释参数。
     * @param invocation 组件完成变量替换后的执行计划。
     * @return 程序退出码；启动失败返回本地化错误。
     */
    suspend fun execute(invocation: UserCommandInvocation): Result<Int>
}

/** JVM 程序执行器；关闭输入并丢弃无人消费的输出，避免子进程被管道写满卡住。 */
internal class JvmUserCommandLauncherService : UserCommandLauncherService {
    /**
     * 在 IO 线程校验本地工作目录、启动参数数组并等待退出。
     * @param invocation 不含 Shell 命令拼接的程序调用。
     * @return 退出状态或明确失败，取消只中断等待，不强制结束用户外部程序。
     */
    @Suppress("TooGenericExceptionCaught") // 平台进程边界将路径、权限和启动错误转换为可见业务结果。
    override suspend fun execute(invocation: UserCommandInvocation): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (invocation.executable.isBlank()) {
                throw LocalizedException(I18nMessage(MessageKey.MSG_USER_COMMAND_INVALID))
            }
            val directory = invocation.workingDirectory?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            if (directory != null && !Files.isDirectory(directory)) {
                throw LocalizedException(I18nMessage(MessageKey.MSG_USER_COMMAND_LOCAL_DIRECTORY_REQUIRED))
            }
            val builder = ProcessBuilder(listOf(invocation.executable) + invocation.arguments)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            if (directory != null) builder.directory(directory.toFile())
            val process = builder.start()
            process.outputStream.close()
            Result.success(runInterruptible { process.waitFor() })
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: LocalizedException) {
            Result.failure(failure)
        } catch (failure: Exception) {
            Result.failure(LocalizedException(I18nMessage(MessageKey.MSG_USER_COMMAND_LAUNCH_FAILED), failure))
        }
    }
}
