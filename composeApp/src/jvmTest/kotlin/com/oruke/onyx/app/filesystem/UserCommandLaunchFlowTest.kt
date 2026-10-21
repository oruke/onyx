package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.shared.usecase.UserCommandInvocation
import com.oruke.onyx.shared.usecase.UserCommandUseCase
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.WString
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 从真实文件选择、参数规划到子进程写入文件，验证独立参数及错误结果。 */
class UserCommandLaunchFlowTest {
    /** 包含空格和 Unicode 的多个文件必须是独立参数，特殊字符不得被 Shell 解释。 */
    @Test
    fun selectionArgumentsReachRealProcessWithoutShellParsing() = runBlocking {
        val root = Files.createTempDirectory("onyx-command-flow-")
        val provider = JvmLocalFileProvider()
        try {
            Files.writeString(root.resolve("中文 空格.txt"), "first")
            Files.writeString(root.resolve("second.txt"), "second")
            val selection = provider.list(root.toString()).getOrThrow().sortedBy { it.name }
            val output = root.resolve("arguments.txt")
            val special = "literal & ; dollar $ and spaces"
            val definition = UserCommandDefinition(
                id = "probe", name = "Probe", executable = testJavaExecutable(),
                argumentTemplate = listOf(
                    "-cp",
                    probeClasspath(),
                    CommandArgumentProbe::class.java.name,
                    output.toString(),
                    "{selection}",
                    special
                ),
            )
            val invocation = UserCommandUseCase().renderInvocation(definition, root.toString(), selection)
            assertEquals(0, JvmUserCommandLauncherService().execute(invocation).getOrThrow())
            assertEquals(listOf(root.toString()) + selection.map { it.location } + special, Files.readAllLines(output))
        } finally {
            provider.delete(provider.list(root.toString()).getOrThrow()).getOrThrow()
            Files.delete(root)
        }
    }

    /** 启动不存在的程序返回明确失败；真实子进程的非零退出码也必须可观察。 */
    @Test
    fun processStartupAndExitFailureAreObservable() = runBlocking {
        val launcher = JvmUserCommandLauncherService()
        assertTrue(
            launcher.execute(
                UserCommandInvocation(
                    "onyx-nonexistent-fixture-program",
                    emptyList(),
                    null
                )
            ).isFailure
        )
        val invocation = UserCommandInvocation(
            testJavaExecutable(),
            listOf("-cp", probeClasspath(), CommandArgumentProbe::class.java.name, "--fail"),
            null
        )
        assertEquals(7, launcher.execute(invocation).getOrThrow())
    }
}

/**
 * 找到当前测试 JVM 使用的实际 Java，不写死机器路径。
 * @return 本机 Java 可执行文件路径。
 */
private fun testJavaExecutable(): String = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
).toString()

/**
 * 为子进程提供测试探针和 Kotlin 运行库，不依赖 Gradle worker 的专用 classpath。
 * @return 当前构建产物组成的 classpath。
 */
private fun probeClasspath(): String = listOf(
    CommandArgumentProbe::class.java, Unit::class.java, Native::class.java, Shell32Util::class.java,
)
    .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
    .joinToString(File.pathSeparator)

/** 独立 JVM 测试探针，只写入测试显式传入的临时输出文件。 */
object CommandArgumentProbe {
    /**
     * 记录工作目录和真实收到的每个参数，或返回用于失败验证的非零退出码。
     * @param args 输出文件后跟待验证参数；首参数为 --fail 时退出码为 7。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val received = receivedArguments(args)
        if (received.firstOrNull() == "--fail") System.exit(7)
        Files.write(Path.of(received.first()), listOf(System.getProperty("user.dir")) + received.drop(1))
    }

    /**
     * 读取子进程实际接收的参数；Windows 使用原始宽字符命令行，避免 Java 启动器按 ANSI 码页丢字。
     * @param args 非 Windows 平台由 JVM 无损传入的参数。
     * @return 操作系统实际传入探针的完整参数，保留 Unicode 和各参数边界。
     */
    private fun receivedArguments(args: Array<String>): Array<String> {
        if (!Platform.isWindows()) return args
        // Java 21 的 Windows 启动器仍受系统 ANSI 码页约束，不能把其解码损失误判为 ProcessBuilder 拼参。
        val api = Native.load("kernel32", WindowsProbeCommandLine::class.java)
        val command = Shell32Util.CommandLineToArgv(api.GetCommandLineW().toString())
        val mainClassIndex = command.indexOf(CommandArgumentProbe::class.java.name)
        check(mainClassIndex >= 0) { "测试探针主类未出现在真实命令行中" }
        return command.drop(mainClassIndex + 1).toTypedArray()
    }
}

/** 仅供独立测试进程读取操作系统原始 UTF-16 命令行，生产启动器保持统一 ProcessBuilder 实现。 */
internal interface WindowsProbeCommandLine : StdCallLibrary {
    /** @return 当前探针进程由 Windows 实际接收的原始宽字符命令行。 */
    fun GetCommandLineW(): WString
}
