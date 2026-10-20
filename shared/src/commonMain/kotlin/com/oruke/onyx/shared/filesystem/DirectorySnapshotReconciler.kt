package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.DirectoryVerificationResult

/**
 * 比较真实目录快照，保留未变化的列表实例，并以 Provider 对象身份识别改名。
 * @param previous 上一次成功快照，首次读取时为空。
 * @param incoming 本次读取的完整目录内容。
 * @return 可直接发布的内容、预算和变化；只改变枚举顺序不会产生新内容版本。
 */
internal fun reconcileDirectorySnapshot(previous: List<VFile>?, incoming: List<VFile>): DirectoryContentSnapshot {
    if (previous == null) return DirectoryContentSnapshot(incoming, incoming.sumOf { it.estimatedMetadataBytes() })
    val oldById = previous.associateBy { it.id }
    val newIds = incoming.mapTo(hashSetOf()) { it.id }
    val removed = previous.filter { it.id !in newIds }
    val upserted = incoming.filter { oldById[it.id] != it }
    val change = DirectoryChange(removed = removed, upserted = upserted,
        identityChanges = detectDirectoryRenames(previous, incoming))
    val mutation = previous.applyDirectoryMutation(previous.firstOrNull()?.parentLocation
        ?: incoming.firstOrNull()?.parentLocation.orEmpty(), change)
    val entries = mutation?.entries ?: previous
    return DirectoryContentSnapshot(entries, entries.sumOf { it.estimatedMetadataBytes() },
        change = change.takeIf { mutation != null }, changed = mutation != null)
}

/**
 * 为条目级通知补充确定的改名映射，不使用名称、大小或修改时间猜测同一文件。
 * @param previous 查询开始前的完整快照。
 * @param change 查询到的新增、修改和删除。
 * @return 包含无歧义对象身份映射的变化。
 */
internal fun detectDirectoryChangeIdentities(previous: List<VFile>, change: DirectoryChange): DirectoryChange {
    val parent = previous.firstOrNull()?.parentLocation ?: change.upserted.firstOrNull()?.parentLocation.orEmpty()
    val next = previous.applyDirectoryMutation(parent, change)?.entries ?: previous
    return change.copy(identityChanges = detectDirectoryRenames(previous, next))
}

/**
 * 识别同一父目录下唯一对象的旧新路径，硬链接和缺少稳定身份的 Provider 均不做推断。
 * @param previous 变化前的完整快照。
 * @param current 变化后的完整快照。
 * @return 旧条目 ID 到新条目 ID 的映射。
 */
private fun detectDirectoryRenames(previous: List<VFile>, current: List<VFile>): Map<String, String> {
    val oldObjects = previous.filter { it.identityKey != null }.groupBy { it.identityKey }
    val newObjects = current.filter { it.identityKey != null }.groupBy { it.identityKey }
    return buildMap {
        oldObjects.forEach { (key, oldMatches) ->
            val old = oldMatches.singleOrNull() ?: return@forEach
            val new = newObjects[key]?.singleOrNull() ?: return@forEach
            // 原路径可能已被另一条目占用或参与交换名称，唯一对象身份仍能准确追踪。
            if (old.id == new.id) return@forEach
            if (old.parentLocation == new.parentLocation && old.kind == new.kind) put(old.id, new.id)
        }
    }
}

/**
 * 合并目录读取结果与身份日志；没有真实变化时保留原版本和变化日志。
 * @param snapshot 已在后台完成差异计算的真实快照。
 * @param nextRevision 内容变化时分配的新版本。
 * @return 最新内容与同步状态；无变化的静默校验会得到相等的状态。
 */
internal fun DirectoryContentState.withDirectorySnapshot(
    snapshot: DirectoryContentSnapshot,
    nextRevision: Long,
): DirectoryContentState {
    val revision = if (snapshot.changed) nextRevision else this.revision
    val log = changeLog.appendIdentityChanges(revision, snapshot.change?.identityChanges.orEmpty())
    return copy(entries = snapshot.entries, refreshing = false, failure = null, revision = revision,
        change = if (snapshot.changed) snapshot.change else change, changeLog = log)
}

/**
 * 将真实目录读取结果转换为观察反馈，读取失败不能当作内容没有变化。
 * @param elapsedMillis 本次读取和差异计算的实际耗时。
 * @return 供平台轮询预算使用的结构化结果。
 */
internal fun Result<DirectoryContentSnapshot>.toDirectoryVerificationResult(
    elapsedMillis: Long,
): DirectoryVerificationResult = DirectoryVerificationResult(
    changed = getOrNull()?.changed == true, succeeded = isSuccess, elapsedMillis = elapsedMillis,
)
