package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.VFile

/**
 * 判断条目通知是否缺少改名的另一半，避免先发布删除或重复对象后再丢失旧新路径映射。
 * @param previous 上一次完整目录快照。
 * @return 存在未配对移除或同一对象的新入口时返回 true，由完整读取确定改名、删除或硬链接。
 */
internal fun DirectoryChange.requiresDirectoryIdentityVerification(previous: List<VFile>): Boolean {
    val incomingIdentities = upserted.mapNotNullTo(hashSetOf()) { it.identityKey }
    if (removed.any { it.identityKey != null && it.identityKey !in incomingIdentities }) return true
    val removedIds = removed.mapTo(hashSetOf()) { it.id }
    val oldObjects = previous.filter { it.identityKey != null }.groupBy { it.identityKey }
    return upserted.any { incoming ->
        oldObjects[incoming.identityKey].orEmpty().any { old -> old.id != incoming.id && old.id !in removedIds }
    }
}
