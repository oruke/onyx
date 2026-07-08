package com.oruke.onyx.app.cache

import com.oruke.onyx.app.OnyxLogger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** 平台菜单缓存后台维护服务，负责启动初始化和菜单源变化检测。 */
internal class PlatformMenuCacheMaintenanceService(
    private val cacheService: PlatformMenuCacheService,
    private val fingerprintService: PlatformMenuSourceFingerprintService,
    private val warmupService: PlatformMenuCacheWarmupService,
    private val sourceWatchService: PlatformMenuSourceWatchService,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) {
    /**
     * 在当前协程中运行缓存维护循环。
     *
     * @return 只有协程被取消时才会正常返回。
     */
    suspend fun runUntilCancelled() {
        cacheService.initialize().onFailure { error ->
            OnyxLogger.warn(TAG, "初始化平台菜单缓存数据库失败", error)
        }
        refreshInvalidationState()
        warmUpCommonScopes()
        while (currentCoroutineContext().isActive) {
            sourceWatchService.awaitChange(pollIntervalMillis)
            if (refreshInvalidationState()) {
                warmUpCommonScopes()
            }
        }
    }

    /**
     * 计算平台来源指纹，并在发生变化时清空对应平台缓存。
     *
     * @return `true` 表示本次检测触发了平台菜单缓存失效。
     */
    private suspend fun refreshInvalidationState(): Boolean {
        return runCatching {
            val platform = fingerprintService.currentPlatform()
            val fingerprint = fingerprintService.currentFingerprint()
            val invalidated = cacheService.invalidatePlatformIfChanged(platform, fingerprint)
            if (invalidated) {
                OnyxLogger.info(TAG, "平台菜单来源已变化，已刷新 $platform 菜单缓存状态")
            }
            invalidated
        }.onFailure { error ->
            OnyxLogger.warn(TAG, "检测平台菜单缓存来源变化失败", error)
        }.getOrDefault(false)
    }

    /**
     * 预热常见菜单作用域，减少首次打开目录右键菜单时的系统扫描。
     */
    private suspend fun warmUpCommonScopes() {
        warmupService.warmUp().onFailure { error ->
            OnyxLogger.warn(TAG, "预热平台菜单缓存失败", error)
        }
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "PlatformMenuCache"

        /** 平台菜单源轮询间隔，避免频繁扫描注册表或应用目录。 */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 60_000L
    }
}
