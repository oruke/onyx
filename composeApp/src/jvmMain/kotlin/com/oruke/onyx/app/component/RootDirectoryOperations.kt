package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.shared.filesystem.DirectoryContentStore
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** 应用级共享目录内容；独立根组件测试使用根生命周期的隔离存储。 */
internal val DefaultRootComponent.rootDirectoryStore: DirectoryContentStore
    get() = dependencies.delegates.directoryStore ?: isolatedDirectoryStore

/**
 * 应用文件结果并校验不能准确描述变化的目录。
 * @param change 统一文件变化结果。
 */
internal fun DefaultRootComponent.applyDirectoryChange(change: DirectoryChange) {
    rootDirectoryStore.apply(change)
}

/**
 * 按明确的目录范围校验内容，不改变任何标签浏览位置。
 * @param locations 操作实际触及的目录。
 */
internal fun DefaultRootComponent.refreshDirectories(locations: Set<String>) {
    rootDirectoryStore.invalidate(locations.map(pathService::normalizeLocation).toSet())
}

/**
 * 从本窗口的匹配面板捕获移除上下文，其他订阅者只接收被动内容变化。
 * @param entries 已确定的命令目标。
 * @return 发起访问上下文，剪贴板源不在可见选择中时为空。
 */
internal fun DefaultRootComponent.beginRemoval(entries: List<VFile>): PaneRemovalContext? {
    return paneComponent(activePane.value).captureRemoval(entries)
        ?: listOf(primaryPane, secondaryPane).firstNotNullOfOrNull { it.captureRemoval(entries) }
}

/**
 * 等待不确定目录校验后完成一次选择续接，失败快照不能证明条目已经移除。
 * @param context 发起上下文。
 * @param confirmedIds 命令逐项返回的确认移除身份。
 * @param verify 是否需要根据最终目录确认额外消失的目标。
 */
internal fun DefaultRootComponent.finishRemoval(
    context: PaneRemovalContext?,
    confirmedIds: Set<String>,
    verify: Boolean,
) {
    if (context == null) return
    scope.launch {
        val removed = confirmedIds.toMutableSet()
        if (verify) {
            val content = rootDirectoryStore.settled(context.location)
            val entries = content.entries
            if (content.failure == null && entries != null) {
                removed += context.targetIds - entries.mapTo(hashSetOf()) { it.id }
            }
        }
        yield()
        paneComponent(context.paneId).completeRemoval(context, removed)
    }
}
