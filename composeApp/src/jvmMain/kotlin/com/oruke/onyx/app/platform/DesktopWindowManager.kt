package com.oruke.onyx.app.platform

import androidx.compose.runtime.mutableStateListOf

/**
 * 单个文件管理器窗口的创建请求。
 */
internal data class DesktopFileManagerWindowRequest(
    /** 应用运行期唯一窗口 ID。 */
    val id: Long,
    /** 新窗口需要打开的初始位置。 */
    val initialLocation: String?,
    /** 是否为负责恢复和保存主会话的初始窗口。 */
    val persistsMainSession: Boolean,
)

/**
 * 管理文件管理器窗口集合及其稳定 ID。
 */
internal class DesktopWindowManager {
    /** Compose 可观察的活动窗口集合。 */
    private val mutableWindows = mutableStateListOf(
        DesktopFileManagerWindowRequest(
            id = PRIMARY_WINDOW_ID,
            initialLocation = null,
            persistsMainSession = true,
        )
    )

    /** 下一个新窗口 ID。 */
    private var nextWindowId = PRIMARY_WINDOW_ID + 1L

    /** 当前活动文件管理器窗口。 */
    val windows: List<DesktopFileManagerWindowRequest>
        get() = mutableWindows

    /**
     * 为目录创建独立文件管理器窗口请求。
     *
     * @param location 待打开的 VFS 目录位置。
     * @return 已创建的窗口请求；空位置返回 `null`。
     */
    fun openDirectory(location: String): DesktopFileManagerWindowRequest? {
        if (location.isBlank()) return null
        return DesktopFileManagerWindowRequest(
            id = nextWindowId++,
            initialLocation = location,
            persistsMainSession = false,
        ).also(mutableWindows::add)
    }

    /**
     * 关闭指定窗口。
     *
     * @param windowId 待关闭窗口 ID。
     * @return 成功关闭最后一个窗口时返回 `true`。
     */
    fun closeWindow(windowId: Long): Boolean {
        val removed = mutableWindows.removeAll { request -> request.id == windowId }
        return removed && mutableWindows.isEmpty()
    }

    private companion object {
        /** 初始主窗口的固定 ID。 */
        const val PRIMARY_WINDOW_ID = 0L
    }
}
