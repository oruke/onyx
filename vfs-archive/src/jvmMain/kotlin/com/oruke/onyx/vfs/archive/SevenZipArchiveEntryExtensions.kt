package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID
import java.util.Date

/**
 * 从 7-Zip 归档条目中构建当前目录的直接子节点。
 *
 * @param index 归档条目索引。
 * @param archivePath 归档物理路径。
 * @param prefix 当前归档目录前缀。
 * @param parentLocation 当前归档目录位置。
 * @return 子节点名称与文件对象；非直接子节点返回 `null`。
 */
internal fun IInArchive.directChildEntry(
    index: Int,
    archivePath: String,
    prefix: String,
    parentLocation: String,
): Pair<String, VFile>? {
    val relativePath = itemPath(index)
        ?.takeIf { path -> path.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { path -> path.isNotEmpty() }
    val segments = relativePath
        ?.split("/")
        ?.filter { segment -> segment.isNotEmpty() }
        ?.takeIf { pathSegments -> pathSegments.isNotEmpty() }
    return segments?.let { pathSegments ->
        val directChildName = pathSegments.first()
        val entryIsDirectory = getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
        val childIsDirectory = pathSegments.size > 1 || relativePath.endsWith("/") || entryIsDirectory
        val childEntryPath = prefix + directChildName
        val childLocation = ArchiveService.archiveLocation(archivePath, childEntryPath)
        directChildName to VFile(
            id = childLocation,
            name = directChildName,
            location = childLocation,
            parentLocation = parentLocation,
            kind = if (childIsDirectory) VFileKind.DIRECTORY else VFileKind.FILE,
            sizeBytes = if (childIsDirectory) null else (getProperty(index, PropID.SIZE) as? Long) ?: 0L,
            modifiedAtEpochMillis = (getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date)?.time,
            hidden = false,
            capabilities = if (childIsDirectory) {
                setOf(VFileCapability.LIST_CHILDREN, VFileCapability.READ_METADATA)
            } else {
                setOf(VFileCapability.READ_CONTENT, VFileCapability.READ_METADATA)
            },
        )
    }
}

/**
 * 读取归档条目路径，并统一为正斜杠分隔形式。
 *
 * @param index 归档条目索引。
 * @return 规范化路径；条目没有路径时返回 `null`。
 */
internal fun IInArchive.itemPath(index: Int): String? {
    return (getProperty(index, PropID.PATH) as? String)?.replace('\\', '/')
}
