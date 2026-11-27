package com.oruke.onyx.app.platform

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/**
 * 系统剪贴板轻量无锁探测边界，用于在调用 AWT `getContents(null)` 之前判断剪贴板是否变化及是否包含文件列表。
 */
internal interface ClipboardChangeProbe {
    /**
     * 读取系统剪贴板全局变更序号，每次剪贴板被任意进程改写都会递增。
     *
     * @return 无符号变更序号；平台不支持或查询失败时返回 `null`。
     */
    fun sequenceNumber(): Long?

    /**
     * 无锁探测当前系统剪贴板是否包含原生文件列表格式（Windows `CF_HDROP`）。
     *
     * @return 明确包含文件列表返回 `true`，明确不含返回 `false`，平台不支持返回 `null`。
     */
    fun hasNativeFileList(): Boolean?
}

/**
 * Windows 无锁剪贴板探测器，直接调用 `GetClipboardSequenceNumber` 与 `IsClipboardFormatAvailable`，
 * 不打开剪贴板锁、不拷贝截图/图片或富文本字节。
 */
internal object WindowsClipboardProbe : ClipboardChangeProbe {
    /** Windows 原生拖放文件列表格式 ID（`CF_HDROP`）。 */
    private const val CF_HDROP = 15

    /** 32 位无符号整型掩码，防止序号溢出成负数。 */
    private const val UNSIGNED_INT_MASK = 0xFFFFFFFFL

    /** 当前运行环境是否为 Windows。 */
    private val isWindows: Boolean =
        currentDesktopOperatingSystem() == DesktopOperatingSystem.WINDOWS

    /**
     * 查询 Windows 全局剪贴板序号。
     *
     * @return 非零序号；非 Windows 平台或 Win32 返回 0 时返回 `null`。
     */
    override fun sequenceNumber(): Long? {
        if (!isWindows) return null
        return runCatching {
            val raw = Win32ClipboardUser32.INSTANCE.GetClipboardSequenceNumber()
            if (raw == 0) null else (raw.toLong() and UNSIGNED_INT_MASK)
        }.getOrNull()
    }

    /**
     * 无锁检查系统剪贴板当前是否具备 `CF_HDROP` 文件列表格式。
     *
     * @return 具备文件列表返回 `true`，否则返回 `false`；非 Windows 平台或调用异常返回 `null`。
     */
    override fun hasNativeFileList(): Boolean? {
        if (!isWindows) return null
        return runCatching {
            Win32ClipboardUser32.INSTANCE.IsClipboardFormatAvailable(CF_HDROP)
        }.getOrNull()
    }

    /** 最小化 Win32 `user32` 剪贴板无锁探测接口。 */
    @Suppress("FunctionName")
    private interface Win32ClipboardUser32 : StdCallLibrary {
        /** 返回剪贴板全局变更序号，无需打开剪贴板。 */
        fun GetClipboardSequenceNumber(): Int

        /** 判断指定剪贴板格式是否可用，无需打开剪贴板。 */
        fun IsClipboardFormatAvailable(format: Int): Boolean

        companion object {
            /** `user32` 动态库单例。 */
            val INSTANCE: Win32ClipboardUser32 by lazy {
                Native.load("user32", Win32ClipboardUser32::class.java)
            }
        }
    }
}
