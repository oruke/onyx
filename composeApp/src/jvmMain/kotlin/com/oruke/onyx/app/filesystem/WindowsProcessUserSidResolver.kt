package com.oruke.onyx.app.filesystem

import com.sun.jna.Memory
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.pathString
import kotlin.streams.toList

/**
 * Windows 进程用户 SID 解析与回收站路径扫描工具。
 *
 * 通过 JNA 读取当前进程访问令牌提取用户真实 SID，规避对 `user.home` 文件属主枚举的依赖；
 * 同时按卷类型过滤网络映射盘，直达回收站 SID 目录。
 */
internal object WindowsProcessUserSidResolver {

    /** 回收站元数据文件前缀。 */
    private const val WINDOWS_RECYCLE_INFO_PREFIX = "\$I"

    /** 当前宿主进程用户的 SID 字符串；非 Windows 平台或解析失败时为 null。 */
    val currentUserSid: String? by lazy { resolveProcessUserSid() }

    /**
     * 解析当前进程访问令牌的用户 SID 字符串。
     *
     * 优先通过 `Advapi32.INSTANCE.OpenProcessToken` 与 `GetTokenInformation` 直接提取；
     * 失败或异常时依次回退 `Advapi32Util.getTokenAccount` 与 `Advapi32Util.getAccountByName`。
     */
    private fun resolveProcessUserSid(): String? {
        if (!isWindows()) return null
        return runCatching {
            var tokenHandle: WinNT.HANDLEByReference? = null
            try {
                val handleRef = WinNT.HANDLEByReference()
                val processHandle = Kernel32.INSTANCE.GetCurrentProcess()
                val tokenOpened = Advapi32.INSTANCE.OpenProcessToken(
                    processHandle,
                    WinNT.TOKEN_QUERY,
                    handleRef,
                )
                if (tokenOpened) {
                    tokenHandle = handleRef
                    val sidString = readSidFromTokenHandle(handleRef.value)
                    if (!sidString.isNullOrBlank()) {
                        sidString
                    } else {
                        val accountSid = runCatching {
                            Advapi32Util.getTokenAccount(handleRef.value)?.sidString
                        }.getOrNull()
                        if (!accountSid.isNullOrBlank()) {
                            accountSid
                        } else {
                            fallbackUserNameSid()
                        }
                    }
                } else {
                    fallbackUserNameSid()
                }
            } finally {
                tokenHandle?.value?.let { handle ->
                    if (handle != WinBase.INVALID_HANDLE_VALUE) {
                        Kernel32.INSTANCE.CloseHandle(handle)
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * 通过 JNA 获取令牌的用户 SID。
     */
    private fun readSidFromTokenHandle(handle: WinNT.HANDLE): String? {
        val returnLength = IntByReference()
        Advapi32.INSTANCE.GetTokenInformation(
            handle,
            WinNT.TOKEN_INFORMATION_CLASS.TokenUser,
            null,
            0,
            returnLength,
        )
        val size = returnLength.value
        if (size <= 0) return null
        val memory = Memory(size.toLong()).apply { clear() }
        val tokenUser = WinNT.TOKEN_USER(memory)
        val success = Advapi32.INSTANCE.GetTokenInformation(
            handle,
            WinNT.TOKEN_INFORMATION_CLASS.TokenUser,
            tokenUser,
            size,
            returnLength,
        )
        return if (success) {
            tokenUser.read()
            tokenUser.User?.Sid?.let(Advapi32Util::convertSidToStringSid)
        } else {
            null
        }
    }

    /**
     * 回退方案：通过用户名解析 SID。
     */
    private fun fallbackUserNameSid(): String? {
        return runCatching {
            val userName = Advapi32Util.getUserName()
            Advapi32Util.getAccountByName(userName)?.sidString
        }.getOrNull()
    }

    /**
     * 收集指定回收站根目录下的 `$I` 元数据文件。
     *
     * @param recycleRoot `$Recycle.Bin` 根路径。
     * @param currentUserSid 当前用户 SID；非空白时直达对应 SID 子目录并收集顶层文件，为空时扫描顶层及一层普通子目录。
     * @return 匹配的 `$I` 元数据文件列表。
     */
    fun collectRecycleBinInfoFiles(
        recycleRoot: Path,
        currentUserSid: String? = this.currentUserSid,
    ): List<Path> {
        val candidates = if (!currentUserSid.isNullOrBlank()) {
            collectUserSidAndTopInfoFiles(recycleRoot, currentUserSid)
        } else {
            collectUnscopedInfoFiles(recycleRoot)
        }
        return candidates.distinctBy { it.toAbsolutePath().normalize() }
    }

    private fun collectUserSidAndTopInfoFiles(recycleRoot: Path, userSid: String): List<Path> {
        val userSidDir = recycleRoot.resolve(userSid)
        val sidFiles = if (Files.isDirectory(userSidDir, LinkOption.NOFOLLOW_LINKS)) {
            userSidDir.safeChildren().filter { it.isRecycleInfoFile() }
        } else {
            emptyList()
        }
        val topFiles = recycleRoot.safeChildren().filter { it.isRecycleInfoFile() }
        return sidFiles + topFiles
    }

    private fun collectUnscopedInfoFiles(recycleRoot: Path): List<Path> {
        val result = mutableListOf<Path>()
        for (child in recycleRoot.safeChildren()) {
            if (child.isRecycleInfoFile()) {
                result.add(child)
            } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                result.addAll(child.safeChildren().filter { it.isRecycleInfoFile() })
            }
        }
        return result
    }

    private fun Path.isRecycleInfoFile(): Boolean =
        fileName?.toString()?.startsWith(WINDOWS_RECYCLE_INFO_PREFIX, ignoreCase = true) == true

    /**
     * 判断根路径是否为本地或可移动磁盘，排除网络映射盘与 UNC 根。
     *
     * @param root 待检查的文件系统根路径。
     * @return 为本地卷、可移动存储或 RAM 磁盘时返回 true。
     */
    fun isLocalOrRemovableSystemRoot(root: Path): Boolean {
        val raw = root.pathString
        return when {
            raw.startsWith("\\\\") || raw.startsWith("//") -> false
            !isWindows() -> true
            else -> runCatching {
                val driveRoot = if (raw.endsWith("\\") || raw.endsWith("/")) raw else "$raw\\"
                when (Kernel32.INSTANCE.GetDriveType(driveRoot)) {
                    WinBase.DRIVE_FIXED,
                    WinBase.DRIVE_REMOVABLE,
                    WinBase.DRIVE_RAMDISK -> true
                    else -> false
                }
            }.getOrDefault(false)
        }
    }

    private fun Path.safeChildren(): List<Path> = runCatching {
        if (!Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS)) {
            emptyList()
        } else {
            Files.list(this).use { stream -> stream.toList() }
        }
    }.getOrDefault(emptyList())

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("win") == true
}
