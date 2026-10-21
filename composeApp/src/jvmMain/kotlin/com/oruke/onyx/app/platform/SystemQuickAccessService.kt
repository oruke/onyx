package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.SystemQuickAccessLocation

/**
 * 读取操作系统文件管理器快速访问位置的统一平台接口。
 */
internal interface SystemQuickAccessService {
    /**
     * 返回无需 I/O 即可构造的初始位置，供首帧立即渲染。
     *
     * @return 至少包含用户主目录的初始位置。
     */
    fun initialLocations(): List<SystemQuickAccessLocation>

    /**
     * 在后台读取操作系统维护的快速访问位置。
     *
     * @return 成功时返回去重后的快速访问位置，失败时保留具体异常。
     */
    suspend fun loadLocations(): Result<List<SystemQuickAccessLocation>>

    /**
     * 查询电脑概览中的标准目录，使用操作系统目录注册信息而不是用户固定书签。
     * @return 主目录与系统标准目录的实际位置。
     */
    suspend fun loadComputerPlaces(): Result<List<SystemQuickAccessLocation>> = loadLocations()
}
