package com.oruke.onyx.vfs.local

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import java.io.Closeable
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 读取 Windows 重解析点标记，区分重定向入口与普通云文件占位目录。 */
internal object WindowsReparsePointAccess {
    /**
     * 判断目录是否为 Junction 或其他指向另一名称的重解析入口。
     *
     * @param path 待检查的入口路径，不能预先解析成目标。
     * @param attributes 使用 NOFOLLOW_LINKS 读取的入口属性。
     * @return 名称替代型重解析点返回 true；其他平台和普通目录返回 false。
     */
    fun isDirectoryLink(path: Path, attributes: BasicFileAttributes): Boolean {
        if (!Platform.isWindows() || !attributes.isDirectory || !attributes.isOther) return false
        val api = Kernel32.INSTANCE
        val handle = api.CreateFile(
            path.toAbsolutePath().normalize().toString(),
            0,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OPEN_REPARSE_POINT or WinNT.FILE_FLAG_BACKUP_SEMANTICS,
            null,
        )
        if (handle == WinBase.INVALID_HANDLE_VALUE) throw path.windowsMetadataFailure(api.GetLastError())
        // 打开入口自身，即使其目标离线仍可读取标记；use 保留读取失败并附加关闭异常。
        return Closeable {
            if (!api.CloseHandle(handle)) throw path.windowsMetadataFailure(api.GetLastError())
        }.use { readNameSurrogateTag(handle, path) }
    }

    /**
     * 从不跟随目标的句柄读取名称替代标记，失败时禁止继续递归。
     *
     * @param handle 已打开的重解析入口句柄。
     * @param path 错误反馈所关联的入口路径。
     * @return 标记表示入口引用另一实体时返回 true。
     */
    private fun readNameSurrogateTag(handle: WinNT.HANDLE, path: Path): Boolean {
        val information = WinBase.FILE_ATTRIBUTE_TAG_INFO()
        val success = Kernel32.INSTANCE.GetFileInformationByHandleEx(
            handle,
            FILE_ATTRIBUTE_TAG_INFORMATION_CLASS,
            information.pointer,
            DWORD(information.size().toLong()),
        )
        if (!success) throw path.windowsMetadataFailure(Kernel32.INSTANCE.GetLastError())
        information.read()
        return information.FileAttributes and WinNT.FILE_ATTRIBUTE_REPARSE_POINT != 0 &&
            information.ReparseTag and NAME_SURROGATE_TAG_MASK != 0
    }

    /** GetFileInformationByHandleEx 的 FileAttributeTagInfo 信息类别。 */
    private const val FILE_ATTRIBUTE_TAG_INFORMATION_CLASS = 9
    /** Windows 重解析点标记中的名称替代位，包含 Junction 和目录符号链接。 */
    private const val NAME_SURROGATE_TAG_MASK = 0x20000000
}

/**
 * 保留 Win32 错误语义，使现有文件层错误映射能够区分不存在、权限及其他 I/O 失败。
 *
 * @param errorCode 失败调用紧接着取得的 Win32 错误码。
 * @return 携带入口路径和系统诊断的文件系统异常。
 */
private fun Path.windowsMetadataFailure(errorCode: Int): FileSystemException {
    val diagnostic = Win32Exception(errorCode)
    val failure = when (errorCode) {
        WinError.ERROR_FILE_NOT_FOUND, WinError.ERROR_PATH_NOT_FOUND ->
            NoSuchFileException(toString(), null, diagnostic.message)
        WinError.ERROR_ACCESS_DENIED -> AccessDeniedException(toString(), null, diagnostic.message)
        else -> FileSystemException(toString(), null, diagnostic.message)
    }
    failure.initCause(diagnostic)
    return failure
}
