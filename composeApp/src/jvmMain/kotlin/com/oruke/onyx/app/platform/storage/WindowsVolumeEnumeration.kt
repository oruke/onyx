package com.oruke.onyx.app.platform.storage

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import java.io.Closeable

/** Windows 卷枚举的单一所有权对象，use 会保留主异常并附加关闭失败。 */
internal class WindowsVolumeEnumeration : Closeable {
    /** 当前线程使用的内核接口。 */
    private val api = Kernel32.INSTANCE
    /** 足够容纳卷 GUID 名称的缓冲区。 */
    private val buffer = CharArray(VOLUME_NAME_BUFFER_SIZE)
    /** 第一项已由 FindFirstVolume 写入，首次读取不能跳过它。 */
    private var first = true
    /** 是否已经到达枚举末尾。 */
    private var finished = false
    /** 本对象唯一持有的卷枚举句柄。 */
    private val handle =
        api.FindFirstVolume(buffer, buffer.size).also {
            if (it == WinBase.INVALID_HANDLE_VALUE) throw Win32Exception(api.GetLastError())
        }

    /** @return 下一个真实卷 GUID 路径；枚举结束后为空。 */
    fun next(): String? =
        when {
            finished -> null
            first -> {
                first = false
                Native.toString(buffer)
            }
            api.FindNextVolume(handle, buffer, buffer.size) -> Native.toString(buffer)
            else -> {
                val error = api.GetLastError()
                if (error != WinError.ERROR_NO_MORE_FILES) throw Win32Exception(error)
                finished = true
                null
            }
        }

    /** 使用与 FindFirstVolume 匹配的关闭 API，不把枚举句柄当作普通文件句柄。 */
    override fun close() {
        if (!api.FindVolumeClose(handle)) throw Win32Exception(api.GetLastError())
    }

    private companion object {
        /** Win32 卷 GUID 名称查询缓冲区长度。 */
        const val VOLUME_NAME_BUFFER_SIZE = 512
    }
}
