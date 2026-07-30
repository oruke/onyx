package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 本地文件元数据与错误映射测试。
 */
class LocalFileExtensionsTest {
    /**
     * 校验普通可读目录声明子项枚举能力。
     *
     * @return 无返回值。
     */
    @Test
    fun exposesListChildrenForReadableDirectory() {
        val parent = createTempDirectory("onyx-local-parent")
        try {
            val directory = Files.createDirectory(parent.resolve("readable"))

            val entry = directory.toLocalVFile(parent)

            assertTrue(VFileCapability.LIST_CHILDREN in entry.capabilities)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    /**
     * 校验普通可读目录保持自身作为枚举位置。
     *
     * @return 无返回值。
     */
    @Test
    fun keepsReadableDirectoryAsListingLocation() {
        val directory = createTempDirectory("onyx-local-listing")
        try {
            val resolved = LocalDirectoryAccess.resolveForListing(directory).getOrThrow()

            assertEquals(directory.toAbsolutePath().normalize(), resolved)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * 校验拒绝访问异常保留为本地 Provider 的结构化权限错误。
     *
     * @return 无返回值。
     */
    @Test
    fun mapsAccessDeniedToProviderPermissionError() {
        val result = Result.failure<Unit>(AccessDeniedException("protected"))
            .mapLocalError()

        val failure = assertIs<VfsProviderException>(result.exceptionOrNull())
        val error = assertIs<VfsProviderError.PermissionDenied>(failure.error)
        assertEquals(VfsProtocol.LOCAL, error.protocol)
        assertEquals("protected", error.location)
    }
}
