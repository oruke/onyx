package com.oruke.onyx

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.ui.theme.FileDropZone
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** 使用真实应用、文件和拖放服务验证设备概览不能成为移动目标。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class ComputerDropFlowTest {
    /** 文件拖进概览的旧放置区域时不崩溃、不创建传输任务，并保留源文件内容。 */
    @Test
    fun draggingFileOverComputerDoesNotResolveOrStartTransfer() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val sample = Files.createTempDirectory("onyx-computer-drop-")
            val payload = Files.writeString(sample.resolve("preserved.txt"), "preserved")
            var capturedRoot: RootComponent? = null
            var capturedDrag: ExternalFileDragService? = null
            try {
                setContent {
                    DirectoryBrowsingApplication(
                        sample.toString(),
                        onDragReady = { capturedDrag = it },
                    ) {
                        capturedRoot = it
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    capturedRoot?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                }
                val root = checkNotNull(capturedRoot)
                runOnUiThread {
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.DUAL_VERTICAL))
                    root.primaryPane.dispatch(PaneIntent.OpenDirectory(sample.toString()))
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(sample.toString()))
                }
                waitUntil(timeoutMillis = 15_000) {
                    root.primaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        root.secondaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        root.secondaryPane.state.value.location == sample.toString()
                }
                val source =
                    (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready)
                        .entries
                        .single()
                val tasksBefore = root.state.value.tasks.map { it.id }.toSet()
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.SelectEntry(source.id))
                    val controller = AppDragController(root, checkNotNull(capturedDrag))
                    val bindings = controller.bindings
                    bindings.fileDropZones["sample-computer-zone"] =
                        FileDropZone(
                            PaneId.SECONDARY,
                            sample.toString(),
                            IntRect(0, 0, 100, 100),
                        )
                    bindings.onFileDragStart(PaneId.PRIMARY, FileTransferOperation.MOVE)
                    bindings.onFileDragPositionChange(IntOffset(50, 50))
                    assertEquals(sample.toString(), assertNotNull(controller.fileDropTarget).targetDirectoryLocation)
                    // 不等待根聚合流或下一次布局：导航后旧目录放置区必须立即失效。
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
                    assertEquals(COMPUTER_LOCATION, root.secondaryPane.state.value.location)
                    bindings.onFileDragPositionChange(IntOffset(50, 50))
                    assertNull(controller.fileDropTarget)
                    bindings.fileDropZones["sample-computer-zone"] = FileDropZone(
                        PaneId.SECONDARY, COMPUTER_LOCATION, IntRect(0, 0, 100, 100),
                    )
                    bindings.onFileDragPositionChange(IntOffset(50, 50))
                    assertNull(controller.fileDropTarget)
                    bindings.onFileDragEnd(IntOffset(50, 50))
                }
                waitForIdle()
                assertEquals(
                    tasksBefore,
                    root.state.value.tasks.map { it.id }.toSet(),
                    "拖入概览不能新增传输任务",
                )
                assertEquals("preserved", Files.readString(payload))
                assertEquals(COMPUTER_LOCATION, root.secondaryPane.state.value.location)
            } finally {
                mainClock.autoAdvance = false
                setContent {}
                waitForIdle()
                Files.deleteIfExists(payload)
                Files.deleteIfExists(sample)
            }
        }
}
