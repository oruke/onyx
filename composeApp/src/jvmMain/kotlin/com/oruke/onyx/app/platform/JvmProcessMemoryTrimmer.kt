package com.oruke.onyx.app.platform

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.ManagedBitmapRegistry
import com.sun.jna.Native
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 进程级闲置内存回收器。
 *
 * 当全部主窗口卸载并进入后台托盘驻留后，先等待短时冷却以避免用户误关立即重开产生额外抖动，
 * 再执行一次显式 GC 促使 ShenandoahGC 回收已卸载的 Compose/Skiko 对象并归还空闲堆，
 * 随后在 Windows 平台隔离调用 `SetProcessWorkingSetSizeEx(-1, -1)` 提示内核回收闲置物理页。
 */
internal object JvmProcessMemoryTrimmer {
    /** 隐藏入托盘后触发主动回收前的退让时长，避免短时间切回窗口时引发多余 GC。 */
    private const val BACKGROUND_RECLAIM_DELAY_MS = 1_500L

    /** Windows `SetProcessWorkingSetSizeEx` 传入 `-1` 表示通知系统尽可能裁剪当前进程工作集。 */
    private const val TRIM_WORKING_SET_SENTINEL = -1L

    /**
     * 在后台托盘态等待短暂退让后主动回收已卸载窗口产生的内存与操作系统工作集。
     *
     * 调用方通过取消协程即可在窗口重新唤醒时中断等待，不阻断前台响应。
     */
    suspend fun reclaimAfterEnteringBackground() {
        delay(BACKGROUND_RECLAIM_DELAY_MS)
        withContext(Dispatchers.IO) {
            ManagedBitmapRegistry.flushPendingCloses()
            System.gc()
            val trimmedNative = trimOperatingSystemWorkingSet()
            OnyxLogger.info(
                "Startup",
                "后台托盘内存回收完成（osWorkingSetTrimmed=$trimmedNative）",
            )
        }
    }

    /**
     * 向宿主机操作系统发起工作集裁剪请求；仅在 Windows 平台调用 Win32 API，其他平台静默跳过。
     *
     * @return Windows 下成功调用内核工作集裁剪返回 `true`，其余情况返回 `false`。
     */
    internal fun trimOperatingSystemWorkingSet(): Boolean {
        if (currentDesktopOperatingSystem() != DesktopOperatingSystem.WINDOWS) return false
        return runCatching {
            val currentProcess = Kernel32.INSTANCE.GetCurrentProcess()
            val sentinel = BaseTSD.SIZE_T(TRIM_WORKING_SET_SENTINEL)
            Win32MemoryKernel32.INSTANCE.SetProcessWorkingSetSizeEx(
                hProcess = currentProcess,
                dwMinimumWorkingSetSize = sentinel,
                dwMaximumWorkingSetSize = sentinel,
                flags = 0,
            )
        }.getOrDefault(false)
    }

    /** 最小化 Win32 `kernel32` 进程工作集裁剪接口。 */
    @Suppress("FunctionName")
    private interface Win32MemoryKernel32 : StdCallLibrary {
        /**
         * 调整指定进程工作集边界；最小与最大尺寸均传 `(SIZE_T)-1` 时触发立即裁剪闲置物理页。
         *
         * @param hProcess 目标进程句柄。
         * @param dwMinimumWorkingSetSize 最小工作集尺寸或 `-1`。
         * @param dwMaximumWorkingSetSize 最大工作集尺寸或 `-1`。
         * @param flags 工作集限制标志位。
         * @return 调用成功返回 `true`。
         */
        fun SetProcessWorkingSetSizeEx(
            hProcess: WinNT.HANDLE,
            dwMinimumWorkingSetSize: BaseTSD.SIZE_T,
            dwMaximumWorkingSetSize: BaseTSD.SIZE_T,
            flags: Int,
        ): Boolean

        companion object {
            /** `kernel32` 动态库单例。 */
            val INSTANCE: Win32MemoryKernel32 by lazy {
                Native.load("kernel32", Win32MemoryKernel32::class.java)
            }
        }
    }
}
