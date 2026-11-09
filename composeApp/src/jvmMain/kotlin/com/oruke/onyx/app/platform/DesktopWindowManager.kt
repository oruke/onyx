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
    /** 窗口当前是否可见；主窗口常驻模式下点击关闭仅设为 `false` 保持热态。 */
    val visible: Boolean = true,
    /** 显式唤醒计数（大于 `0` 时触发窗口置前聚焦与目录刷新）。 */
    val activationRequest: Long = 0L,
    /** 唤醒已常驻窗口时外部传入的待打开目录。 */
    val pendingOpenLocation: String? = null,
)

/**
 * 管理文件管理器窗口集合及其稳定 ID。
 *
 * @param keepPrimaryWindowResident 关闭主窗口时是否将其隐藏驻留后台以支持毫秒级二次秒起。
 * @param primaryWindowInitiallyVisible 主窗口启动时是否初始可见（`--background` 预热模式下为 `false`）。
 * @param initialPrimaryLocation 主窗口首次启动时的可选初始目录位置。
 */
internal class DesktopWindowManager(
    var keepPrimaryWindowResident: Boolean = false,
    primaryWindowInitiallyVisible: Boolean = true,
    initialPrimaryLocation: String? = null,
) {
    /** Compose 可观察的活动窗口集合。 */
    private val mutableWindows = mutableStateListOf(
        DesktopFileManagerWindowRequest(
            id = PRIMARY_WINDOW_ID,
            initialLocation = initialPrimaryLocation?.takeIf(String::isNotBlank),
            persistsMainSession = true,
            visible = primaryWindowInitiallyVisible,
        )
    )

    /** 下一个新窗口 ID。 */
    private var nextWindowId = PRIMARY_WINDOW_ID + 1L

    /** 下一次主窗口唤醒序号。 */
    private var nextActivationRequest = 1L

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
     * 唤醒并置前主窗口，必要时携带外部传入的待打开位置。
     *
     * @param location 外部请求在主窗口打开的可选路径。
     * @return 唤醒后的主窗口请求。
     */
    fun activatePrimaryWindow(location: String? = null): DesktopFileManagerWindowRequest {
        val sanitizedLocation = location?.takeIf(String::isNotBlank)
        val index = mutableWindows.indexOfFirst { request -> request.id == PRIMARY_WINDOW_ID }
        val activation = nextActivationRequest++
        if (index >= 0) {
            val updated = mutableWindows[index].copy(
                visible = true,
                activationRequest = activation,
                pendingOpenLocation = sanitizedLocation ?: mutableWindows[index].pendingOpenLocation,
            )
            mutableWindows[index] = updated
            return updated
        }
        return DesktopFileManagerWindowRequest(
            id = PRIMARY_WINDOW_ID,
            initialLocation = sanitizedLocation,
            persistsMainSession = true,
            visible = true,
            activationRequest = activation,
        ).also { created -> mutableWindows.add(0, created) }
    }

    /**
     * 消费主窗口已处理的外部待打开目录请求。
     *
     * @param windowId 目标窗口 ID。
     */
    fun consumePendingOpenLocation(windowId: Long) {
        val index = mutableWindows.indexOfFirst { request -> request.id == windowId }
        if (index >= 0 && mutableWindows[index].pendingOpenLocation != null) {
            mutableWindows[index] = mutableWindows[index].copy(pendingOpenLocation = null)
        }
    }

    /**
     * 关闭指定窗口。
     *
     * 当启用 [keepPrimaryWindowResident] 时，关闭主窗口只会将其设为隐藏热驻留并返回 `false`（不退出进程）。
     *
     * @param windowId 待关闭窗口 ID。
     * @return 成功关闭最后一个窗口且未启用主窗口驻留时返回 `true`。
     */
    fun closeWindow(windowId: Long): Boolean {
        if (keepPrimaryWindowResident && windowId == PRIMARY_WINDOW_ID) {
            val index = mutableWindows.indexOfFirst { request -> request.id == PRIMARY_WINDOW_ID }
            if (index >= 0 && mutableWindows[index].visible) {
                mutableWindows[index] = mutableWindows[index].copy(
                    visible = false,
                    pendingOpenLocation = null,
                )
            }
            return false
        }
        val removed = mutableWindows.removeAll { request -> request.id == windowId }
        if (keepPrimaryWindowResident) {
            return false
        }
        return removed && mutableWindows.isEmpty()
    }

    private companion object {
        /** 初始主窗口的固定 ID。 */
        const val PRIMARY_WINDOW_ID = 0L
    }
}
