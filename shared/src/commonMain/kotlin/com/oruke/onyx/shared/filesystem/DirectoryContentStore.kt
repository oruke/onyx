package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.DirectoryRevisionChange
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.DirectoryObservationService
import com.oruke.onyx.vfs.api.DirectoryWatchEvent
import com.oruke.onyx.vfs.api.FileEntryLookup
import com.oruke.onyx.vfs.api.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 非活动目录快照的默认估算内存预算，单位字节。 */
private const val DEFAULT_CACHED_BYTES = 67_108_864L

/** 一个目录的共享内容和同步状态，不携带面板的选择、排序与滚动。 */
data class DirectoryContentState(
    /** 最近一次成功快照；null 表示尚未读取，空列表表示成功读取空目录。 */
    val entries: List<VFile>? = null,
    /** 是否正在后台读取或校验。 */
    val refreshing: Boolean = false,
    /** 最近读取失败原因，保留旧快照供用户恢复操作。 */
    val failure: Throwable? = null,
    /** 监听不可用原因，与目录读取失败分开表达。 */
    val observationFailure: Throwable? = null,
    /** 内容版本，后台状态变化不递增此版本。 */
    val revision: Long = 0,
    /** 当前内容版本包含的已确认身份变化。 */
    val change: DirectoryChange? = null,
    /** 有界身份变化日志，订阅者按自身内容版本重放。 */
    val changeLog: List<DirectoryRevisionChange> = emptyList(),
)

/**
 * 目录订阅句柄，最后一个订阅释放时关闭底层观察资源。
 * @param state 共享目录状态流。
 * @param release 释放引用的动作，必须在所属作用域线程执行。
 */
class DirectorySubscription(
    val state: StateFlow<DirectoryContentState>,
    private val release: () -> Unit,
) {
    /** 句柄是否已经释放。 */
    private var closed = false

    /** 释放本次订阅；重复关闭不重复减少引用。 */
    fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}

/**
 * 应用级目录内容存储；状态入口在所属主线程调用，I/O 在后台执行。
 *
 * 已知变化局部应用，未知变化重新校验。列表读取期间发生的修改使旧读取失效，
 * 防止慢请求把刚完成的删除或重命名覆盖回去。
 */
class DirectoryContentStore(
    /** 底层统一文件查询仓库。 */
    private val repository: FileRepository,
    /** 应用级主线程作用域，多个窗口共用同一实例。 */
    private val scope: CoroutineScope,
    /** 可选平台观察服务，测试和无观察平台可以只做按需校验。 */
    private val observationService: DirectoryObservationService? = null,
    /** 非活动目录的数量上限。 */
    private val maxCachedDirectories: Int = 32,
    /** 非活动快照的条目数量上限。 */
    private val maxCachedEntries: Int = 100_000,
    /** 非活动快照估算内存上限，包含元数据字符串和对象固定开销。 */
    private val maxCachedBytes: Long = DEFAULT_CACHED_BYTES,
) {
    /** 按最近使用顺序保存的目录记录，活动记录不参与淘汰。 */
    private val records = linkedMapOf<String, DirectoryRecord>()
    /** 全存储递增版本，缓存淘汰重建也不会把旧访问版本误当成新版本。 */
    private var nextRevision = 1L

    /**
     * 订阅规范化目录，同一目录共享读取和观察。
     * @param location 已规范化并包含连接身份的 VFS 目录位置。
     * @return 必须在离开目录时关闭的订阅。
     */
    fun subscribe(location: String): DirectorySubscription {
        val record = record(location)
        record.references++
        if (record.references == 1) {
            startObservation(location, record)
        }
        return DirectorySubscription(record.state) { release(location, record) }
    }

    /**
     * 用户主动刷新时校验内容，同时重新注册已经降级的观察，避免重试后仍等待轮询周期。
     * @param location 当前活动目录的规范化位置。
     */
    fun refresh(location: String) {
        val record = record(location)
        if (record.references > 0 && record.state.value.observationFailure != null) {
            record.observation?.cancel()
            startObservation(location, record)
        }
        load(location, force = true)
    }

    /**
     * 为首个订阅或显式重试建立观察；没有平台观察服务时执行按需读取。
     * @param location 规范化目录位置。
     * @param record 当前目录记录。
     */
    private fun startObservation(location: String, record: DirectoryRecord) {
        val observer = observationService
        if (observer == null) load(location) else {
            record.observation = scope.launch {
                observer.observe(location).collect { event -> acceptWatchEvent(location, record, event) }
            }
        }
    }

    /**
     * 获取可用内存快照，不执行 I/O，也不创建新的缓存记录。
     * @param location 规范化目录位置。
     * @return 已有状态；缓存已淘汰时为 null。
     */
    fun cached(location: String): DirectoryContentState? = records[location]?.state?.value

    /**
     * 等待指定目录的本次校验结束，失败保留旧内容但不会被误当成成功结果。
     * @param location 已规范化目录。
     * @return 已完成读取的共享内容状态。
     */
    suspend fun settled(location: String): DirectoryContentState {
        val record = record(location)
        if (record.dirty) load(location, force = true)
        return record.state.first { !it.refreshing }
    }

    /**
     * 连接身份改变时清除同协议缓存并取消旧请求，旧账号的数据不能继续作为新连接快照。
     * @param location 已发生凭据、连接配置或显式重连变化的位置。
     */
    fun resetConnection(location: String) {
        val prefix = location.takeIf { it.contains("://") }?.substringBefore("://")?.plus("://")
        records.filterKeys { if (prefix == null) it == location else it.startsWith(prefix) }.forEach { (path, record) ->
            record.mutationRevision++
            record.loading?.cancel()
            record.loading = null
            record.batch?.cancel()
            record.batch = null
            record.pendingLocations.clear()
            record.dirty = true
            record.estimatedBytes = 0
            record.identityBytes = 0
            record.state.value = DirectoryContentState(revision = nextRevision++)
            if (record.references > 0) load(path, force = true)
        }
    }

    /**
     * 请求底层目录读取；并发请求共享当前读取。
     * @param location 规范化目录位置。
     * @param force 是否忽略已有快照，F5 使用 true。
     */
    @Suppress("TooGenericExceptionCaught") // 仓储边界既接受 Result 失败，也将意外抛出的异常转为可见状态。
    fun load(location: String, force: Boolean = false) {
        val record = record(location)
        if (record.loading?.isActive == true) {
            if (force) { record.dirty = true; record.mutationRevision++ }
            return
        }
        if (!force && !record.dirty && record.state.value.entries != null) return
        val startRevision = record.mutationRevision
        record.dirty = false
        record.state.value = record.state.value.copy(refreshing = true, failure = null)
        record.loading = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) { repository.list(location).map { entries ->
                    DirectoryContentSnapshot(entries, entries.sumOf { it.estimatedMetadataBytes() })
                } }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (record.mutationRevision == startRevision) {
                    record.state.value = result.fold(
                        onSuccess = { snapshot ->
                            record.estimatedBytes = snapshot.estimatedBytes
                            record.state.value.copy(entries = snapshot.entries, refreshing = false, failure = null,
                                revision = nextRevision++, change = null)
                        },
                        onFailure = { failure -> record.state.value.copy(refreshing = false, failure = failure) },
                    )
                } else {
                    record.dirty = true
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                record.state.value = record.state.value.copy(refreshing = false, failure = failure)
            } finally {
                // 旧任务只清除自己的引用，不能移除后续导航启动的新请求。
                if (record.loading === currentCoroutineContext()[Job]) {
                    record.loading = null
                    if (record.dirty && record.references > 0) load(location, force = true)
                    else if (record.state.value.refreshing) {
                        record.state.value = record.state.value.copy(refreshing = false)
                    }
                }
                trimCache()
            }
        }
        record.loading?.start()
    }

    /**
     * 标记操作触及的目录失效，后台标签在再次激活时读取新内容。
     * @param locations 需要校验的规范化目录集合。
     */
    fun invalidate(locations: Set<String>) {
        locations.forEach { location ->
            records[location]?.let { record ->
                record.mutationRevision++
                record.dirty = true
                if (record.references > 0) load(location, force = true)
            }
        }
    }

    /**
     * 应用已经提交的文件变化，不从任务成功状态猜测真实变更。
     * @param change 已确认增删、身份映射及不确定目录。
     */
    fun apply(change: DirectoryChange) {
        val parents = (change.removed.mapNotNull { it.parentLocation } +
            change.upserted.mapNotNull { it.parentLocation }).toSet()
        parents.forEach { parent ->
            val record = records[parent] ?: return@forEach
            val previous = record.state.value.entries
            if (previous == null) {
                record.mutationRevision++
                record.dirty = true
                return@forEach
            }
            val mutation = previous.applyDirectoryMutation(parent, change) ?: return@forEach
            record.mutationRevision++
            record.estimatedBytes += mutation.estimatedByteDelta
            val revision = nextRevision++
            val log = record.state.value.changeLog.appendIdentityChanges(revision, change.identityChanges)
            record.identityBytes = log.estimatedIdentityBytes()
            record.state.value = record.state.value.copy(entries = mutation.entries,
                refreshing = record.loading?.isActive == true, failure = null,
                revision = revision, change = change, changeLog = log)
            // 首次读取尚未得到完整快照时不能用局部结果替代全目录。
            if (record.loading?.isActive == true) record.dirty = true
        }
        // 删除或重命名目录使其后代缓存失效，重新进入不能复用旧目录树。
        val removedDirectories = change.removed.filter {
            it.kind == com.oruke.onyx.core.model.VFileKind.DIRECTORY
        }
        invalidate(records.keys.filter { candidate -> removedDirectories.any { entry ->
            candidate == entry.location || candidate.startsWith(entry.location.trimEnd('/', '\\') + "/") ||
                candidate.startsWith(entry.location.trimEnd('/', '\\') + "\\")
        } }.toSet())
        invalidate(change.invalidatedDirectories)
        trimCache()
    }

    /**
     * 处理统一观察事件，并按固定窗口合并条目查询，持续写入不会饿死更新。
     * @param location 被观察目录。
     * @param record 当前目录记录。
     * @param event 平台观察事件。
     */
    private fun acceptWatchEvent(location: String, record: DirectoryRecord, event: DirectoryWatchEvent) {
        when (event) {
            DirectoryWatchEvent.Ready -> {
                record.state.value = record.state.value.copy(observationFailure = null)
                load(location, force = true)
            }
            DirectoryWatchEvent.Invalidated -> invalidate(setOf(location))
            is DirectoryWatchEvent.Unavailable -> {
                record.state.value = record.state.value.copy(observationFailure = event.failure)
                load(location, force = true)
            }
            is DirectoryWatchEvent.Changed -> {
                record.pendingLocations += event.location
                if (record.pendingLocations.size > MAX_EVENT_PATHS) {
                    record.pendingLocations.clear()
                    invalidate(setOf(location))
                } else if (record.batch?.isActive != true) {
                    record.batch = scope.launch(start = CoroutineStart.LAZY) {
                        delay(EVENT_BATCH_MILLIS)
                        val locations = record.pendingLocations.toList()
                        record.pendingLocations.clear()
                        try {
                            reconcileEntries(location, record, locations)
                        } finally {
                            // 连接重置或重新订阅可能已创建新批次，旧任务只能释放自己的引用。
                            if (record.batch === currentCoroutineContext()[Job]) {
                                record.batch = null
                                if (record.references > 0 && record.pendingLocations.isNotEmpty()) {
                                    acceptWatchEvent(location, record,
                                        DirectoryWatchEvent.Changed(record.pendingLocations.first()))
                                }
                            }
                        }
                    }
                    record.batch?.start()
                }
            }
        }
    }

    /**
     * 查询批次中条目的最终状态，读取错误与不支持均改为目录校验。
     * @param location 父目录位置。
     * @param record 目录记录。
     * @param locations 合并后的条目位置。
     */
    @Suppress("TooGenericExceptionCaught") // 观察查询边界保留取消，意外失败转为目录校验。
    private suspend fun reconcileEntries(location: String, record: DirectoryRecord, locations: List<String>) {
        val revision = record.mutationRevision
        val results = try {
            withContext(Dispatchers.IO) { locations.map { it to repository.query(it) } }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            record.state.value = record.state.value.copy(observationFailure = failure)
            invalidate(setOf(location))
            return
        }
        if (revision != record.mutationRevision) {
            record.pendingLocations.addAll(locations)
            return
        }
        val removed = mutableListOf<VFile>()
        val upserted = mutableListOf<VFile>()
        var invalid = false
        for ((path, result) in results) {
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            when (val lookup = result.getOrNull()) {
                is FileEntryLookup.Found -> upserted += lookup.entry
                FileEntryLookup.Missing -> record.state.value.entries?.firstOrNull { it.location == path }
                    ?.let(removed::add)
                else -> invalid = true
            }
        }
        if (invalid) invalidate(setOf(location)) else apply(DirectoryChange(removed = removed, upserted = upserted))
        // 查询期间到达的事件另起一批，不能因上一批尚未结束而遗留在集合中。
    }

    /**
     * 查找并更新目录的最近使用顺序。
     * @param location 规范化目录位置。
     * @return 当前目录记录。
     */
    private fun record(location: String): DirectoryRecord {
        val record = records.remove(location) ?: DirectoryRecord()
        records[location] = record
        return record
    }

    /**
     * 释放订阅，保留有界快照供历史导航立即恢复。
     * @param location 目录位置。
     * @param record 订阅对应的记录。
     */
    private fun release(location: String, record: DirectoryRecord) {
        record.references--
        if (record.references == 0) {
            // 释放观察期间无法知道外部变化，重新激活先显示缓存，再注册监听并校验。
            record.dirty = true
            record.observation?.cancel()
            record.observation = null
            record.batch?.cancel()
            record.batch = null
            if (record.pendingLocations.isNotEmpty()) record.dirty = true
            record.pendingLocations.clear()
        }
        if (records[location] === record) trimCache()
    }

    /** 按非活动目录数量与条目总数淘汰快照，不影响可见目录。 */
    private fun trimCache() {
        val inactive = records.filterValues { it.references == 0 }
        var count = inactive.size
        var entries = inactive.values.sumOf { it.state.value.entries?.size ?: 0 }
        var bytes = inactive.values.sumOf { it.estimatedBytes + it.identityBytes }
        for ((location, record) in inactive) {
            if (count <= maxCachedDirectories && entries <= maxCachedEntries && bytes <= maxCachedBytes) break
            records.remove(location)
            record.loading?.cancel()
            entries -= record.state.value.entries?.size ?: 0
            bytes -= record.estimatedBytes + record.identityBytes
            count--
        }
    }

    /** 一个目录的可变任务和引用状态，只在存储所属线程访问。 */
    private class DirectoryRecord {
        /** 对外内容状态。 */
        val state = MutableStateFlow(DirectoryContentState())
        /** 可见订阅数量。 */
        var references = 0
        /** 是否必须重新校验。 */
        var dirty = true
        /** 文件变化代次。 */
        var mutationRevision = 0L
        /** 当前快照的估算内存占用。 */
        var estimatedBytes = 0L
        /** 身份变化日志的估算内存占用，同样计入非活动缓存预算。 */
        var identityBytes = 0L
        /** 当前目录列举任务。 */
        var loading: Job? = null
        /** 当前底层观察任务。 */
        var observation: Job? = null
        /** 当前批次查询任务。 */
        var batch: Job? = null
        /** 合并窗口内待查询的条目。 */
        val pendingLocations = linkedSetOf<String>()
    }

    private companion object {
        /** 外部变化合并窗口。 */
        const val EVENT_BATCH_MILLIS = 250L
        /** 单批最多查询的条目，超过后改为一次目录校验。 */
        const val MAX_EVENT_PATHS = 256
    }
}
