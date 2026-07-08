package com.oruke.onyx.app.cache

import com.oruke.onyx.vfs.api.SystemMenuAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 平台菜单缓存服务，负责缓存命中、首次填充和平台级失效编排。 */
internal class PlatformMenuCacheService(
    private val databaseService: OnyxLocalDatabaseService,
    private val repository: PlatformMenuCacheRepository,
) {
    /**
     * 初始化缓存数据库。
     *
     * @return 初始化结果。
     */
    suspend fun initialize(): Result<Unit> {
        return databaseService.initialize()
    }

    /**
     * 读取缓存；缓存不存在时同步加载并写入快照。
     *
     * 这里刻意不在命中路径计算系统来源指纹，避免右键菜单再次被注册表或文件系统扫描拖慢。
     *
     * @param scope 平台菜单缓存作用域。
     * @param loader 缓存缺失时使用的平台菜单扫描函数。
     * @return 可展示的系统菜单动作列表。
     */
    suspend fun cachedOrLoad(
        scope: PlatformMenuCacheScope,
        loader: suspend () -> List<SystemMenuAction>,
    ): List<SystemMenuAction> {
        val cached = databaseService.transaction {
            val actions = repository.loadActions(scope)
            val hasSnapshot = actions.isNotEmpty() || repository.loadFingerprint(scope) != null
            if (hasSnapshot) actions else null
        }
        if (cached != null) return cached

        val loaded = loader()
        databaseService.transaction {
            repository.replaceActions(
                scope = scope,
                fingerprint = loaded.snapshotFingerprint(),
                actions = loaded,
                refreshedAtMillis = System.currentTimeMillis(),
            )
        }
        return loaded
    }

    /**
     * 当平台全局来源指纹变化时清空该平台菜单缓存。
     *
     * @param platform 当前宿主平台标识。
     * @param fingerprint 最新平台来源指纹。
     * @return `true` 表示本次检测触发了缓存失效。
     */
    suspend fun invalidatePlatformIfChanged(
        platform: String,
        fingerprint: String,
    ): Boolean {
        val metadataKey = platformMetadataKey(platform)
        return databaseService.transaction {
            val oldFingerprint = repository.loadMetadata(metadataKey)
            if (oldFingerprint == fingerprint) {
                false
            } else {
                repository.clearPlatform(platform)
                repository.replaceMetadata(metadataKey, fingerprint)
                true
            }
        }
    }

    /**
     * 清空指定平台的菜单缓存。
     *
     * @param platform 需要清理的平台标识。
     */
    suspend fun clearPlatform(platform: String) {
        databaseService.transaction {
            repository.clearPlatform(platform)
        }
    }

    /**
     * 生成平台全局元数据键。
     *
     * @param platform 宿主平台标识。
     * @return 元数据表中的稳定键名。
     */
    private fun platformMetadataKey(platform: String): String {
        return "platform-menu-source:$platform"
    }

    /**
     * 根据菜单动作内容生成稳定快照指纹。
     *
     * @return 菜单树内容哈希，空列表也会得到稳定值。
     */
    private fun List<SystemMenuAction>.snapshotFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        /**
         * 将菜单动作写入哈希输入。
         *
         * @param action 当前菜单动作。
         */
        fun appendAction(action: SystemMenuAction) {
            digest.update(action.id.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(action.displayName.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(action.command.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(action.iconPath.orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            action.children.forEach(::appendAction)
            digest.update(1.toByte())
        }

        forEach(::appendAction)
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
