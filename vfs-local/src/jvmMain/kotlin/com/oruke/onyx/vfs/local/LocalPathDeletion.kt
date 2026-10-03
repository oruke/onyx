package com.oruke.onyx.vfs.local

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 按后序删除普通目录树，并将符号链接及 Windows Junction 作为不可递归的入口处理。 */
internal object LocalPathDeletion {
    /**
     * 删除指定入口；普通目录删除其子项，联接只删除入口本身。
     *
     * @param path 用户选择或失败传输需要回收的确切路径。
     */
    fun delete(path: Path) {
        val pending = ArrayDeque<PendingDeletion>()
        pending.addLast(PendingDeletion(path, childrenVisited = false))
        while (pending.isNotEmpty()) {
            deleteEntry(pending.removeLast(), pending)
        }
    }

    /**
     * 删除当前叶子或安排普通目录的子项，再回到目录自身执行删除。
     *
     * @param current 当前入口及后序状态。
     * @param pending 当前删除操作尚未处理的入口栈。
     */
    private fun deleteEntry(current: PendingDeletion, pending: ArrayDeque<PendingDeletion>) {
        if (current.childrenVisited) {
            Files.deleteIfExists(current.path)
            return
        }
        val attributes = current.path.readDeletionAttributes() ?: return
        val isDirectoryLink = WindowsReparsePointAccess.isDirectoryLink(current.path, attributes)
        if (!attributes.isDirectory || attributes.isSymbolicLink || isDirectoryLink) {
            Files.deleteIfExists(current.path)
        } else {
            // 必须先识别入口再打开目录；Files.walk 默认仍会遍历 Windows Junction。
            pending.addLast(PendingDeletion(current.path, childrenVisited = true))
            Files.newDirectoryStream(current.path).use { children ->
                children.forEach { child -> pending.addLast(PendingDeletion(child, childrenVisited = false)) }
            }
        }
    }
}

/** 记录删除遍历中的入口以及是否已经调度过其子项。 */
private data class PendingDeletion(
    /** 待删除入口，始终保留目录枚举时的路径而不解析联接目标。 */
    val path: Path,
    /** 为 true 时只删除目录自身，不再遍历子项。 */
    val childrenVisited: Boolean,
)

/**
 * 读取待删除入口自身的属性，只有入口确实消失时才按幂等删除处理。
 *
 * @return 入口属性；不存在时返回 null，其他读取失败原样传播。
 */
private fun Path.readDeletionAttributes(): BasicFileAttributes? {
    return try {
        Files.readAttributes(this, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }
}
