package com.oruke.onyx

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.vfs.api.StorageDeviceService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 生产 App 入口的设备限制状态矩阵，只替代平台快照，不声称制造了真实只读介质。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class StorageDeviceStatusUiTest {
    /** 同一已知容量的只读设备在宽详情、窄详情和画廊始终显示限制，状态更新后不可用提示同样保留。 */
    @Test
    fun readOnlyAndUnavailableStateRemainVisibleAcrossPresentationModes() =
        runDesktopComposeUiTest(width = 760, height = 540) {
            val sample = Files.createTempDirectory("onyx-storage-status-")
            val devices = StorageStatusFixtureService(sample.root.toString())
            var captured: RootComponent? = null
            var savedSettings: OnyxSettings? = null
            try {
                setContent {
                    DirectoryBrowsingApplication(sample.toString(), storageDevices = devices) {
                        captured = it
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    captured?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                }
                val root = checkNotNull(captured)
                savedSettings = root.state.value.settings
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(
                                preferredLocale = AppLocale.ENGLISH,
                                sidebarVisible = false,
                                uiScale = 100,
                            )
                        )
                    )
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                    root.primaryPane.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
                    root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS))
                }
                waitUntil(timeoutMillis = 5_000) {
                    (root.primaryPane.state.value.entriesState as? ComputerEntriesState)
                        ?.devices
                        ?.isNotEmpty() == true
                }
                val entryTag = "computer-entry-PRIMARY-sample-read-only-volume"
                onNodeWithTag("computer-list-PRIMARY", useUnmergedTree = true)
                    .performScrollToNode(hasTestTag(entryTag))
                assertTrue(
                    onAllNodesWithText("Read-only", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                )
                saveComputerScreenshot("computer-read-only-wide")
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            root.state.value.settings.copy(sidebarVisible = true, uiScale = 150)
                        )
                    )
                }
                onNodeWithTag("computer-list-PRIMARY", useUnmergedTree = true)
                    .performScrollToNode(hasTestTag(entryTag))
                assertTrue(
                    onAllNodesWithText("Read-only", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                )
                saveComputerScreenshot("computer-read-only-narrow")
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.SetViewMode(ViewMode.GALLERY))
                }
                onNodeWithTag("computer-grid-PRIMARY", useUnmergedTree = true)
                    .performScrollToNode(hasTestTag(entryTag))
                assertTrue(
                    onAllNodesWithText("Read-only", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                )
                saveComputerScreenshot("computer-read-only-gallery")
                devices.updates.value =
                    Result.success(
                        listOf(
                            devices.device.copy(
                                readOnly = false,
                                state = StorageDeviceState.UNAVAILABLE,
                            )
                        )
                    )
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Unavailable", substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                onNodeWithTag("computer-capacity-PRIMARY-${devices.device.id}", useUnmergedTree = true)
                    .assertDoesNotExist()
                saveComputerScreenshot("computer-unavailable")
                onNodeWithTag(entryTag, useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    devices.device.id in root.primaryPane.state.value.selectedEntryIds
                }
                onNodeWithTag(entryTag, useUnmergedTree = true).assertIsSelected()
                saveComputerScreenshot("computer-unavailable-selected")
                devices.updates.value = Result.success(listOf(devices.device.copy(
                    readOnly = false, availableBytes = 50_000_000L,
                )))
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Low space", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("computer-capacity-PRIMARY-${devices.device.id}", useUnmergedTree = true)
                    .assertIsDisplayed()
                assertEquals(0.95f,
                    onNodeWithTag("computer-capacity-PRIMARY-${devices.device.id}", useUnmergedTree = true)
                        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
                onNodeWithTag(entryTag, useUnmergedTree = true).assertIsSelected()
                saveComputerScreenshot("computer-low-space")
                devices.updates.value = Result.success(listOf(devices.device.copy(
                    totalBytes = null, availableBytes = null,
                    failure = I18nMessage(MessageKey.MSG_DEVICE_UNAVAILABLE),
                )))
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Capacity unknown", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("computer-capacity-PRIMARY-${devices.device.id}", useUnmergedTree = true)
                    .assertDoesNotExist()
                onNodeWithText("Capacity unknown", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                saveComputerScreenshot("computer-capacity-unknown")
            } finally {
                savedSettings?.let { settings ->
                    runOnUiThread { captured?.dispatch(RootIntent.UpdateSettings(settings)) }
                }
                mainClock.autoAdvance = false
                setContent {}
                waitForIdle()
                Files.deleteIfExists(sample)
            }
        }
}

/**
 * 确定性的只读平台数据，用于核对真实 App 渲染，不替代原生只读磁盘验收。
 *
 * @param location 测试宿主上的真实文件系统根目录。
 */
private class StorageStatusFixtureService(location: String) : StorageDeviceService {
    /** 已知容量且已挂载的只读设备示例。 */
    val device =
        StorageDevice(
            "sample-read-only-volume",
            "Sample volume",
            StorageDeviceKind.LOCAL,
            listOf(location),
            StorageDeviceState.MOUNTED,
            readOnly = true,
            totalBytes = 1_000_000_000L,
            availableBytes = 500_000_000L,
        )
    /** 平台数据更新，仍通过生产组件的观察入口流转。 */
    val updates = MutableStateFlow(Result.success(listOf(device)))

    /** @return 当前测试平台设备清单。 */
    override suspend fun snapshot(): Result<List<StorageDevice>> = updates.value

    /** @return 由生产组件管理订阅的状态变化流。 */
    override fun observe(): Flow<Result<List<StorageDevice>>> = updates

    /**
     * 本场景不支持执行设备命令，误操作必须返回明确失败。
     *
     * @param deviceId 请求的设备身份。
     * @param action 请求的原生动作。
     * @return 当前平台快照没有声明操作能力的错误。
     */
    override suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?> =
        Result.failure(LocalizedException(I18nMessage(MessageKey.MSG_DEVICE_ACTION_UNAVAILABLE)))
}
