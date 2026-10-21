package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.StandardUserDirectory
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.ptr.PointerByReference

/** 使用 Windows Known Folders 解析系统目录，支持用户重定向和云目录迁移。 */
internal class WindowsKnownDirectoriesSource : SystemQuickAccessSource {
    /** @return 操作系统注册的标准用户目录；不为读取目录列表创建目录或触发验证。 */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> = runCatching {
        val folders =
            listOf(
                StandardUserDirectory.DESKTOP to KnownFolders.FOLDERID_Desktop,
                StandardUserDirectory.DOCUMENTS to KnownFolders.FOLDERID_Documents,
                StandardUserDirectory.DOWNLOADS to KnownFolders.FOLDERID_Downloads,
                StandardUserDirectory.PICTURES to KnownFolders.FOLDERID_Pictures,
                StandardUserDirectory.MUSIC to KnownFolders.FOLDERID_Music,
                StandardUserDirectory.VIDEOS to KnownFolders.FOLDERID_Videos,
            )
        folders.map { (kind, identifier) ->
            val output = PointerByReference()
            try {
                COMUtils.checkRC(
                    Shell32.INSTANCE.SHGetKnownFolderPath(
                        identifier,
                        DONT_VERIFY_DIRECTORY,
                        null,
                        output,
                    )
                )
                val pointer = checkNotNull(output.value)
                SystemQuickAccessLocation(null, pointer.getWideString(0), standardDirectory = kind)
            } finally {
                // Known Folder API 在失败时也可能分配路径，必须按返回指针统一释放。
                output.value?.let(Ole32.INSTANCE::CoTaskMemFree)
            }
        }
    }

    private companion object {
        /** KF_FLAG_DONT_VERIFY：发现位置时不探测慢盘，也不隐式创建不存在的标准目录。 */
        const val DONT_VERIFY_DIRECTORY = 0x00004000
    }
}
