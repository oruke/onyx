package com.oruke.onyx.app.platform.storage

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** 通过专用隐藏窗口接收 Windows 卷变化，关闭订阅时停止消息循环并注销窗口类。 */
internal object WindowsStorageChanges {
    /** @return 不携带原生句柄的设备变化触发流。 */
    fun observe(): Flow<Unit> = callbackFlow {
        val closed = AtomicBoolean()
        val window = AtomicReference<HWND?>()
        val worker =
            thread(name = "onyx-volume-notifications", isDaemon = true) {
                runCatching { messageLoop(window, closed) { trySend(Unit) } }
                    .onFailure { close(it) }
            }
        awaitClose {
            closed.set(true)
            window.get()?.let {
                User32.INSTANCE.PostMessage(it, WinUser.WM_CLOSE, WPARAM(0), LPARAM(0))
            }
            worker.join(CLOSE_WAIT_MILLIS)
        }
    }
        .flowOn(Dispatchers.IO)

    /**
     * 为当前订阅运行消息循环；普通隐藏顶层窗口可以接收卷到达和移除广播。
     *
     * @param window 向取消处理暴露的当前窗口引用。
     * @param closed 订阅是否已经取消。
     * @param changed 只发送变化信号的回调。
     */
    private fun messageLoop(
        window: AtomicReference<HWND?>,
        closed: AtomicBoolean,
        changed: () -> Unit,
    ) {
        val api = User32.INSTANCE
        val definition = WinUser.WNDCLASSEX()
        definition.hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
        definition.lpszClassName = "OnyxStorage-${UUID.randomUUID()}"
        definition.lpfnWndProc = WinUser.WindowProc { handle, message, word, parameter ->
            when (message) {
                WinUser.WM_DEVICECHANGE -> {
                    changed()
                    LRESULT(0)
                }
                WinUser.WM_CLOSE -> {
                    api.DestroyWindow(handle)
                    LRESULT(0)
                }
                WinUser.WM_DESTROY -> {
                    api.PostQuitMessage(0)
                    LRESULT(0)
                }
                else -> api.DefWindowProc(handle, message, word, parameter)
            }
        }
        if (api.RegisterClassEx(definition).toInt() == 0)
            throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        Closeable { definition.releaseWindowClass(window.getAndSet(null)) }
            .use {
                val handle =
                    api.CreateWindowEx(
                        0,
                        definition.lpszClassName,
                        "",
                        0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        null,
                        definition.hInstance,
                        null,
                    ) ?: throw Win32Exception(Kernel32.INSTANCE.GetLastError())
                window.set(handle)
                if (closed.get()) api.PostMessage(handle, WinUser.WM_CLOSE, WPARAM(0), LPARAM(0))
                api.processStorageMessages()
            }
    }

    /** 接收当前线程消息直到原生窗口退出；系统错误通过观察流反馈。 */
    private fun User32.processStorageMessages() {
        val message = WinUser.MSG()
        var result: Int
        do {
            result = GetMessage(message, null, 0, 0)
            if (result > 0) {
                TranslateMessage(message)
                DispatchMessage(message)
            }
        } while (result > 0)
        if (result == -1) throw Win32Exception(Kernel32.INSTANCE.GetLastError())
    }

    /**
     * 销毁仍存活的本线程窗口并注销类，use 保留清理异常而不覆盖原始失败。
     *
     * @param window 本订阅尚未清理的窗口，正常退出后可能已被销毁。
     */
    private fun WinUser.WNDCLASSEX.releaseWindowClass(window: HWND?) {
        val api = User32.INSTANCE
        Closeable {
            if (!api.UnregisterClass(lpszClassName, hInstance))
                throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        }
            .use {
                if (window != null && api.IsWindow(window) && !api.DestroyWindow(window)) {
                    throw Win32Exception(Kernel32.INSTANCE.GetLastError())
                }
            }
    }

    /** 消息循环正常收到关闭通知后允许的最长退出等待时间。 */
    private const val CLOSE_WAIT_MILLIS = 1_000L
}
