package com.oruke.onyx.vfs.archive

import net.sf.sevenzipjbinding.SevenZip
import java.io.File
import java.nio.file.Files

/**
 * 负责以进程隔离的临时目录初始化 SevenZipJBinding 原生库。
 *
 * SevenZipJBinding 默认按构建号复用同一个临时 DLL。Windows 会锁定已经加载的 DLL，
 * 因此同时运行安装版、开发版或测试进程时，后启动进程可能无法覆盖该文件。
 */
internal object SevenZipNativeRuntime {
    /** 初始化状态检查与临时目录创建共用锁。 */
    private val initializationLock = Any()

    /**
     * 确保当前 JVM 已从独立临时目录加载 SevenZipJBinding 原生库。
     */
    fun ensureInitialized() {
        if (SevenZip.isInitializedSuccessfully()) return
        synchronized(initializationLock) {
            if (SevenZip.isInitializedSuccessfully()) return
            val processTempDirectory = Files.createTempDirectory(PROCESS_TEMP_PREFIX).toFile()
            runCatching {
                SevenZip.initSevenZipFromPlatformJAR(processTempDirectory)
                registerTemporaryArtifactsForDeletion(processTempDirectory)
            }.onFailure {
                processTempDirectory.deleteRecursively()
            }.getOrThrow()
        }
    }

    /**
     * 将 JBinding 解包产物登记为 JVM 退出时删除。
     *
     * @param processTempDirectory 当前进程独占的临时根目录。
     */
    private fun registerTemporaryArtifactsForDeletion(processTempDirectory: File) {
        // JVM 会按登记顺序逆序删除，先登记父级才能在退出时先删除子级。
        processTempDirectory.deleteOnExit()
        SevenZip.getTemporaryArtifacts()
            ?.reversedArray()
            ?.forEach(File::deleteOnExit)
    }

    /** 每次 JVM 启动都会创建唯一目录的名称前缀。 */
    private const val PROCESS_TEMP_PREFIX = "onyx-sevenzip-"
}
