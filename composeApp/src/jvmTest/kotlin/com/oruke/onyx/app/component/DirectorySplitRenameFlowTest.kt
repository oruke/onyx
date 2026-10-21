package com.oruke.onyx.app.component

import com.oruke.onyx.vfs.api.DirectoryObservationService
import com.oruke.onyx.vfs.api.DirectoryWatchEvent
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** 通过真实文件重命名和生产面板验证跨批次通知不丢失选择、焦点与编辑草稿。 */
class DirectorySplitRenameFlowTest {
    /** 先收到新名称通知也必须立即校验完整目录，不能发布同一文件的两个入口。 */
    @Test
    fun newNameNotificationBeforeOldNameKeepsEditingState() = verifySplitRename(newNameFirst = true)

    /** 先收到旧名称删除通知时，完整快照仍能识别真实改名而不是清掉编辑对象。 */
    @Test
    fun oldNameNotificationBeforeNewNameKeepsEditingState() = verifySplitRename(newNameFirst = false)
}

/**
 * 分开发送同一次真实改名的两条通知，核对第一批处理完成后状态以及最终编辑命令落盘结果。
 * @param newNameFirst 是否先发送新名称通知，另一次通知在完整状态核对后才发送。
 * @return 无返回值；任一条目重复、身份丢失或最终文件内容错误时失败。
 */
private fun verifySplitRename(newNameFirst: Boolean) = runBlocking {
    withContext(Dispatchers.Main) {
        val changes = MutableSharedFlow<DirectoryWatchEvent.Changed>()
        val observer = DirectoryObservationService { location -> flow {
            emit(DirectoryWatchEvent.Ready)
            changes.collect { event -> if (event.location.startsWith(location)) emit(event) }
        } }
        val fixture = DirectoryBrowsingFixture(observationService = observer)
        try {
            val original = fixture.populate(10)[4]
            assertNotNull(original.identityKey)
            fixture.primary.dispatch(PaneIntent.SelectEntry(original.id))
            fixture.primary.dispatch(PaneIntent.BeginRename)
            fixture.primary.dispatch(PaneIntent.UpdateInlineEditDraft("final-name.txt"))
            val renamedPath = fixture.source.resolve("external-name.txt")
            withContext(Dispatchers.IO) { Files.move(fixture.source.resolve(original.name), renamedPath) }
            changes.emit(DirectoryWatchEvent.Changed(if (newNameFirst) renamedPath.toString() else original.location))
            val updated = fixture.primary.awaitBrowsing { state ->
                val entries = (state.entriesState as? PaneEntriesState.Ready)?.entries
                entries?.size == 10 && entries.any { it.location == renamedPath.toString() } &&
                    entries.none { it.id == original.id }
            }
            val renamed = (updated.entriesState as PaneEntriesState.Ready).entries.single { it.location == renamedPath.toString() }
            assertEquals(setOf(renamed.id), updated.selectedEntryIds)
            assertEquals(renamed.id, updated.selectionFocusId)
            assertEquals(renamed.id, updated.inlineEditState?.targetEntryId)
            assertEquals("final-name.txt", updated.inlineEditState?.draftName)
            changes.emit(DirectoryWatchEvent.Changed(if (newNameFirst) original.location else renamedPath.toString()))
            fixture.primary.dispatch(PaneIntent.ConfirmInlineEdit)
            fixture.primary.awaitBrowsing { state -> state.inlineEditState == null &&
                (state.entriesState as? PaneEntriesState.Ready)?.entries?.any { it.name == "final-name.txt" } == true }
            assertEquals("sample-4", withContext(Dispatchers.IO) { Files.readString(fixture.source.resolve("final-name.txt")) })
            assertFalse(Files.exists(renamedPath))
        } finally {
            fixture.close()
        }
    }
}
