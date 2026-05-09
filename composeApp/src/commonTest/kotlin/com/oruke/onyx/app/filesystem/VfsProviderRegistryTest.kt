package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VfsProviderRegistryTest {

    @Test
    fun `registry routes list request to matching provider`() = runBlocking {
        val localFile = file("local", "/tmp/local.txt", 10)
        val archiveFile = file("archive", "archive:///tmp/a.zip!/a.txt", 20)
        val local = FakeProvider(
            protocol = VfsProtocol.LOCAL,
            supportedPrefix = "/",
            listResult = listOf(localFile),
        )
        val archive = FakeProvider(
            protocol = VfsProtocol.ARCHIVE,
            supportedPrefix = "archive://",
            listResult = listOf(archiveFile),
        )
        val registry = VfsProviderRegistry(listOf(archive, local))

        assertEquals(listOf(archiveFile), registry.list("archive:///tmp/a.zip!/").getOrThrow())
        assertEquals(listOf(localFile), registry.list("/tmp").getOrThrow())
    }

    @Test
    fun `registry groups total size by provider`() = runBlocking {
        val local = FakeProvider(VfsProtocol.LOCAL, "/", totalSize = 30)
        val archive = FakeProvider(VfsProtocol.ARCHIVE, "archive://", totalSize = 20)
        val registry = VfsProviderRegistry(listOf(archive, local))

        val total = registry.totalSizeBytes(
            listOf(
                file("local", "/tmp/local.txt", 1),
                file("archive", "archive:///tmp/a.zip!/a.txt", 2),
            )
        ).getOrThrow()

        assertEquals(50, total)
    }

    @Test
    fun `registry reports unsupported location`() {
        val registry = VfsProviderRegistry(listOf(FakeProvider(VfsProtocol.LOCAL, "/")))

        assertFailsWith<VfsProviderNotFoundException> {
            registry.providerFor("smb://server/share").getOrThrow()
        }
    }

    private class FakeProvider(
        override val protocol: VfsProtocol,
        private val supportedPrefix: String,
        private val listResult: List<VFile> = emptyList(),
        private val totalSize: Long = listResult.sumOf { entry -> entry.sizeBytes ?: 0L },
    ) : VfsProvider {
        override val capabilities: Set<VfsProviderCapability> = emptySet()

        override fun supports(location: String): Boolean = location.startsWith(supportedPrefix)

        override suspend fun list(location: String): Result<List<VFile>> = Result.success(listResult)

        override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> = Result.success(totalSize)
    }

    private fun file(
        id: String,
        location: String,
        sizeBytes: Long,
    ): VFile {
        return VFile(
            id = id,
            name = location.substringAfterLast('/'),
            location = location,
            parentLocation = null,
            kind = VFileKind.FILE,
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = emptySet(),
        )
    }
}
