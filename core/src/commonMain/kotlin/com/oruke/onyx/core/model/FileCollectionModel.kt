package com.oruke.onyx.core.model

/**
 * 虚拟文件集合来源。
 */
enum class FileCollectionKind {
    SEARCH_RESULT,
    TEMPORARY,
    MANUAL,
}

/**
 * 可作为虚拟目录打开的文件集合。
 *
 * @property id 集合唯一标识。
 * @property name 集合显示名称。
 * @property kind 集合来源类型。
 * @property entries 集合包含的文件条目。
 */
data class FileCollection(
    val id: String,
    val name: String,
    val kind: FileCollectionKind,
    val entries: List<VFile>,
)

/**
 * 虚拟文件集合的定位信息。
 *
 * @property collectionId 集合唯一标识。
 * @property location 可传递给面板的虚拟位置。
 */
data class FileCollectionLocation(
    val collectionId: String,
    val location: String,
)
