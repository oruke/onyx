package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.core.model.*
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** 通过真实组件、真实文件命令和磁盘最终状态验证目录同步与访问保持。 */
class DirectoryBrowsingFlowTest {
    /** 删除后的邻项续接只作用发起面板，无关目录不重新列举也不丢编辑草稿。 */
    @Test
    fun consecutiveDeleteKeepsContextAndDoesNotReadUnrelatedDirectory() = browsingScenario {
        val entries = populate(1_000)
        Files.writeString(target.resolve("keep.txt"), "keep")
        secondary.dispatch(PaneIntent.Refresh)
        secondary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 1 }
        val targetEntry = assertIs<PaneEntriesState.Ready>(secondary.state.value.entriesState).entries.single()
        secondary.dispatch(PaneIntent.SelectEntry(targetEntry.id))
        secondary.dispatch(PaneIntent.BeginRename)
        secondary.dispatch(PaneIntent.UpdateInlineEditDraft("unfinished.txt"))
        val targetReads = repository.reads.getValue(target.toString()).get()
        val sourceReads = repository.reads.getValue(source.toString()).get()
        val actions = actions()
        primary.dispatch(PaneIntent.SelectEntry(entries[500].id))
        repeat(2) { iteration ->
            val entry = entries[500 + iteration]
            actions.executeDeleteRequest(FileActionDelegate.PendingDeleteRequest(PaneId.PRIMARY, listOf(entry), false))
            primary.awaitBrowsing { it.selectedEntryIds == setOf(entries[501 + iteration].id) }
            assertFalse(Files.exists(source.resolve(entry.name)))
            assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState)
        }
        assertEquals(sourceReads, repository.reads.getValue(source.toString()).get())
        assertEquals(targetReads, repository.reads.getValue(target.toString()).get())
        assertEquals("unfinished.txt", secondary.state.value.inlineEditState?.draftName)
        assertEquals(setOf(targetEntry.id), secondary.state.value.selectedEntryIds)
        assertEquals("keep", Files.readString(target.resolve("keep.txt")))
    }

    /** F5 成功、失败和重试均保留 Ready 列表、多选与编辑，不把加载状态当成新访问。 */
    @Test
    fun refreshKeepsReadySelectionAndDraftAcrossFailureAndRetry() = browsingScenario {
        val entries = populate()
        primary.dispatch(PaneIntent.SelectEntries(setOf(entries[30].id, entries[31].id)))
        primary.dispatch(PaneIntent.BeginRename)
        primary.dispatch(PaneIntent.UpdateInlineEditDraft("draft.txt"))
        val before = primary.state.value
        val gate = BrowsingReadGate()
        repository.gates[source.toString()] = gate
        primary.dispatch(PaneIntent.Refresh)
        gate.started.await()
        assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState)
        assertEquals(before.selectedEntryIds, primary.state.value.selectedEntryIds)
        assertEquals("draft.txt", primary.state.value.inlineEditState?.draftName)
        repository.failing += source.toString()
        gate.release.complete(Unit)
        primary.awaitBrowsing { it.activeTabState.directoryError != null }
        assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState)
        repository.failing.clear()
        primary.dispatch(PaneIntent.Refresh)
        primary.awaitBrowsing { !it.activeTabState.refreshing && it.activeTabState.directoryError == null }
        assertEquals(before.selectedEntryIds, primary.state.value.selectedEntryIds)
        assertEquals("draft.txt", primary.state.value.inlineEditState?.draftName)
        assertEquals(before.activeTabState.browserState.visitId, primary.state.value.activeTabState.browserState.visitId)
    }

    /** Up、Back、Forward、切换标签及跨面板转移均恢复同一次访问的多选、筛选和视口。 */
    @Test
    fun historyAndTransferredTabRestoreVisitState() = browsingScenario {
        val entries = populate()
        val child = Files.createDirectory(source.resolve("child"))
        primary.dispatch(PaneIntent.Refresh)
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 81 }
        primary.dispatch(PaneIntent.SetFilterQuery("item"))
        val selection = setOf(entries[40].id, entries[41].id)
        primary.dispatch(PaneIntent.SelectEntries(selection))
        val tabId = primary.state.value.activeTabId
        val visitId = primary.state.value.activeTabState.browserState.visitId
        val anchor = PaneViewportAnchor(entries[35].id, 35, 7f, 13f)
        primary.dispatch(PaneIntent.ViewportChanged(tabId, visitId, ViewMode.DETAILS, anchor, false))
        primary.dispatch(PaneIntent.OpenDirectory(child.toString()))
        primary.awaitBrowsing { it.location == child.toString() && it.entriesState is PaneEntriesState.Ready }
        primary.dispatch(PaneIntent.GoUp)
        primary.awaitBrowsing { it.location == source.toString() && it.entriesState is PaneEntriesState.Ready }
        assertEquals(selection, primary.state.value.selectedEntryIds)
        assertEquals(anchor, primary.state.value.activeTabState.browserState.viewports[ViewMode.DETAILS])
        assertEquals("item", primary.state.value.filterQuery)
        primary.dispatch(PaneIntent.GoForward)
        primary.awaitBrowsing { it.location == child.toString() }
        primary.dispatch(PaneIntent.GoBack)
        primary.awaitBrowsing { it.location == source.toString() }
        primary.dispatch(PaneIntent.CreateTab(target.toString()))
        primary.awaitBrowsing { it.location == target.toString() && it.entriesState is PaneEntriesState.Ready }
        primary.dispatch(PaneIntent.SelectTab(tabId))
        primary.awaitBrowsing { it.location == source.toString() && it.entriesState is PaneEntriesState.Ready }
        assertEquals(selection, primary.state.value.selectedEntryIds)
        val transfer = assertNotNull(primary.detachTab(tabId))
        secondary.attachTab(transfer, 0)
        secondary.awaitBrowsing { it.activeTabId == tabId && it.entriesState is PaneEntriesState.Ready }
        assertEquals(selection, secondary.state.value.selectedEntryIds)
        assertEquals(anchor, secondary.state.value.activeTabState.browserState.viewports[ViewMode.DETAILS])
        assertEquals(visitId, secondary.state.value.activeTabState.browserState.visitId)
    }

    /** SKIP 保留源条目，KEEP_BOTH 使用真实副本名；目标浏览选择不随传入文件改变。 */
    @Test
    fun moveConflictResultsMatchDiskAndPreserveTargetSelection() = browsingScenario {
        val entries = populate()
        Files.writeString(target.resolve(entries[30].name), "existing")
        secondary.dispatch(PaneIntent.Refresh)
        secondary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 1 }
        val existing = assertIs<PaneEntriesState.Ready>(secondary.state.value.entriesState).entries.single()
        secondary.dispatch(PaneIntent.SelectEntry(existing.id))
        primary.dispatch(PaneIntent.SelectEntries(setOf(entries[30].id, entries[31].id)))
        val transfer = transfers()
        transfer.requestTransferEntriesToDirectory(listOf(entries[30], entries[31]), target.toString(),
            FileTransferOperation.MOVE, false)
        withTimeout(10_000) { dialog.first { it is RootDialogState.ConflictResolution } }
        transfer.resolveConflict(TransferConflictStrategy.SKIP, true)
        withTimeout(10_000) { tasks.tasks.first { it.firstOrNull()?.status == BackgroundTaskStatus.SUCCEEDED } }
        primary.awaitBrowsing { it.selectedEntryIds == setOf(entries[30].id) }
        assertTrue(Files.exists(source.resolve(entries[30].name)))
        assertFalse(Files.exists(source.resolve(entries[31].name)))
        assertEquals("existing", Files.readString(target.resolve(entries[30].name)))
        transfer.requestTransferEntriesToDirectory(listOf(entries[30]), target.toString(), FileTransferOperation.MOVE, false)
        withTimeout(10_000) { dialog.first { it is RootDialogState.ConflictResolution } }
        transfer.resolveConflict(TransferConflictStrategy.KEEP_BOTH, true)
        val copyName = entries[30].name.removeSuffix(".txt") + " copy.txt"
        secondary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.any { e -> e.name == copyName } == true }
        assertFalse(Files.exists(source.resolve(entries[30].name)))
        assertEquals("sample-30", Files.readString(target.resolve(copyName)))
        assertEquals(setOf(existing.id), secondary.state.value.selectedEntryIds)
    }

    /** 已删除部分保留；失败和取消只校验相关目录，用户后来选择的文件不会被旧任务覆盖。 */
    @Test
    fun partialFailureAndCancellationKeepCommittedChangesAndNewInteraction() = browsingScenario {
        val entries = populate()
        val failedCommands = object : FileCommandService by provider {
            /**
             * 删除第一项后使第二项返回真实任务边界错误。
             * @param entries 本步骤删除目标。
             * @return 第一项真实删除结果或受控失败。
             */
            override suspend fun delete(entries: List<VFile>): Result<Unit> =
                if (entries.single().name == "item-0021.txt") Result.failure(IOException("controlled delete failure"))
                else provider.delete(entries)
        }
        primary.dispatch(PaneIntent.SelectEntries(setOf(entries[20].id, entries[21].id)))
        actions(failedCommands).executeDeleteRequest(FileActionDelegate.PendingDeleteRequest(PaneId.PRIMARY,
            listOf(entries[20], entries[21]), false))
        withTimeout(10_000) { tasks.tasks.first { it.firstOrNull()?.status == BackgroundTaskStatus.FAILED } }
        primary.awaitBrowsing { it.selectedEntryIds == setOf(entries[21].id) }
        assertFalse(Files.exists(source.resolve(entries[20].name)))
        assertTrue(Files.exists(source.resolve(entries[21].name)))
        val entered = CompletableDeferred<Unit>()
        val cancelCommands = object : FileCommandService by provider {
            /**
             * 删除第一项后阻塞第二项，取消仍由真实协程任务处理。
             * @param entries 本步骤删除目标。
             * @return 真实删除结果；第二项等待期间允许取消。
             */
            override suspend fun delete(entries: List<VFile>): Result<Unit> {
                if (entries.single().name == "item-0023.txt") { entered.complete(Unit); awaitCancellation() }
                return provider.delete(entries)
            }
        }
        primary.dispatch(PaneIntent.SelectEntries(setOf(entries[22].id, entries[23].id)))
        actions(cancelCommands).executeDeleteRequest(FileActionDelegate.PendingDeleteRequest(PaneId.PRIMARY,
            listOf(entries[22], entries[23]), false))
        entered.await()
        primary.dispatch(PaneIntent.SelectEntry(entries[50].id))
        tasks.cancelTask(tasks.tasks.value.first().id)
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.none { e -> e.id == entries[22].id } == true }
        assertEquals(setOf(entries[50].id), primary.state.value.selectedEntryIds)
        assertFalse(Files.exists(source.resolve(entries[22].name)))
        assertTrue(Files.exists(source.resolve(entries[23].name)))
    }

    /** 旧快照不能把刚删除的文件放回来，A→B→A 之后返回结果仍属于正确访问。 */
    @Test
    fun staleReadAndRapidNavigationDoNotResurrectRemovedEntry() = browsingScenario {
        val entries = populate()
        val gate = BrowsingReadGate(captureBeforeRelease = true)
        repository.gates[source.toString()] = gate
        primary.dispatch(PaneIntent.Refresh)
        gate.started.await()
        primary.dispatch(PaneIntent.SelectEntry(entries[40].id))
        actions().executeDeleteRequest(FileActionDelegate.PendingDeleteRequest(PaneId.PRIMARY, listOf(entries[40]), false))
        primary.awaitBrowsing { it.selectedEntryIds == setOf(entries[41].id) }
        primary.dispatch(PaneIntent.OpenDirectory(target.toString()))
        primary.awaitBrowsing { it.location == target.toString() }
        primary.dispatch(PaneIntent.GoBack)
        gate.release.complete(Unit)
        primary.awaitBrowsing { !it.activeTabState.refreshing &&
            (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 79 }
        assertEquals(source.toString(), primary.state.value.location)
        assertFalse(assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState).entries.any { it.id == entries[40].id })
        assertFalse(Files.exists(source.resolve(entries[40].name)))
    }

    /** 真实系统事件突发最终与磁盘一致，被动删除只剔除原选择，不自动选择无关文件。 */
    @Test
    fun externalBatchEventsConvergeWithoutAutomaticSelection() = browsingScenario(observe = true) {
        val entries = populate()
        primary.dispatch(PaneIntent.SelectEntry(entries[40].id))
        withContext(Dispatchers.IO) {
            repeat(320) { Files.writeString(source.resolve("external-$it.txt"), "external") }
            Files.delete(source.resolve(entries[40].name))
        }
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == 399 }
        assertTrue(primary.state.value.selectedEntryIds.isEmpty())
        val diskNames = withContext(Dispatchers.IO) { Files.newDirectoryStream(source).use { it.map { p -> p.fileName.toString() }.toSet() } }
        assertEquals(diskNames, assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState).entries.map { it.name }.toSet())
    }

    /** 完整目录校验发现改名目标消失时结束编辑并显示原因，后续新建不被旧草稿阻塞。 */
    @Test
    fun deletedRenameTargetClosesEditorAndAllowsNewCreation() = browsingScenario {
        val entries = populate()
        primary.dispatch(PaneIntent.SelectEntry(entries[20].id))
        primary.dispatch(PaneIntent.BeginRename)
        primary.dispatch(PaneIntent.UpdateInlineEditDraft("unfinished-name.txt"))
        withContext(Dispatchers.IO) { Files.delete(source.resolve(entries[20].name)) }
        primary.dispatch(PaneIntent.Refresh)
        primary.awaitBrowsing { it.inlineEditState == null && it.operationFeedback != null }
        assertEquals(MessageKey.MSG_INLINE_EDIT_TARGET_UNAVAILABLE, primary.state.value.operationFeedback?.detail?.key)
        primary.dispatch(PaneIntent.BeginCreateFile)
        primary.awaitBrowsing { it.inlineEditState != null }
        primary.dispatch(PaneIntent.UpdateInlineEditDraft("follow-up.txt"))
        primary.dispatch(PaneIntent.ConfirmInlineEdit)
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.any { e -> e.name == "follow-up.txt" } == true }
        assertTrue(Files.exists(source.resolve("follow-up.txt")))
        assertFalse(Files.exists(source.resolve("unfinished-name.txt")))
    }

    /** 行内改名和新建使用真实结果更新，并保持改名后的多选身份。 */
    @Test
    fun inlineRenameAndCreateKeepSelectionAndFilter() = browsingScenario {
        val entries = populate()
        primary.dispatch(PaneIntent.SelectEntries(setOf(entries[30].id, entries[31].id)))
        primary.dispatch(PaneIntent.BeginRename)
        val oldId = assertNotNull(primary.state.value.inlineEditState?.targetEntryId)
        primary.dispatch(PaneIntent.UpdateInlineEditDraft("renamed.txt"))
        primary.dispatch(PaneIntent.ConfirmInlineEdit)
        primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.any { e -> e.name == "renamed.txt" } == true }
        val renamed = assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState).entries.single { it.name == "renamed.txt" }
        primary.awaitBrowsing { renamed.id in it.selectedEntryIds && it.selectedEntryIds.size == 2 }
        assertFalse(oldId in primary.state.value.selectedEntryIds)
        assertEquals("sample-${entries.indexOfFirst { it.id == oldId }}", Files.readString(source.resolve("renamed.txt")))
        primary.dispatch(PaneIntent.SetFilterQuery("item"))
        primary.dispatch(PaneIntent.BeginCreateFile)
        primary.awaitBrowsing { it.inlineEditState != null }
        primary.dispatch(PaneIntent.UpdateInlineEditDraft("hidden-by-filter.txt"))
        primary.dispatch(PaneIntent.ConfirmInlineEdit)
        withTimeout(10_000) { while (!Files.exists(source.resolve("hidden-by-filter.txt"))) delay(10) }
        assertEquals("item", primary.state.value.filterQuery)
        assertFalse(assertIs<PaneEntriesState.Ready>(primary.state.value.entriesState).entries.any { it.name == "hidden-by-filter.txt" })
    }
}
