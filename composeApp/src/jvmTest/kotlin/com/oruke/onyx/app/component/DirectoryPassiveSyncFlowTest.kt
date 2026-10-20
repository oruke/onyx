package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.DirectoryPollingPolicy
import com.oruke.onyx.app.filesystem.JvmDirectoryObservationService
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.shared.filesystem.DirectoryContentStore
import com.oruke.onyx.vfs.api.FileRepository
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 通过真实本地目录和生产组件验证后台同步无抖动、外部改名与交互保护。 */
class DirectoryPassiveSyncFlowTest {
    /** 无变化且枚举顺序相反的后台校验也必须复用快照，不发布加载状态或新版本。 */
    @Test
    fun unchangedVerificationKeepsSnapshotAndRevision() = browsingScenario {
        populate(1_000)
        var reverse = false
        val ordered = object : FileRepository by repository {
            /**
             * 返回真实目录内容，仅改变枚举顺序来模拟不同文件系统的列举行为。
             * @param location 真实目录位置。
             * @return 完整真实快照。
             */
            override suspend fun list(location: String) = repository.list(location).map {
                if (reverse) it.reversed() else it
            }
        }
        val directories = DirectoryContentStore(ordered, scope)
        val subscription = directories.subscribe(source.toString())
        val before = withTimeout(5_000) { subscription.state.first { it.entries?.size == 1_000 && !it.refreshing } }
        reverse = true
        val gate = BrowsingReadGate()
        repository.gates[source.toString()] = gate
        directories.load(source.toString(), force = true, background = true)
        gate.started.await()
        assertSame(before, subscription.state.value)
        gate.release.complete(Unit)
        val after = directories.settled(source.toString())
        assertSame(before.entries, after.entries)
        assertSame(before, after)
        assertEquals(before.revision, after.revision)
        println("PASSIVE_SYNC: 1000 项目录无变化校验，新增内容版本=0，快照替换=0")
        subscription.close()
    }

    /** 外部真实改名保留编辑草稿、选择和焦点，并能从原编辑入口继续完成最终改名。 */
    @Test
    fun externalRenameKeepsSelectionFocusAndDraft() = browsingScenario(observe = true) {
        val entries = populate()
        val original = entries[40]
        assertNotNull(original.identityKey, "当前真实文件系统必须提供可验证的对象身份")
        primary.dispatch(PaneIntent.SelectEntry(original.id))
        primary.dispatch(PaneIntent.BeginRename)
        primary.dispatch(PaneIntent.UpdateInlineEditDraft("final-name.txt"))
        withContext(Dispatchers.IO) { Files.move(source.resolve(original.name), source.resolve("external-name.txt")) }
        val renamed = primary.awaitBrowsing { state ->
            val edit = state.inlineEditState
            edit != null && state.selectionFocusId == edit.targetEntryId &&
                (state.entriesState as? PaneEntriesState.Ready)?.entries?.any { it.name == "external-name.txt" } == true
        }
        val entry = (renamed.entriesState as PaneEntriesState.Ready).entries.single { it.name == "external-name.txt" }
        assertEquals(setOf(entry.id), renamed.selectedEntryIds)
        assertEquals(entry.id, renamed.inlineEditState?.targetEntryId)
        assertEquals("final-name.txt", renamed.inlineEditState?.draftName)
        primary.dispatch(PaneIntent.ConfirmInlineEdit)
        primary.awaitBrowsing { state -> state.inlineEditState == null &&
            (state.entriesState as? PaneEntriesState.Ready)?.entries?.any { it.name == "final-name.txt" } == true }
        assertEquals("sample-40", withContext(Dispatchers.IO) { Files.readString(source.resolve("final-name.txt")) })
        assertFalse(Files.exists(source.resolve(original.name)))
        assertFalse(Files.exists(source.resolve("external-name.txt")))
    }

    /** 鼠标、滚动和编辑期间只更新数据，交互结束后才让新增项进入配置排序位置。 */
    @Test
    fun passiveUpdatesDeferOrderingUntilInteractionEnds() = browsingScenario(observe = true) {
        val selected = populate()[30]
        primary.dispatch(PaneIntent.SelectEntry(selected.id))
        val browser = primary.state.value.activeTabState.browserState
        val tabId = primary.state.value.activeTabId
        primary.dispatch(PaneIntent.ListPointerInteraction(tabId, browser.visitId, true))
        withContext(Dispatchers.IO) { Files.writeString(source.resolve("aaa-pointer.txt"), "pointer") }
        val pressed = primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 81 }
        assertEquals("aaa-pointer.txt", (pressed.entriesState as PaneEntriesState.Ready).entries.last().name)
        assertTrue(pressed.activeTabState.browserState.directoryOrderDeferred)
        primary.dispatch(PaneIntent.ListPointerInteraction(tabId, browser.visitId, false))
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.firstOrNull()?.name == "aaa-pointer.txt" }

        val anchor = PaneViewportAnchor(selected.id, 30, 3f)
        primary.dispatch(PaneIntent.ViewportChanged(tabId, browser.visitId, ViewMode.DETAILS, anchor, true))
        withContext(Dispatchers.IO) { Files.writeString(source.resolve("000-scroll.txt"), "scroll") }
        val scrolling = primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 82 }
        assertEquals("000-scroll.txt", (scrolling.entriesState as PaneEntriesState.Ready).entries.last().name)
        primary.dispatch(PaneIntent.ViewportChanged(tabId, browser.visitId, ViewMode.DETAILS, anchor, false))
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.firstOrNull()?.name == "000-scroll.txt" }

        primary.dispatch(PaneIntent.BeginRename)
        withContext(Dispatchers.IO) { Files.writeString(source.resolve("000-edit.txt"), "edit") }
        val editing = primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 83 }
        assertEquals("000-edit.txt", (editing.entriesState as PaneEntriesState.Ready).entries.last().name)
        primary.dispatch(PaneIntent.CancelInlineEdit)
        val idle = primary.awaitBrowsing {
            (it.entriesState as? PaneEntriesState.Ready)?.entries?.firstOrNull()?.name == "000-edit.txt"
        }
        assertEquals(setOf(selected.id), idle.selectedEntryIds)
        assertFalse(idle.activeTabState.browserState.directoryOrderDeferred)
        println("PASSIVE_SYNC: 指针、滚动、编辑三个真实文件更新场景均在交互结束后恢复排序")
    }

    /** 用无系统通知的观察分支校验真实文件变化，慢读取期间不并发扫描也不闪加载态。 */
    @Test
    fun pollingFindsChangesWithoutPublishingUnchangedSnapshots() = browsingScenario {
        populate(5)
        val logical = "test://passive-directory"
        val remote = object : FileRepository by repository {
            /**
             * 使用真实本地目录模拟仅能轮询的协议位置，数据读取仍经过生产 Provider。
             * @param location 测试观察位置。
             * @return 真实目录的完整元数据。
             */
            override suspend fun list(location: String) = repository.list(source.toString())
        }
        val observer = JvmDirectoryObservationService(paths, DirectoryPollingPolicy(50, 100, 200))
        val directories = DirectoryContentStore(remote, scope, observer)
        val subscription = directories.subscribe(logical)
        val before = withTimeout(5_000) { subscription.state.first { it.entries?.size == 5 && !it.refreshing } }
        val reads = repository.reads.getValue(source.toString())
        val gate = BrowsingReadGate()
        repository.gates[source.toString()] = gate
        withTimeout(5_000) { gate.started.await() }
        val pendingReads = reads.get()
        delay(300)
        assertEquals(pendingReads, reads.get(), "读取尚未结束时不能再发起轮询读取")
        assertSame(before, subscription.state.value)
        withContext(Dispatchers.IO) { Files.writeString(source.resolve("polled.txt"), "polled-content") }
        gate.release.complete(Unit)
        val after = withTimeout(5_000) { subscription.state.first { it.entries?.size == 6 } }
        assertEquals("polled-content", Files.readString(source.resolve("polled.txt")))
        assertTrue(after.revision > before.revision)
        assertFalse(after.refreshing)
        val observedReads = reads.get()
        withTimeout(5_000) { while (reads.get() <= observedReads + 1) delay(20) }
        directories.settled(logical)
        assertSame(after.entries, subscription.state.value.entries)
        assertEquals(after.revision, subscription.state.value.revision)
        subscription.close()
    }

    /** 多个硬链接共享底层身份时不能猜测改名关系，避免把选择转到另一个入口。 */
    @Test
    fun ambiguousHardLinksDoNotRedirectSelection() = browsingScenario {
        val selected = populate(5)[2]
        withContext(Dispatchers.IO) { Files.createLink(source.resolve("alias.txt"), source.resolve(selected.name)) }
        primary.dispatch(PaneIntent.Refresh)
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 6 && !it.activeTabState.refreshing }
        primary.dispatch(PaneIntent.SelectEntry(selected.id))
        withContext(Dispatchers.IO) { Files.move(source.resolve(selected.name), source.resolve("renamed-link.txt")) }
        primary.dispatch(PaneIntent.Refresh)
        val refreshed = primary.awaitBrowsing { state -> !state.activeTabState.refreshing &&
            (state.entriesState as? PaneEntriesState.Ready)?.entries?.any { it.name == "renamed-link.txt" } == true }
        assertTrue(refreshed.selectedEntryIds.isEmpty())
        assertEquals(Files.readString(source.resolve("alias.txt")), Files.readString(source.resolve("renamed-link.txt")))
    }

    /** 外部交换两个文件名时按底层对象追踪选择，不能因旧路径仍存在就误认成内容替换。 */
    @Test
    fun exchangedNamesKeepSelectedObjectIdentity() = browsingScenario {
        val entries = populate(5)
        val first = entries[1]
        val second = entries[2]
        assertNotNull(first.identityKey)
        primary.dispatch(PaneIntent.SelectEntry(first.id))
        withContext(Dispatchers.IO) {
            val temporary = source.resolve("swap.tmp")
            Files.move(source.resolve(first.name), temporary)
            Files.move(source.resolve(second.name), source.resolve(first.name))
            Files.move(temporary, source.resolve(second.name))
        }
        primary.dispatch(PaneIntent.Refresh)
        val updated = primary.awaitBrowsing { state -> !state.activeTabState.refreshing &&
            (state.entriesState as? PaneEntriesState.Ready)?.entries?.any {
                it.id == second.id && it.identityKey == first.identityKey
            } == true }
        assertEquals(setOf(second.id), updated.selectedEntryIds)
        assertEquals(second.id, updated.selectionFocusId)
        assertEquals("sample-1", Files.readString(source.resolve(second.name)))
        assertEquals("sample-2", Files.readString(source.resolve(first.name)))
    }

    /** 超过传统 Win32 路径长度的真实条目仍能列举，额外对象身份查询不能引入回归。 */
    @Test
    fun longPathEntriesKeepIdentityDuringRename() = browsingScenario {
        val directory = withContext(Dispatchers.IO) {
            var path = source
            repeat(16) { path = Files.createDirectory(path.resolve("long-directory-${it.toString().padStart(2, '0')}")) }
            Files.writeString(path.resolve("before.txt"), "long-path-content")
            path
        }
        val before = provider.list(directory.toString()).getOrThrow().single()
        assertNotNull(before.identityKey)
        withContext(Dispatchers.IO) { Files.move(directory.resolve("before.txt"), directory.resolve("after.txt")) }
        val after = provider.list(directory.toString()).getOrThrow().single()
        assertEquals(before.identityKey, after.identityKey)
        assertEquals("after.txt", after.name)
        assertEquals("long-path-content", Files.readString(directory.resolve(after.name)))
    }

    /** 无变化缓存不能省略显式定位和历史排序派生，返回已有目录仍保持用户配置。 */
    @Test
    fun unchangedCachedDirectoryStillHonorsExplicitFocusAndSort() = browsingScenario {
        val entries = populate(5)
        val targetEntry = entries[3]
        primary.dispatch(PaneIntent.OpenDirectoryAndSelect(source.toString(), targetEntry.name))
        primary.awaitBrowsing { it.selectedEntryIds == setOf(targetEntry.id) }
        assertEquals(targetEntry.id, primary.state.value.selectionFocusId)
        primary.dispatch(PaneIntent.ToggleSort(DetailsColumn.NAME))
        val expected = entries.map { it.name }.sortedDescending()
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.map { entry -> entry.name } == expected }
        primary.dispatch(PaneIntent.OpenDirectory(target.toString()))
        primary.awaitBrowsing { it.location == target.toString() && it.entriesState is PaneEntriesState.Ready }
        primary.dispatch(PaneIntent.GoBack)
        val restored = primary.awaitBrowsing { it.location == source.toString() && it.entriesState is PaneEntriesState.Ready }
        assertEquals(expected, (restored.entriesState as PaneEntriesState.Ready).entries.map { it.name })
        assertEquals(setOf(targetEntry.id), restored.selectedEntryIds)
    }
}
