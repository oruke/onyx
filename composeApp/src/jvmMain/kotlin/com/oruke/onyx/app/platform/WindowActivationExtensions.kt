package com.oruke.onyx.app.platform

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import javax.swing.SwingUtilities

/** 当前宿主是否为 Windows，用于处理跨进程唤醒时的 Win32 前台焦点锁限制。 */
private val isWindowsHost: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * 在显式打开窗口时同时恢复 Compose 状态和原生窗口，再请求前台焦点。
 * @param state 当前窗口持续保留的 Compose 状态。
 * @return 无返回值；仅响应用户激活动作，后台内容更新不得调用。
 */
internal fun ComposeWindow.activateFromUserRequest(state: WindowState) {
    isAutoRequestFocus = true
    if (!isVisible) {
        isVisible = true
    }
    state.isMinimized = false
    // macOS 的原生最小化事件可能晚于 Compose 状态回写，不能仅给已经为 false 的状态再次赋值。
    extendedState = extendedState and Frame.ICONIFIED.inv()
    toFront()
    requestFocus()
    if (isWindowsHost) {
        SwingUtilities.invokeLater {
            if (!isVisible) return@invokeLater
            if (!isAlwaysOnTop) {
                isAlwaysOnTop = true
                toFront()
                isAlwaysOnTop = false
            } else {
                toFront()
            }
            requestFocus()
            requestFocusInWindow()
        }
    }
}

/**
 * 在后台新任务自动唤起任务窗口时将其恢复显示并置顶到非置顶窗口最上方，但不抢占原活动窗口的键盘焦点。
 *
 * @param state 当前窗口持续保留的 Compose 状态。
 * @param previousFocusedWindow 自动弹窗前持有焦点的应用窗口；若创建原生窗口时短暂夺走焦点则安全归还。
 */
internal fun ComposeWindow.revealFromBackgroundTask(
    state: WindowState,
    previousFocusedWindow: Window? = null,
) {
    isAutoRequestFocus = false
    if (!isVisible) {
        isVisible = true
    }
    state.isMinimized = false
    extendedState = extendedState and Frame.ICONIFIED.inv()
    toFront()
    SwingUtilities.invokeLater {
        if (!isVisible) {
            isAutoRequestFocus = true
            return@invokeLater
        }
        val currentFocused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        val shouldRestoreOwnerFocus = previousFocusedWindow != null &&
            previousFocusedWindow !== this &&
            previousFocusedWindow.isShowing &&
            currentFocused === this
        if (shouldRestoreOwnerFocus) {
            val wasTopMost = isAlwaysOnTop
            if (!wasTopMost) {
                isAlwaysOnTop = true
            }
            previousFocusedWindow.requestFocus()
            SwingUtilities.invokeLater {
                if (!wasTopMost && isVisible) {
                    // 在 isAutoRequestFocus = false 下取消置顶（HWND_NOTOPMOST + SWP_NOACTIVATE），保持位于主窗口上方且不抢焦点。
                    isAlwaysOnTop = false
                }
                isAutoRequestFocus = true
            }
        } else {
            isAutoRequestFocus = true
        }
    }
}
