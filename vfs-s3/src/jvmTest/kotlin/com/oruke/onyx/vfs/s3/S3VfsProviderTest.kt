package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsConnectionTester
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsDirectoryPageRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S3 VFS provider 的文件命令测试。
 */
class S3VfsProviderTest {
    /**
     * 验证目录复制会保留嵌套文件和空目录占位对象。
     */
    @Test
    fun `copy directory copies nested files and empty directories`() = runBlocking {
        val client = FakeS3Client(
            initialObjects = mapOf(
                "source/root.txt" to "root".encodeToByteArray(),
                "source/nested/child.txt" to "child".encodeToByteArray(),
                "source/empty/" to ByteArray(0),
            )
        )
        val provider = S3VfsProvider(authRepository = StaticS3AuthRepository, client = client)

        provider.copy(
            entries = listOf(directory("source", "s3://bucket/source/")),
            targetDirectoryLocation = "s3://bucket/target/",
            conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
        ).getOrThrow()

        assertContentEquals("root".encodeToByteArray(), client.objectBytes("target/source/root.txt"))
        assertContentEquals("child".encodeToByteArray(), client.objectBytes("target/source/nested/child.txt"))
        assertTrue(client.hasObject("target/source/empty/"))
    }

    /**
     * 验证 SKIP 冲突策略下移动文件不会误删未复制的源对象。
     */
    @Test
    fun `move file with skip conflict keeps source object`() = runBlocking {
        val client = FakeS3Client(
            initialObjects = mapOf(
                "source/file.txt" to "source".encodeToByteArray(),
                "target/file.txt" to "target".encodeToByteArray(),
            )
        )
        val provider = S3VfsProvider(authRepository = StaticS3AuthRepository, client = client)

        provider.move(
            entries = listOf(file("file.txt", "s3://bucket/source/file.txt")),
            targetDirectoryLocation = "s3://bucket/target/",
            conflictStrategy = TransferConflictStrategy.SKIP,
        ).getOrThrow()

        assertContentEquals("source".encodeToByteArray(), client.objectBytes("source/file.txt"))
        assertContentEquals("target".encodeToByteArray(), client.objectBytes("target/file.txt"))
    }

    /**
     * 验证删除目录会递归移除子对象和目录占位对象。
     */
    @Test
    fun `delete directory removes children and placeholder`() = runBlocking {
        val client = FakeS3Client(
            initialObjects = mapOf(
                "source/" to ByteArray(0),
                "source/root.txt" to "root".encodeToByteArray(),
                "source/nested/child.txt" to "child".encodeToByteArray(),
            )
        )
        val provider = S3VfsProvider(authRepository = StaticS3AuthRepository, client = client)

        provider.delete(listOf(directory("source", "s3://bucket/source/"))).getOrThrow()

        assertFalse(client.hasObject("source/"))
        assertFalse(client.hasObject("source/root.txt"))
        assertFalse(client.hasObject("source/nested/child.txt"))
    }

    /**
     * 验证写入文件会把内容落到目标 S3 对象。
     *
     * @return 无返回值。
     */
    @Test
    fun `write file stores object bytes`() = runBlocking {
        val client = FakeS3Client(initialObjects = emptyMap())
        val provider = S3VfsProvider(authRepository = StaticS3AuthRepository, client = client)

        val written = provider.writeFile(
            parentLocation = "s3://bucket/target/",
            name = "note.txt",
            chunks = flowOf("hello s3".encodeToByteArray()),
            conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
        ).getOrThrow()

        assertEquals("s3://bucket/target/note.txt", written?.location)
        assertContentEquals("hello s3".encodeToByteArray(), client.objectBytes("target/note.txt"))
    }

    /**
     * 验证创建文件和目录会分别写入空对象与目录占位对象。
     *
     * @return 无返回值。
     */
    @Test
    fun `create file and directory create s3 objects`() = runBlocking {
        val client = FakeS3Client(initialObjects = emptyMap())
        val provider = S3VfsProvider(authRepository = StaticS3AuthRepository, client = client)

        val fileEntry = provider.createFile(
            parentLocation = "s3://bucket/target/",
            name = "empty.txt",
        ).getOrThrow()
        val directoryEntry = provider.createDirectory(
            parentLocation = "s3://bucket/target/",
            name = "folder",
        ).getOrThrow()

        assertEquals("s3://bucket/target/empty.txt", fileEntry.location)
        assertEquals("s3://bucket/target/folder/", directoryEntry.location)
        assertContentEquals(ByteArray(0), client.objectBytes("target/empty.txt"))
        assertTrue(client.hasObject("target/folder/"))
    }

    /**
     * 验证 S3 provider 会把分页参数传给 client，并返回下一页 token。
     *
     * @return 无返回值。
     */
    @Test
    fun `list page delegates native s3 pagination`() = runBlocking {
        val client = FakeS3Client(
            initialObjects = mapOf(
                "source/a.txt" to "a".encodeToByteArray(),
                "source/b.txt" to "b".encodeToByteArray(),
            )
        )
        val provider = S3VfsProvider(authRepository = StaticS3AuthRepository, client = client)

        val page = provider.listPage(
            VfsDirectoryPageRequest(
                location = "s3://bucket/source/",
                pageSize = 1,
                pageToken = "token-1",
            )
        ).getOrThrow()

        assertEquals(1, page.entries.size)
        assertEquals("token-2", page.nextPageToken)
        assertEquals(1, client.lastPageSize)
        assertEquals("token-1", client.lastPageToken)
    }

    /** 验证 Provider 会按 VFS 根位置把服务商配置传给底层 S3 client。 */
    @Test
    fun `list passes registered connection config to client`() = runBlocking {
        val client = FakeS3Client(initialObjects = emptyMap())
        val connectionConfig = S3ConnectionConfig(
            provider = S3ProviderPreset.MINIO,
            endpoint = "http://minio.example.test:9000",
        )
        val connectionRepository = MutableS3ConnectionRepository().apply {
            replaceAll(listOf(S3ConnectionRegistration("s3://bucket/", connectionConfig)))
        }
        val provider = S3VfsProvider(
            authRepository = StaticS3AuthRepository,
            client = client,
            connectionRepository = connectionRepository,
        )

        provider.list("s3://bucket/source/").getOrThrow()

        assertEquals(connectionConfig, client.lastConnectionConfig)
    }

    /**
     * 创建目录测试条目。
     *
     * @param name 条目名。
     * @param location S3 目录位置。
     * @return 目录 VFile。
     */
    private fun directory(
        name: String,
        location: String,
    ): VFile {
        return VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = null,
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = emptySet(),
        )
    }

    /**
     * 创建文件测试条目。
     *
     * @param name 条目名。
     * @param location S3 文件位置。
     * @return 文件 VFile。
     */
    private fun file(
        name: String,
        location: String,
    ): VFile {
        return VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = null,
            kind = VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = emptySet(),
        )
    }

    private object StaticS3AuthRepository : S3AuthRepository {
        /**
         * 返回固定 AWS 凭据，供 fake client 通过认证分支。
         *
         * @param location 请求位置。
         * @return AWS 凭据上下文。
         */
        override fun authContext(location: String): VfsAuthContext {
            return VfsAuthContext.AwsCredentials(
                accessKeyId = "test-access",
                secretAccessKey = "test-secret",
                region = "us-east-1",
            )
        }
    }

    /**
     * 基于内存对象表的 S3 client，用于验证 provider 层递归命令语义。
     *
     * @param initialObjects 初始对象 key 到内容的映射。
     */
    private inner class FakeS3Client(
        initialObjects: Map<String, ByteArray>,
    ) : S3Client {
        /**
         * 内存中的 S3 对象表，key 为对象路径，value 为对象内容。
         */
        private val objects = initialObjects.toMutableMap()

        /** 最近一次分页请求的 pageSize。 */
        var lastPageSize: Int? = null

        /** 最近一次分页请求的 pageToken。 */
        var lastPageToken: String? = null

        /** 最近一次 client 调用收到的 S3 连接配置。 */
        var lastConnectionConfig: S3ConnectionConfig? = null

        /**
         * 判断对象是否存在。
         *
         * @param key S3 对象 key。
         * @return `true` 表示对象存在。
         */
        fun hasObject(key: String): Boolean = key in objects

        /**
         * 读取指定对象内容。
         *
         * @param key S3 对象 key。
         * @return 对象内容。
         */
        fun objectBytes(key: String): ByteArray = objects.getValue(key)

        /**
         * 验证连接时不访问网络。
         *
         * @param location 请求位置。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         */
        override suspend fun testConnection(
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ) = Unit

        /**
         * 按 S3 delimiter 语义列出当前前缀的直接子目录和文件。
         *
         * @param location 当前目录位置。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         * @return 当前目录直接子条目。
         */
        override suspend fun list(
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ): List<VFile> {
            lastConnectionConfig = connectionConfig
            val directoryPrefix = location.directoryPrefix
            val directories = linkedSetOf<String>()
            val files = mutableListOf<VFile>()
            objects.keys.sorted().forEach { key ->
                if (!key.startsWith(directoryPrefix)) return@forEach
                val relative = key.removePrefix(directoryPrefix)
                if (relative.isBlank()) return@forEach
                val slashIndex = relative.indexOf('/')
                if (slashIndex >= 0) {
                    directories += relative.substring(0, slashIndex)
                } else {
                    files += file(
                        name = relative,
                        location = location.toLocation(directoryPrefix + relative, directory = false),
                    )
                }
            }
            return directories.map { name ->
                directory(
                    name = name,
                    location = location.toLocation(directoryPrefix + name + "/", directory = true),
                )
            } + files
        }

        /**
         * 按测试所需的单页大小截断列表，并记录分页请求参数。
         *
         * @param location 当前目录位置。
         * @param pageSize 单页最大条目数。
         * @param pageToken continuation token。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         * @return 当前页和下一页 token。
         */
        override suspend fun listPage(
            location: S3Location,
            pageSize: Int,
            pageToken: String?,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ): S3ListPage {
            lastPageSize = pageSize
            lastPageToken = pageToken
            lastConnectionConfig = connectionConfig
            return S3ListPage(
                entries = list(location, authContext, connectionConfig).take(pageSize),
                nextContinuationToken = "token-2",
            )
        }

        /**
         * 读取指定对象内容。
         *
         * @param entry 源文件条目。
         * @param location 对象位置。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         * @return 内容源。
         */
        override suspend fun readFile(
            entry: VFile,
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ): VfsContentSource {
            return VfsContentSource(
                name = entry.name,
                sizeBytes = objects.getValue(location.objectKey).size.toLong(),
                chunks = flowOf(objects.getValue(location.objectKey)),
            )
        }

        /**
         * 写入对象内容，并按 SKIP 策略返回空结果。
         *
         * @param parentLocation 目标父目录。
         * @param name 目标文件名。
         * @param chunks 内容分块。
         * @param conflictStrategy 冲突处理策略。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         * @return 写入后的文件；跳过时返回 `null`。
         */
        override suspend fun writeFile(
            parentLocation: S3Location,
            name: String,
            chunks: Flow<ByteArray>,
            conflictStrategy: TransferConflictStrategy,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ): VFile? {
            val key = parentLocation.directoryPrefix + name
            if (key in objects && conflictStrategy == TransferConflictStrategy.SKIP) return null
            val output = mutableListOf<Byte>()
            chunks.collect { chunk -> output += chunk.toList() }
            objects[key] = output.toByteArray()
            return file(name, parentLocation.toLocation(key, directory = false))
        }

        /**
         * 删除对象。
         *
         * @param location 对象位置。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         */
        override suspend fun deleteObject(
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ) {
            objects -= location.objectKey
        }

        /**
         * 创建目录占位对象。
         *
         * @param location 目录位置。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         */
        override suspend fun createDirectory(
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ) {
            objects[location.directoryPrefix] = ByteArray(0)
        }

        /**
         * 判断对象是否存在。
         *
         * @param location 对象位置。
         * @param authContext AWS 凭据。
         * @param connectionConfig S3 Endpoint 与寻址配置。
         * @return `true` 表示存在。
         */
        override suspend fun objectExists(
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ): Boolean {
            return location.objectKey in objects
        }
    }
}
