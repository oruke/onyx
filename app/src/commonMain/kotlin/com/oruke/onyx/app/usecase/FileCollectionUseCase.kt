package com.oruke.onyx.app.usecase

import com.oruke.onyx.core.model.FileCollection
import com.oruke.onyx.core.model.FileCollectionKind
import com.oruke.onyx.core.model.FileCollectionLocation
import com.oruke.onyx.core.model.VFile

/**
 * 文件集合仓库，负责维护可作为虚拟目录打开的集合。
 */
interface FileCollectionRepository {
    /**
     * 保存或替换文件集合。
     *
     * @param collection 待保存的文件集合。
     * @return 保存后的集合定位信息。
     */
    fun save(collection: FileCollection): FileCollectionLocation

    /**
     * 读取文件集合。
     *
     * @param id 集合唯一标识。
     * @return 命中的集合；不存在时返回 `null`。
     */
    fun findById(id: String): FileCollection?

    /**
     * 按虚拟位置读取集合。
     *
     * @param location `collection://` 虚拟目录位置。
     * @return 命中的集合；不存在或格式不合法时返回 `null`。
     */
    fun findByLocation(location: String): FileCollection?
}

/**
 * 内存文件集合仓库，适合运行期搜索结果和临时集合。
 */
class InMemoryFileCollectionRepository : FileCollectionRepository {
    private val collections = mutableMapOf<String, FileCollection>()

    override fun save(collection: FileCollection): FileCollectionLocation {
        collections[collection.id] = collection
        return FileCollectionLocation(
            collectionId = collection.id,
            location = collection.location(),
        )
    }

    override fun findById(id: String): FileCollection? {
        return collections[id]
    }

    override fun findByLocation(location: String): FileCollection? {
        val id = location.removePrefix(COLLECTION_SCHEME).takeIf { value -> value != location }
            ?: return null
        return findById(id.substringBefore('/'))
    }
}

/**
 * 文件集合用例，负责把搜索结果、跨目录临时集合和手工集合统一为虚拟目录。
 *
 * @param repository 文件集合仓库。
 */
class FileCollectionUseCase(
    private val repository: FileCollectionRepository,
) {
    /**
     * 保存搜索结果集合。
     *
     * @param id 集合唯一标识。
     * @param name 集合显示名称。
     * @param entries 搜索结果条目。
     * @return 可打开的集合虚拟位置。
     */
    fun saveSearchResults(
        id: String,
        name: String,
        entries: List<VFile>,
    ): FileCollectionLocation {
        return saveCollection(id, name, FileCollectionKind.SEARCH_RESULT, entries)
    }

    /**
     * 保存跨目录临时集合。
     *
     * @param id 集合唯一标识。
     * @param name 集合显示名称。
     * @param entries 临时集合条目。
     * @return 可打开的集合虚拟位置。
     */
    fun saveTemporaryCollection(
        id: String,
        name: String,
        entries: List<VFile>,
    ): FileCollectionLocation {
        return saveCollection(id, name, FileCollectionKind.TEMPORARY, entries)
    }

    /**
     * 保存手工收藏集合。
     *
     * @param id 集合唯一标识。
     * @param name 集合显示名称。
     * @param entries 手工集合条目。
     * @return 可打开的集合虚拟位置。
     */
    fun saveManualCollection(
        id: String,
        name: String,
        entries: List<VFile>,
    ): FileCollectionLocation {
        return saveCollection(id, name, FileCollectionKind.MANUAL, entries)
    }

    /**
     * 按集合虚拟位置列出条目。
     *
     * @param location `collection://` 虚拟位置。
     * @return 集合条目；集合不存在时返回失败。
     */
    fun list(location: String): Result<List<VFile>> {
        val collection = repository.findByLocation(location)
            ?: return Result.failure(IllegalArgumentException("File collection not found: $location"))
        return Result.success(collection.entries)
    }

    /**
     * 统一保存不同来源的集合。
     *
     * @param id 集合唯一标识。
     * @param name 集合显示名称。
     * @param kind 集合来源类型。
     * @param entries 集合条目。
     * @return 可打开的集合虚拟位置。
     */
    private fun saveCollection(
        id: String,
        name: String,
        kind: FileCollectionKind,
        entries: List<VFile>,
    ): FileCollectionLocation {
        return repository.save(
            FileCollection(
                id = id,
                name = name,
                kind = kind,
                entries = entries.distinctBy { entry -> entry.location },
            )
        )
    }
}

/**
 * 判断位置是否为文件集合虚拟目录。
 *
 * @return `true` 表示位置使用 `collection://` 协议。
 */
fun String.isFileCollectionLocation(): Boolean {
    return startsWith(COLLECTION_SCHEME)
}

/**
 * 将集合转换为虚拟目录位置。
 *
 * @return `collection://` 虚拟位置。
 */
private fun FileCollection.location(): String {
    return "$COLLECTION_SCHEME$id"
}

private const val COLLECTION_SCHEME = "collection://"
