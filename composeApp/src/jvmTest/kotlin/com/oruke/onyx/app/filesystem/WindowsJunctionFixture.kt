package com.oruke.onyx.app.filesystem

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 在独立临时目录中创建真实 Windows Junction，所有入口、目标和清理范围均限制在本次测试内。 */
internal class WindowsJunctionFixture : Closeable {
    /** 本次测试独占的绝对根路径。 */
    val root: Path = Files.createTempDirectory("onyx-junction-test-").toAbsolutePath().normalize()

    /**
     * 创建本次测试内的普通目录。
     *
     * @param name 相对于测试根的目录名称或路径。
     * @return 创建完成的绝对目录路径。
     */
    fun directory(name: String): Path = Files.createDirectories(testPath(name))

    /**
     * 创建指向本次测试内目标的真实 Junction，不要求目标已经存在。
     *
     * @param name 联接入口相对于测试根的路径。
     * @param target 本次测试内的目标路径。
     * @return 实际创建的目录联接入口。
     */
    fun junction(name: String, target: Path): Path {
        val link = testPath(name)
        require(target.toAbsolutePath().normalize().startsWith(root) && target != root)
        Files.createDirectories(link.parent)
        val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out creating a test junction")
        }
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        return link
    }

    /**
     * 解析测试资源路径并拒绝越过测试根的路径。
     *
     * @param name 测试根内的相对路径。
     * @return 已确认位于测试根内的绝对路径。
     */
    fun testPath(name: String): Path {
        val resolved = root.resolve(name).normalize()
        require(resolved.startsWith(root) && resolved != root)
        return resolved
    }

    /** 清理本次测试创建的资源，联接直接删除入口，不使用被测删除实现或跟随其目标。 */
    override fun close() {
        removeTestEntry(root)
    }

    /**
     * 仅清理测试根内的实际条目，所有重解析入口均视作叶子。
     *
     * @param path 已解析的测试路径。
     */
    private fun removeTestEntry(path: Path) {
        require(path.toAbsolutePath().normalize().startsWith(root))
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.isDirectory && !attributes.isOther && !attributes.isSymbolicLink) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::removeTestEntry) }
        }
        Files.deleteIfExists(path)
    }
}
