package com.oruke.onyx.vfs.local

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * 读取入口自身的可选对象身份，Windows JVM 缺少 fileKey 时使用原生 128 位文件 ID。
 * @param attributes 已使用 NOFOLLOW_LINKS 读取的入口属性。
 * @return 文件系统提供的稳定对象身份；不提供该能力时为空。
 */
internal fun Path.readLocalIdentityKey(attributes: BasicFileAttributes): String? {
    val key = attributes.fileKey()?.toString()
        ?: if (Platform.isWindows()) WindowsFileIdentityAccess.read(this) else null
    // NTFS 名称隧道可能在交换路径时恢复旧创建时间，时间属性不能参与对象身份。
    return key
}

/**
 * 使用 Win32 扩展路径调用原生元数据接口，避免 NIO 可列出的长路径在额外身份查询时失败。
 * @return 保留盘符或 UNC 语义的绝对扩展路径。
 */
internal fun Path.toWindowsIdentityPath(): String {
    val value = toAbsolutePath().normalize().toString()
    return when {
        value.startsWith("\\\\?\\") -> value
        value.startsWith("\\\\") -> "\\\\?\\UNC\\${value.drop(2)}"
        else -> "\\\\?\\$value"
    }
}

/** 使用只读取元数据的入口句柄取得 Windows 文件身份，不跟随离线挂载或符号链接目标。 */
internal object WindowsFileIdentityAccess {
    /** FileIdInfo 信息类别，返回卷身份和 128 位文件身份。 */
    private const val FILE_ID_INFORMATION_CLASS = 18
    /** 不透明身份字符串使用十六进制表示。 */
    private const val IDENTITY_RADIX = 16

    /** 可选身份查询被文件系统或访问策略明确拒绝时保留普通列项能力。 */
    private val unavailableErrors = setOf(WinError.ERROR_INVALID_FUNCTION, WinError.ERROR_NOT_SUPPORTED,
        WinError.ERROR_INVALID_PARAMETER, WinError.ERROR_ACCESS_DENIED, WinError.ERROR_SHARING_VIOLATION)

    /**
     * 打开入口自身的只读元数据句柄，查询身份并始终关闭句柄。
     * @param path 需要取得对象身份的真实入口路径。
     * @return 不透明对象身份；不支持身份查询时为空，其他 I/O 失败继续上抛。
     */
    fun read(path: Path): String? {
        val api = Kernel32.INSTANCE
        val handle = api.CreateFile(path.toWindowsIdentityPath(), 0,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE, null,
            WinNT.OPEN_EXISTING, WinNT.FILE_FLAG_OPEN_REPARSE_POINT or WinNT.FILE_FLAG_BACKUP_SEMANTICS, null)
        if (handle == WinBase.INVALID_HANDLE_VALUE) {
            val error = api.GetLastError()
            if (error in unavailableErrors) return null
            throw path.windowsMetadataFailure(error)
        }
        return Closeable {
            if (!api.CloseHandle(handle)) throw path.windowsMetadataFailure(api.GetLastError())
        }.use { readIdentity(handle, path) }
    }

    /**
     * 从已打开的入口句柄读取卷序列和完整文件身份；全零身份不参与改名推断。
     * @param handle 已打开且不跟随目标的入口句柄。
     * @param path 错误反馈对应的入口路径。
     * @return 可用的对象身份，不支持时为空。
     */
    private fun readIdentity(handle: WinNT.HANDLE, path: Path): String? {
        val information = WinBase.FILE_ID_INFO()
        val api = Kernel32.INSTANCE
        if (!api.GetFileInformationByHandleEx(handle, FILE_ID_INFORMATION_CLASS,
                information.pointer, DWORD(information.size().toLong()))) {
            val error = api.GetLastError()
            if (error in unavailableErrors) return null
            throw path.windowsMetadataFailure(error)
        }
        information.read()
        val identifier = information.FileId.Identifier
        return if (identifier.any { it.toInt() != 0 }) {
            val fileId = identifier.joinToString("") { it.toInt().toString(IDENTITY_RADIX).padStart(2, '0') }
            "${information.VolumeSerialNumber.toULong().toString(IDENTITY_RADIX)}:$fileId"
        } else null
    }
}
