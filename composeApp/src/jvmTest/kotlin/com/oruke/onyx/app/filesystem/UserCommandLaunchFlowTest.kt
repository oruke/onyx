package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.shared.usecase.UserCommandInvocation
import com.oruke.onyx.shared.usecase.UserCommandUseCase
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
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
private fun probeClasspath(): String = listOf(CommandArgumentProbe::class.java, Unit::class.java)
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
        if (args.firstOrNull() == "--fail") System.exit(7)
        Files.write(Path.of(args.first()), listOf(System.getProperty("user.dir")) + args.drop(1))
    }
}
