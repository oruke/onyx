package com.oruke.onyx.app.platform

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState
import java.awt.Frame
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
