package com.oruke.onyx.app.cache

import com.oruke.onyx.vfs.api.SystemMenuAction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** 平台菜单缓存 Repository，封装 Exposed DSL 查询与快照映射。 */
internal class PlatformMenuCacheRepository {
    /**
     * 读取指定作用域下的菜单动作树。
     *
     * @param scope 平台菜单缓存作用域。
     * @return 已恢复层级结构的系统菜单动作列表。
     */
    fun loadActions(scope: PlatformMenuCacheScope): List<SystemMenuAction> {
        val rows = PlatformMenuActionTable
            .selectAll()
            .where {
                (PlatformMenuActionTable.platform eq scope.platform) and
                    (PlatformMenuActionTable.scopeKey eq scope.scopeKey)
            }
            .orderBy(PlatformMenuActionTable.parentActionId to SortOrder.ASC_NULLS_FIRST)
            .orderBy(PlatformMenuActionTable.sortOrder to SortOrder.ASC)
            .map { row ->
                CachedPlatformMenuAction(
                    actionId = row[PlatformMenuActionTable.actionId],
                    parentActionId = row[PlatformMenuActionTable.parentActionId],
                    sortOrder = row[PlatformMenuActionTable.sortOrder],
                    displayName = row[PlatformMenuActionTable.displayName],
                    command = row[PlatformMenuActionTable.command],
                    iconPath = row[PlatformMenuActionTable.iconPath],
                )
            }
        return rows.toSystemMenuActions()
    }

    /**
     * 读取指定作用域的菜单来源指纹。
     *
     * @param scope 平台菜单缓存作用域。
     * @return 已保存的来源指纹；不存在时返回 `null`。
     */
    fun loadFingerprint(scope: PlatformMenuCacheScope): String? {
        return PlatformMenuSourceFingerprintTable
            .selectAll()
            .where {
                (PlatformMenuSourceFingerprintTable.platform eq scope.platform) and
                    (PlatformMenuSourceFingerprintTable.scopeKey eq scope.scopeKey)
            }
            .firstOrNull()
            ?.get(PlatformMenuSourceFingerprintTable.fingerprint)
    }

    /**
     * 原子替换指定作用域的菜单动作快照。
     *
     * @param scope 平台菜单缓存作用域。
     * @param fingerprint 当前菜单快照指纹。
     * @param actions 需要写入缓存的菜单动作树。
     * @param refreshedAtMillis 刷新时间，使用 epoch millis。
     */
    fun replaceActions(
        scope: PlatformMenuCacheScope,
        fingerprint: String,
        actions: List<SystemMenuAction>,
        refreshedAtMillis: Long,
    ) {
        PlatformMenuActionTable.deleteWhere {
            (PlatformMenuActionTable.platform eq scope.platform) and
                (PlatformMenuActionTable.scopeKey eq scope.scopeKey)
        }
        val flattened = actions.flattenActions()
        PlatformMenuActionTable.batchInsert(flattened) { action ->
            this[PlatformMenuActionTable.platform] = scope.platform
            this[PlatformMenuActionTable.scopeKey] = scope.scopeKey
            this[PlatformMenuActionTable.actionId] = action.actionId
            this[PlatformMenuActionTable.parentActionId] = action.parentActionId
            this[PlatformMenuActionTable.sortOrder] = action.sortOrder
            this[PlatformMenuActionTable.displayName] = action.displayName
            this[PlatformMenuActionTable.command] = action.command
            this[PlatformMenuActionTable.iconPath] = action.iconPath
        }
        replaceFingerprint(scope, fingerprint, refreshedAtMillis)
    }

    /**
     * 清空指定平台的所有菜单缓存。
     *
     * @param platform 需要清理的平台标识。
     */
    fun clearPlatform(platform: String) {
        PlatformMenuActionTable.deleteWhere {
            PlatformMenuActionTable.platform eq platform
        }
        PlatformMenuSourceFingerprintTable.deleteWhere {
            PlatformMenuSourceFingerprintTable.platform eq platform
        }
    }

    /**
     * 读取元数据值。
     *
     * @param key 元数据键名。
     * @return 已保存的元数据值；不存在时返回 `null`。
     */
    fun loadMetadata(key: String): String? {
        return CacheMetadataTable
            .selectAll()
            .where { CacheMetadataTable.key eq key }
            .firstOrNull()
            ?.get(CacheMetadataTable.value)
    }

    /**
     * 替换元数据值。
     *
     * @param key 元数据键名。
     * @param value 元数据值。
     */
    fun replaceMetadata(key: String, value: String) {
        val updatedRows = CacheMetadataTable.update({ CacheMetadataTable.key eq key }) { row ->
            row[CacheMetadataTable.value] = value
        }
        if (updatedRows == 0) {
            CacheMetadataTable.insert { row ->
                row[CacheMetadataTable.key] = key
                row[CacheMetadataTable.value] = value
            }
        }
    }

    /**
     * 替换指定作用域的来源指纹。
     *
     * @param scope 平台菜单缓存作用域。
     * @param fingerprint 当前来源指纹。
     * @param refreshedAtMillis 刷新时间，使用 epoch millis。
     */
    private fun replaceFingerprint(
        scope: PlatformMenuCacheScope,
        fingerprint: String,
        refreshedAtMillis: Long,
    ) {
        PlatformMenuSourceFingerprintTable.deleteWhere {
            (PlatformMenuSourceFingerprintTable.platform eq scope.platform) and
                (PlatformMenuSourceFingerprintTable.scopeKey eq scope.scopeKey)
        }
        PlatformMenuSourceFingerprintTable.insert { row ->
            row[PlatformMenuSourceFingerprintTable.platform] = scope.platform
            row[PlatformMenuSourceFingerprintTable.scopeKey] = scope.scopeKey
            row[PlatformMenuSourceFingerprintTable.fingerprint] = fingerprint
            row[PlatformMenuSourceFingerprintTable.refreshedAtMillis] = refreshedAtMillis
        }
    }

    /**
     * 将菜单树扁平化为数据库可保存的行结构。
     *
     * @return 按深度优先顺序展开的菜单动作列表。
     */
    private fun List<SystemMenuAction>.flattenActions(): List<CachedPlatformMenuAction> {
        return flatMapIndexed { index, action -> action.flatten(parentActionId = null, sortOrder = index) }
    }

    /**
     * 将单个菜单动作及其子项扁平化。
     *
     * @param parentActionId 父动作 ID；顶层菜单项为空。
     * @param sortOrder 当前动作在同级中的排序位置。
     * @return 当前动作与所有子动作的扁平行。
     */
    private fun SystemMenuAction.flatten(
        parentActionId: String?,
        sortOrder: Int,
    ): List<CachedPlatformMenuAction> {
        val current = CachedPlatformMenuAction(
            actionId = id,
            parentActionId = parentActionId,
            sortOrder = sortOrder,
            displayName = displayName,
            command = command,
            iconPath = iconPath,
        )
        return listOf(current) + children.flatMapIndexed { index, child ->
            child.flatten(parentActionId = id, sortOrder = index)
        }
    }

    /**
     * 将扁平缓存行恢复为系统菜单动作树。
     *
     * @return 顶层系统菜单动作列表。
     */
    private fun List<CachedPlatformMenuAction>.toSystemMenuActions(): List<SystemMenuAction> {
        val byParent = groupBy { action -> action.parentActionId }

        /**
         * 递归恢复指定父节点下的子菜单。
         *
         * @param parentActionId 父动作 ID；顶层菜单项为空。
         * @return 当前父节点下的菜单动作列表。
         */
        fun buildChildren(parentActionId: String?): List<SystemMenuAction> {
            return byParent[parentActionId]
                .orEmpty()
                .sortedBy { action -> action.sortOrder }
                .map { action ->
                    SystemMenuAction(
                        id = action.actionId,
                        displayName = action.displayName,
                        command = action.command,
                        iconPath = action.iconPath,
                        children = buildChildren(action.actionId),
                    )
                }
        }

        return buildChildren(parentActionId = null)
    }
}
