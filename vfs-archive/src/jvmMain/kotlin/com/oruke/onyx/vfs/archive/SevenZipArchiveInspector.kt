package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID

/**
 * 检查 7-Zip 归档的加密状态并验证密码。
 *
 * @param logger 归档处理日志出口。
 * @param randomAccessService 归档随机访问服务。
 */
internal class SevenZipArchiveInspector(
    private val logger: ArchiveServiceLogger,
    private val randomAccessService: RoutableVfsRandomAccessService,
) {
    /**
     * 判断归档是否加密（同时覆盖普通内容加密与 7z `-mhe=on` / RAR `-hp` 头部加密）。
     *
     * 通过实现 [IArchiveOpenCallback] + [ICryptoGetTextPassword] 探测打开阶段是否收到密码回调，
     * 可精准识别头部加密归档而不会与损坏文件混淆。
     *
     * @param archivePath 归档物理路径。
     * @return 包含加密头部或加密条目时返回 `true`；非加密或探测失败时返回 `false`。
     */
    suspend fun isEncrypted(archivePath: String): Boolean {
        if (archivePath.isTarZstdArchive()) return false
        val probeCallback = HeaderEncryptionProbeCallback()
        return runCatching {
            openSevenZipArchive(
                path = archivePath,
                randomAccessService = randomAccessService,
                openCallback = probeCallback,
            ).use { handle ->
                if (probeCallback.passwordRequested) return@use true
                val archive = handle.archive
                (0 until archive.numberOfItems).any { index ->
                    archive.getProperty(index, PropID.ENCRYPTED) as? Boolean ?: false
                }
            }
        }.getOrElse { failure ->
            failure.throwIfCancellation()
            if (probeCallback.passwordRequested) {
                true
            } else {
                logger.warn("ArchiveService", "检测压缩包加密状态异常: $archivePath", failure)
                false
            }
        }
    }

    /**
     * 验证归档密码。
     *
     * 先执行不落盘的 test 解压，再在存在 CRC 时读取内容并校验，兼容不会返回错误密码状态的 ZipCrypto。
     *
     * @param archivePath 归档物理路径。
     * @param password 待验证密码。
     * @return 密码有效或归档没有加密文件时返回 `true`。
     */
    suspend fun verifyPassword(
        archivePath: String,
        password: String,
    ): Boolean {
        return if (archivePath.isTarZstdArchive()) {
            true
        } else {
            runCatching {
                openSevenZipArchive(archivePath, randomAccessService, password).use { handle ->
                    verifyPassword(handle.archive, password)
                }
            }.onFailure { failure ->
                failure.throwIfCancellation()
                logger.error("ArchiveService", "verifyPassword 异常", failure)
            }.getOrDefault(false)
        }
    }

    /**
     * 在已打开归档中验证密码。
     *
     * @param archive 已打开的 7-Zip 归档。
     * @param password 待验证密码。
     * @return 密码验证结果。
     */
    private fun verifyPassword(
        archive: IInArchive,
        password: String,
    ): Boolean {
        val testIndex = encryptedFileIndex(archive)
        return if (testIndex == null) {
            true
        } else {
            val testCallback = TestExtractCallback(password)
            archive.extract(intArrayOf(testIndex), true, testCallback)
            val testPassed = testCallback.errors.isEmpty()
            val storedCrc = archive.getProperty(testIndex, PropID.CRC) as? Int
            testPassed && (storedCrc == null || verifyCrc(archive, testIndex, password, storedCrc))
        }
    }

    /**
     * 查找第一个加密且非空的文件条目。
     *
     * @param archive 已打开的 7-Zip 归档。
     * @return 条目索引；没有需要验证的条目时返回 `null`。
     */
    private fun encryptedFileIndex(archive: IInArchive): Int? {
        return (0 until archive.numberOfItems).firstOrNull { index ->
            val encrypted = archive.getProperty(index, PropID.ENCRYPTED) as? Boolean ?: false
            val isDirectory = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
            val size = archive.getProperty(index, PropID.SIZE) as? Long ?: 0L
            encrypted && !isDirectory && size > 0
        }
    }

    /**
     * 解压条目并校验 CRC32。
     *
     * @param archive 已打开的 7-Zip 归档。
     * @param index 待验证条目索引。
     * @param password 待验证密码。
     * @param storedCrc 归档记录的 CRC32。
     * @return 内容解压成功且 CRC32 一致时返回 `true`。
     */
    private fun verifyCrc(
        archive: IInArchive,
        index: Int,
        password: String,
        storedCrc: Int,
    ): Boolean {
        val buffer = ByteArrayOutputStream()
        val callback = MemoryExtractCallback(buffer, password)
        archive.extract(intArrayOf(index), false, callback)
        val data = buffer.toByteArray()
        val computedCrc = CRC32().apply { update(data) }.value.toInt()
        return callback.errors.isEmpty() && data.isNotEmpty() && computedCrc == storedCrc
    }
}

/**
 * 探测归档打开阶段是否触发加密头部密码回调（如 7z `-mhe=on`、RAR `-hp`）。
 */
private class HeaderEncryptionProbeCallback : IArchiveOpenCallback, ICryptoGetTextPassword {
    /** 7-Zip 引擎在解析文件头期间是否主动请求了文本密码。 */
    @Volatile
    var passwordRequested: Boolean = false
        private set

    override fun setTotal(files: Long?, bytes: Long?) = Unit

    override fun setCompleted(files: Long?, bytes: Long?) = Unit

    override fun cryptoGetTextPassword(): String {
        passwordRequested = true
        return ""
    }
}

/**
 * 在降级处理前重新抛出协程取消，避免把取消误判为普通归档错误。
 */
private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}
