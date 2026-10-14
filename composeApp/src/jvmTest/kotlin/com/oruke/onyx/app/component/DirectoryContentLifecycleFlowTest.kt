package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.shared.filesystem.DirectoryContentStore
import com.oruke.onyx.vfs.api.DirectoryObservationService
import com.oruke.onyx.vfs.api.DirectoryWatchEvent
import com.oruke.onyx.vfs.api.FileEntryLookup
import com.oruke.onyx.vfs.api.FileRepository
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用真实目录结果验证共享订阅、缓存预算和连接重置，不以 Mock 返回值代替文件状态。 */
class DirectoryContentLifecycleFlowTest {
    /** 同目录多个订阅只注册一份观察，最后释放后再次进入先显示旧快照再核对真实变化。 */
    @Test
    fun sharedSubscriptionsReleaseObservationAndRevalidateOnReturn() = browsingScenario {
        populate(5)
        val signals = BrowsingObservationSignals()
        val directories = DirectoryContentStore(repository, scope, signals)
        val first = directories.subscribe(source.toString())
        val second = directories.subscribe(source.toString())
        withTimeout(5_000) { first.state.first { it.entries?.size == 5 && !it.refreshing } }
        assertEquals(1, signals.starts)
        assertTrue(first.state === second.state)
        first.close()
        assertEquals(0, signals.stops)
        first.close()
        second.close()
        awaitDirectoryCondition { signals.stops == 1 }
        withContext(Dispatchers.IO) { Files.writeString(source.resolve("while-inactive.txt"), "new") }
        val returned = directories.subscribe(source.toString())
        assertEquals(5, assertNotNull(returned.state.value.entries).size)
        withTimeout(5_000) { returned.state.first { it.entries?.size == 6 && !it.refreshing } }
        assertEquals(2, signals.starts)
        assertTrue(returned.state.value.entries.orEmpty().any { it.name == "while-inactive.txt" })
        returned.close()
        awaitDirectoryCondition { signals.stops == 2 }
    }

    /** 连接重置后迟到的旧查询不能覆盖新快照，也不能释放新批次的任务引用。 */
    @Test
    fun connectionResetKeepsNewBatchAndRejectsOldQueryResults() = browsingScenario {
        populate(5)
        val signals = BrowsingObservationSignals()
        val queries = BrowsingQueryRepository(repository)
        val directories = DirectoryContentStore(queries, scope, signals)
        val subscription = directories.subscribe(source.toString())
        withTimeout(5_000) { subscription.state.first { it.entries?.size == 5 && !it.refreshing } }
        val a = source.resolve("item-0000.txt")
        val b = source.resolve("item-0001.txt")
        val c = source.resolve("item-0002.txt")
        val oldQuery = BrowsingReadGate(captureBeforeRelease = true)
        queries.gates[a.toString()] = oldQuery
        signals.send(DirectoryWatchEvent.Changed(a.toString()))
        withTimeout(5_000) { oldQuery.started.await() }
        withContext(Dispatchers.IO) { Files.writeString(a, "new-connection-content") }
        directories.resetConnection(source.toString())
        withTimeout(5_000) { subscription.state.first { state ->
            !state.refreshing && state.entries?.single { it.location == a.toString() }?.sizeBytes == 22L
        } }
        val newQuery = BrowsingReadGate()
        queries.gates[b.toString()] = newQuery
        withContext(Dispatchers.IO) { Files.writeString(b, "new-b-content") }
        signals.send(DirectoryWatchEvent.Changed(b.toString()))
        withTimeout(5_000) { newQuery.started.await() }
        oldQuery.release.complete(Unit)
        delay(30)
        withContext(Dispatchers.IO) { Files.writeString(c, "new-c-content") }
        signals.send(DirectoryWatchEvent.Changed(c.toString()))
        // 让固定合批窗口经过一次，C 必须等待当前新批次，而非被旧 finally 意外并发启动。
        delay(350)
        assertEquals(0, queries.counts[c.toString()]?.get() ?: 0)
        newQuery.release.complete(Unit)
        withTimeout(5_000) { subscription.state.first { state ->
            state.entries?.single { it.location == c.toString() }?.sizeBytes == 13L
        } }
        assertEquals(22L, subscription.state.value.entries.orEmpty().single { it.location == a.toString() }.sizeBytes)
        assertEquals(13L, subscription.state.value.entries.orEmpty().single { it.location == b.toString() }.sizeBytes)
        assertEquals(Files.size(c), subscription.state.value.entries.orEmpty().single { it.location == c.toString() }.sizeBytes)
        subscription.close()
    }

    /** 非活动目录受数量、条目和字节预算约束，活动目录超预算时仍正常显示。 */
    @Test
    fun inactiveCacheHonorsBudgetsWithoutEvictingVisibleDirectories() = browsingScenario {
        populate(5)
        withContext(Dispatchers.IO) {
            repeat(2) { Files.writeString(target.resolve("target-$it.txt"), "sample") }
        }
        val third = withContext(Dispatchers.IO) {
            Files.createDirectory(workspace.resolve("third")).also { directory ->
                repeat(3) { Files.writeString(directory.resolve("third-$it.txt"), "sample") }
            }
        }
        val directories = DirectoryContentStore(repository, scope, maxCachedDirectories = 1, maxCachedEntries = 3)
        val sourceSubscription = directories.subscribe(source.toString())
        withTimeout(5_000) { sourceSubscription.state.first { it.entries?.size == 5 && !it.refreshing } }
        assertNotNull(directories.cached(source.toString()))
        sourceSubscription.close()
        assertNull(directories.cached(source.toString()))
        val targetSubscription = directories.subscribe(target.toString())
        withTimeout(5_000) { targetSubscription.state.first { it.entries?.size == 2 && !it.refreshing } }
        val thirdSubscription = directories.subscribe(third.toString())
        withTimeout(5_000) { thirdSubscription.state.first { it.entries?.size == 3 && !it.refreshing } }
        targetSubscription.close()
        thirdSubscription.close()
        assertNull(directories.cached(target.toString()))
        assertEquals(3, directories.cached(third.toString())?.entries?.size)
        val byteLimited = DirectoryContentStore(repository, scope, maxCachedBytes = 1)
        val visible = byteLimited.subscribe(third.toString())
        withTimeout(5_000) { visible.state.first { it.entries?.size == 3 && !it.refreshing } }
        assertNotNull(byteLimited.cached(third.toString()))
        visible.close()
        assertNull(byteLimited.cached(third.toString()))
    }

    /** 同一主线程批次合并超过 256 项真实改名时，早期条目的选择身份不能丢失。 */
    @Test
    fun burstRenameKeepsEverySelectedIdentity() = browsingScenario {
        val entries = populate(320)
        val selected = setOf(entries.first().id, entries[10].id, entries.last().id)
        primary.dispatch(PaneIntent.SelectEntries(selected))
        val results = withContext(Dispatchers.IO) { entries.map { entry ->
            entry to provider.rename(entry, "renamed-${entry.name}").getOrThrow()
        } }
        results.forEach { (before, after) ->
            store.apply(DirectoryChange(removed = listOf(before), upserted = listOf(after),
                identityChanges = mapOf(before.id to after.id)))
        }
        val expected = results.filter { it.first.id in selected }.mapTo(linkedSetOf()) { it.second.id }
        primary.awaitBrowsing { it.selectedEntryIds == expected }
        assertEquals(expected, primary.state.value.selectedEntryIds)
        val names = Files.newDirectoryStream(source).use { paths -> paths.map { it.fileName.toString() }.toSet() }
        assertEquals(names, (primary.state.value.entriesState as PaneEntriesState.Ready).entries.map { it.name }.toSet())
    }
}

/** 仅控制事件时机的观察通道，所有条目查询和快照仍来自真实文件仓储。 */
private class BrowsingObservationSignals : DirectoryObservationService {
    /** 底层注册次数。 */
    var starts = 0
    /** 底层释放次数。 */
    var stops = 0
    /** 测试驱动的统一事件。 */
    private val events = MutableSharedFlow<DirectoryWatchEvent>(extraBufferCapacity = 64)

    /**
     * 注册可控制的观察流，取消时记录资源释放。
     * @param location 实际被观察的样本目录。
     * @return 包含注册完成与受控变化事件的冷流。
     */
    override fun observe(location: String): Flow<DirectoryWatchEvent> = flow {
        require(location.isNotBlank())
        starts++
        try { emit(DirectoryWatchEvent.Ready); events.collect { emit(it) } } finally { stops++ }
    }

    /**
     * 发送指定时机的目录事件。
     * @param event 与真实文件变更对应的统一事件。
     */
    suspend fun send(event: DirectoryWatchEvent) { events.emit(event) }
}

/**
 * 在真实单条查询外提供一次性延迟，用于模拟取消后才返回的系统调用。
 * @param delegate 生产本地仓储。
 */
private class BrowsingQueryRepository(private val delegate: FileRepository) : FileRepository by delegate {
    /** 下一次路径查询使用的闸门。 */
    val gates = ConcurrentHashMap<String, BrowsingReadGate>()
    /** 每条路径的实际查询次数。 */
    val counts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 读取真实元数据，并允许指定查询在取消后延迟返回。
     * @param location 样本条目位置。
     * @return 实际文件存在性和元数据。
     */
    override suspend fun query(location: String): Result<FileEntryLookup> {
        counts.computeIfAbsent(location) { AtomicInteger() }.incrementAndGet()
        val gate = gates.remove(location)
        val captured = if (gate?.captureBeforeRelease == true) delegate.query(location) else null
        gate?.started?.complete(Unit)
        if (gate != null) withContext(NonCancellable) { withTimeout(10_000) { gate.release.await() } }
        return captured ?: delegate.query(location)
    }
}

/**
 * 等待生命周期可观察结果，超时以失败结束。
 * @param predicate 必须由生产状态满足的条件。
 */
private suspend fun awaitDirectoryCondition(predicate: () -> Boolean) = withTimeout(5_000) {
    while (!predicate()) delay(10)
}
