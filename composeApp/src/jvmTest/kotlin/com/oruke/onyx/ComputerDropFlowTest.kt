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
import com.oruke.onyx.ui.theme.TabDropZone
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

    /** 双栏切换回单栏后，隐藏副面板的残留文件热区与标签热区必须立刻失效，不得静默接收拖放移动。 */
    @Test
    fun switchingFromDualToSingleLayoutInvalidatesSecondaryDropZones() =
        runDesktopComposeUiTest(width = 960, height = 640) {
            val primaryDir = Files.createTempDirectory("onyx-drop-primary-")
            val secondaryDir = Files.createTempDirectory("onyx-drop-secondary-")
            val payload = Files.writeString(primaryDir.resolve("stay-here.txt"), "safe")
            var capturedRoot: RootComponent? = null
            var capturedDrag: ExternalFileDragService? = null
            try {
                setContent {
                    DirectoryBrowsingApplication(
                        primaryDir.toString(),
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
                    root.primaryPane.dispatch(PaneIntent.OpenDirectory(primaryDir.toString()))
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(secondaryDir.toString()))
                }
                waitUntil(timeoutMillis = 15_000) {
                    root.primaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        root.secondaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        root.secondaryPane.state.value.location == secondaryDir.toString()
                }
                val source = (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready).entries.single()
                val tasksBefore = root.state.value.tasks.map { it.id }.toSet()
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.SelectEntry(source.id))
                    val controller = AppDragController(root, checkNotNull(capturedDrag))
                    val bindings = controller.bindings
                    bindings.fileDropZones["SECONDARY:__pane"] = FileDropZone(
                        paneId = PaneId.SECONDARY,
                        targetDirectoryLocation = secondaryDir.toString(),
                        bounds = IntRect(480, 0, 960, 640),
                    )
                    bindings.tabDropZones[PaneId.SECONDARY] = TabDropZone(
                        bounds = IntRect(480, 0, 960, 40),
                        tabIds = emptyList(),
                        tabBounds = emptyMap(),
                    )
                    bindings.onFileDragStart(PaneId.PRIMARY, FileTransferOperation.MOVE)
                    bindings.onFileDragPositionChange(IntOffset(700, 200))
                    assertEquals(secondaryDir.toString(), assertNotNull(controller.fileDropTarget).targetDirectoryLocation)

                    // 切回单栏后，即使残留映射尚未收到销毁回调，也不得命中隐藏副面板。
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                    bindings.onFileDragPositionChange(IntOffset(700, 200))
                    assertNull(controller.fileDropTarget)
                    bindings.onTabDragPositionChange(IntOffset(700, 20))
                    assertNull(controller.bindings.tabDropTarget)
                    bindings.onFileDragEnd(IntOffset(700, 200))
                }
                waitForIdle()
                assertEquals(tasksBefore, root.state.value.tasks.map { it.id }.toSet())
                assertEquals("safe", Files.readString(payload))
                assertEquals(0, Files.list(secondaryDir).use { it.count() })
            } finally {
                mainClock.autoAdvance = false
                setContent {}
                waitForIdle()
                Files.deleteIfExists(payload)
                Files.deleteIfExists(primaryDir)
                Files.deleteIfExists(secondaryDir)
            }
        }

    /**
     * 真实双栏组合中，拖拽命中目标触发 `PaneDragBindings` 重组时必须完整保留面板与目录热区；
     * 仅在切回单栏隐藏副面板销毁组合后，副面板注册的热区才应由 `DisposableEffect` 自动清除。
     */
    @Test
    fun dragTargetRecompositionPreservesDropZonesAndSingleLayoutDisposesSecondaryZones() =
        runDesktopComposeUiTest(width = 1100, height = 640) {
            val primaryDir = Files.createTempDirectory("onyx-live-drop-primary-")
            val secondaryDir = Files.createTempDirectory("onyx-live-drop-secondary-")
            val payload = Files.writeString(primaryDir.resolve("stay-here.txt"), "safe")
            val nestedFolder = Files.createDirectory(secondaryDir.resolve("nested-folder"))
            var capturedRoot: RootComponent? = null
            var capturedController: AppDragController? = null
            try {
                setContent {
                    DirectoryBrowsingApplication(
                        location = primaryDir.toString(),
                        onDragControllerReady = { capturedController = it },
                    ) {
                        capturedRoot = it
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    capturedRoot?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                }
                val root = checkNotNull(capturedRoot)
                val controller = checkNotNull(capturedController)
                runOnUiThread {
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.DUAL_VERTICAL))
                    root.primaryPane.dispatch(PaneIntent.OpenDirectory(primaryDir.toString()))
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(secondaryDir.toString()))
                }
                waitUntil(timeoutMillis = 15_000) {
                    root.primaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        root.secondaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        controller.bindings.fileDropZones.containsKey("SECONDARY:__pane") &&
                        controller.bindings.tabDropZones.containsKey(PaneId.SECONDARY)
                }

                val source = (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready).entries.single()
                val secondaryZoneBounds = checkNotNull(controller.bindings.fileDropZones["SECONDARY:__pane"]).bounds
                val secondaryCenter = IntOffset(
                    x = (secondaryZoneBounds.left + secondaryZoneBounds.right) / 2,
                    y = (secondaryZoneBounds.top + secondaryZoneBounds.bottom) / 2,
                )

                // 触发真实拖拽并命中副面板，迫使 PaneSurface / PaneEntriesArea 以新 PaneDragBindings 重组。
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.SelectEntry(source.id))
                    controller.bindings.onFileDragStart(PaneId.PRIMARY, FileTransferOperation.MOVE)
                    controller.bindings.onFileDragPositionChange(secondaryCenter)
                }
                waitForIdle()

                // 重组完成后，副面板热区与当前命中目标必须保持完好，不得被 DisposableEffect 误清空。
                assertNotNull(
                    controller.bindings.fileDropZones["SECONDARY:__pane"],
                    "拖拽命中目标触发重组时不得清空面板文件放置热区",
                )
                assertNotNull(
                    controller.bindings.tabDropZones[PaneId.SECONDARY],
                    "拖拽命中目标触发重组时不得清空标签放置热区",
                )
                assertEquals(PaneId.SECONDARY, assertNotNull(controller.fileDropTarget).paneId)

                // 结束拖拽（不落地传输）并切换为单栏，验证副面板热区随组合销毁自动清除。
                runOnUiThread {
                    controller.bindings.onFileDragPositionChange(IntOffset(-100, -100))
                    controller.bindings.onFileDragEnd(IntOffset(-100, -100))
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                }
                waitUntil(timeoutMillis = 5_000) {
                    !controller.bindings.fileDropZones.containsKey("SECONDARY:__pane") &&
                        !controller.bindings.tabDropZones.containsKey(PaneId.SECONDARY)
                }
                assertEquals(
                    0,
                    controller.bindings.fileDropZones.values.count { zone ->
                        !zone.isSidebarTarget && zone.paneId == PaneId.SECONDARY
                    },
                    "切单栏后副面板所有目录与面板热区均应已注销",
                )
            } finally {
                mainClock.autoAdvance = false
                setContent {}
                waitForIdle()
                Files.deleteIfExists(payload)
                Files.deleteIfExists(nestedFolder)
                Files.deleteIfExists(primaryDir)
                Files.deleteIfExists(secondaryDir)
            }
        }
}
