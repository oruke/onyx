package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SMB VFS provider 的 fake client 行为测试。
 */
class SmbVfsProviderTest {
    /**
     * 校验 SMB provider 会把 list/read/write/copy/move/delete 命令路由到 client。
     *
     * @return 无返回值。
     */
    @Test
    fun routesFileOperationsToClient() = runBlocking {
        val client = FakeSmbClient()
        val provider = SmbVfsProvider(
            authRepository = StaticSmbAuthRepository,
            client = client,
        )

        provider.list("smb://host/share/root").getOrThrow()
        provider.readFile(file("note.txt", "smb://host/share/root/note.txt")).getOrThrow()
        provider.writeFile(
            parentLocation = "smb://host/share/root",
            name = "created.txt",
            chunks = flowOf("created".encodeToByteArray()),
        ).getOrThrow()
        provider.copy(
            entries = listOf(file("note.txt", "smb://host/share/root/note.txt")),
            targetDirectoryLocation = "smb://host/share/target",
        ).getOrThrow()
        provider.move(
            entries = listOf(file("move.txt", "smb://host/share/root/move.txt")),
            targetDirectoryLocation = "smb://host/share/target",
        ).getOrThrow()
        provider.delete(listOf(file("delete.txt", "smb://host/share/root/delete.txt"))).getOrThrow()

        assertEquals("smb://host/share/root/", client.listedLocation)
        assertEquals("smb://host/share/root/note.txt", client.readLocation)
        assertEquals("created", client.writtenText)
        assertEquals("smb://host/share/target/", client.copyTarget)
        assertEquals("smb://host/share/target/", client.moveTarget)
        assertEquals(listOf("smb://host/share/root/delete.txt"), client.deletedLocations)
    }

    /**
     * 校验 SMB provider 会透传认证失败与网络失败。
     *
     * @return 无返回值。
     */
    @Test
    fun returnsAuthenticationAndNetworkFailures() = runBlocking {
        val authFailureProvider = SmbVfsProvider(
            authRepository = SmbAuthRepository.None,
            client = FakeSmbClient(),
        )
        val networkClient = FakeSmbClient().apply { failWithNetworkError = true }
        val networkFailureProvider = SmbVfsProvider(
            authRepository = StaticSmbAuthRepository,
            client = networkClient,
        )

        val authFailure = authFailureProvider.list("smb://host/share/root").exceptionOrNull()
        val networkFailure = networkFailureProvider.list("smb://host/share/root").exceptionOrNull()

        assertTrue(authFailure is VfsProviderException)
        assertTrue(authFailure.error is VfsProviderError.AuthenticationRejected)
        assertTrue(networkFailure is VfsProviderException)
        assertTrue(networkFailure.error is VfsProviderError.NetworkFailure)
    }

    private object StaticSmbAuthRepository : SmbAuthRepository {
        /**
         * 返回固定 SMB 用户名密码。
         *
         * @param location 请求位置。
         * @return 认证上下文。
         */
        override fun authContext(location: String): VfsAuthContext {
            return VfsAuthContext.UsernamePassword(username = "user", password = "pass", domain = "DOMAIN")
        }
    }
}

/**
 * SMB 测试 client。
 */
private class FakeSmbClient : SmbClient {
    /** 最近一次 list 位置。 */
    var listedLocation: String? = null

    /** 最近一次读取位置。 */
    var readLocation: String? = null

    /** 最近一次写入文本。 */
    var writtenText: String? = null

    /** 最近一次复制目标目录。 */
    var copyTarget: String? = null

    /** 最近一次移动目标目录。 */
    var moveTarget: String? = null

    /** 已删除的位置。 */
    val deletedLocations = mutableListOf<String>()

    /** 是否模拟网络失败。 */
    var failWithNetworkError = false

    /**
     * 测试连接。
     *
     * @param location 连接位置。
     * @param authContext 认证上下文。
     */
    override suspend fun testConnection(location: String, authContext: VfsAuthContext) = Unit

    /**
     * 列目录并记录请求位置。
     *
     * @param location 目录位置。
     * @param authContext 认证上下文。
     * @return 子条目。
     */
    override suspend fun list(location: String, authContext: VfsAuthContext): List<VFile> {
        failIfNeeded(location, authContext)
        listedLocation = location
        return listOf(file("note.txt", "${location.trimEnd('/')}/note.txt"))
    }

    /**
     * 复制条目。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录。
     * @param conflictStrategy 冲突策略。
     * @param authContext 认证上下文。
     */
    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ) {
        failIfNeeded(targetDirectoryLocation, authContext)
        copyTarget = targetDirectoryLocation
    }

    /**
     * 移动条目。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录。
     * @param conflictStrategy 冲突策略。
     * @param authContext 认证上下文。
     */
    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ) {
        failIfNeeded(targetDirectoryLocation, authContext)
        moveTarget = targetDirectoryLocation
    }

    /**
     * 删除条目。
     *
     * @param entries 待删除条目。
     * @param authContext 认证上下文。
     */
    override suspend fun delete(entries: List<VFile>, authContext: VfsAuthContext) {
        failIfNeeded(entries.firstOrNull()?.location.orEmpty(), authContext)
        deletedLocations += entries.map { entry -> entry.location }
    }

    /**
     * 重命名条目。
     *
     * @param entry 源条目。
     * @param targetName 目标名称。
     * @param authContext 认证上下文。
     * @return 重命名后的条目。
     */
    override suspend fun rename(entry: VFile, targetName: String, authContext: VfsAuthContext): VFile {
        failIfNeeded(entry.location, authContext)
        return file(targetName, "${entry.parentLocation}/$targetName")
    }

    /**
     * 创建文件。
     *
     * @param parentLocation 父目录。
     * @param name 文件名。
     * @param authContext 认证上下文。
     * @return 文件条目。
     */
    override suspend fun createFile(parentLocation: String, name: String, authContext: VfsAuthContext): VFile {
        failIfNeeded(parentLocation, authContext)
        return file(name, "${parentLocation.trimEnd('/')}/$name")
    }

    /**
     * 创建目录。
     *
     * @param parentLocation 父目录。
     * @param name 目录名。
     * @param authContext 认证上下文。
     * @return 目录条目。
     */
    override suspend fun createDirectory(parentLocation: String, name: String, authContext: VfsAuthContext): VFile {
        failIfNeeded(parentLocation, authContext)
        return directory(name, "${parentLocation.trimEnd('/')}/$name")
    }

    /**
     * 读取文件。
     *
     * @param entry 文件条目。
     * @param authContext 认证上下文。
     * @return 内容源。
     */
    override suspend fun readFile(entry: VFile, authContext: VfsAuthContext): VfsContentSource {
        failIfNeeded(entry.location, authContext)
        readLocation = entry.location
        return VfsContentSource(
            name = entry.name,
            sizeBytes = 4L,
            chunks = flowOf("read".encodeToByteArray()),
        )
    }

    /**
     * 写入文件。
     *
     * @param parentLocation 父目录。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 冲突策略。
     * @param authContext 认证上下文。
     * @return 写入后的文件。
     */
    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): VFile? {
        failIfNeeded(parentLocation, authContext)
        val bytes = mutableListOf<Byte>()
        chunks.collect { chunk -> bytes += chunk.toList() }
        writtenText = bytes.toByteArray().decodeToString()
        return file(name, "${parentLocation.trimEnd('/')}/$name")
    }

    /**
     * 按测试开关抛出认证或网络错误。
     *
     * @param location 请求位置。
     * @param authContext 认证上下文。
     */
    private fun failIfNeeded(location: String, authContext: VfsAuthContext) {
        if (authContext == VfsAuthContext.None) {
            throw VfsProviderException(
                VfsProviderError.AuthenticationRejected(VfsProtocol.SMB, location)
            )
        }
        if (failWithNetworkError) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(VfsProtocol.SMB, location, "network")
            )
        }
    }
}

/**
 * 创建测试文件。
 *
 * @param name 文件名。
 * @param location 文件位置。
 * @return 文件条目。
 */
private fun file(name: String, location: String): VFile {
    return VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
        kind = VFileKind.FILE,
        sizeBytes = 4L,
        modifiedAtEpochMillis = null,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT),
    )
}

/**
 * 创建测试目录。
 *
 * @param name 目录名。
 * @param location 目录位置。
 * @return 目录条目。
 */
private fun directory(name: String, location: String): VFile {
    return VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
        kind = VFileKind.DIRECTORY,
        sizeBytes = null,
        modifiedAtEpochMillis = null,
        hidden = false,
        capabilities = setOf(VFileCapability.LIST_CHILDREN),
    )
}
