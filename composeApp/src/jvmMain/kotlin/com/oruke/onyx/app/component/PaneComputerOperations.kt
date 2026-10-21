package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.isComputerLocation
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.shared.filesystem.toI18nMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 为当前概览绑定共享设备更新和系统目录；导航离开时取消订阅，保留标签浏览快照。
 *
 * @param tabId 当前标签身份。
 * @param visitId 当前访问身份，用于拒绝迟到结果。
 */
internal fun DefaultPaneComponent.loadComputerLocation(tabId: String, visitId: Long) {
    updateTab(tabId) { value ->
        value.copy(allEntries = emptyList()).withTabState { state ->
            val computer =
                state.entriesState as? ComputerEntriesState
                    ?: ComputerEntriesState(places = systemQuickAccessService.initialLocations())
            state.copy(
                entriesState =
                    computer.copy(
                        operationFailure = null,
                        placesFailure = null,
                        busyDeviceIds = deviceOperationJobs.keys.toSet(),
                    ),
                inlineEditState = null,
                refreshing = false,
                directoryError = null,
                directoryObservationError = null,
            )
        }
    }
    tabLoadJobs[tabId] =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch {
                systemQuickAccessService
                    .loadComputerPlaces()
                    .fold(
                        onSuccess = { places ->
                            updateComputerState(tabId, visitId) {
                                it.copy(places = places, placesFailure = null)
                            }
                        },
                        onFailure = { failure ->
                            updateComputerState(tabId, visitId) {
                                it.copy(placesFailure = failure.toI18nMessage())
                            }
                        },
                    )
            }
            try {
                applyComputerSnapshot(tabId, visitId, storageDevices.snapshot())
                storageDevices.observe().collect { applyComputerSnapshot(tabId, visitId, it) }
            } finally {
                if (tabLoadJobs[tabId] === currentCoroutineContext()[Job]) tabLoadJobs.remove(tabId)
            }
        }
}

/**
 * 将平台结果合并到当前访问；失败保留设备列表，不能让离线设备变成空页面。
 *
 * @param tabId 结果所属标签。
 * @param visitId 结果所属访问。
 * @param result 平台返回的完整设备快照。
 */
private fun DefaultPaneComponent.applyComputerSnapshot(
    tabId: String,
    visitId: Long,
    result: Result<List<com.oruke.onyx.core.model.StorageDevice>>,
) {
    updateComputerState(tabId, visitId) { state ->
        result.fold(
            onSuccess = { state.copy(devices = it, loading = false, failure = null) },
            onFailure = { state.copy(loading = false, failure = it.toI18nMessage()) },
        )
    }
    if (result.isSuccess) focusComputerRoot(tabId, visitId)
}

/**
 * 使用标签与访问双重身份更新设备状态，旧请求不能覆盖用户新打开的位置。
 *
 * @param tabId 请求所属标签。
 * @param visitId 请求所属访问。
 * @param transform 不涉及平台对象的状态转换。
 */
internal fun DefaultPaneComponent.updateComputerState(
    tabId: String,
    visitId: Long,
    transform: (ComputerEntriesState) -> ComputerEntriesState,
) {
    val current =
        tab(tabId)?.takeIf {
            it.tabState.browserState.visitId == visitId &&
                it.id == state.value.activeTabId &&
                isComputerLocation(it.location)
        } ?: return
    val previous = current.entriesState as? ComputerEntriesState ?: return
    updateTab(tabId) { it.withTabState { state -> state.copy(entriesState = transform(previous)) } }
}

/**
 * 执行单设备操作，操作完成后只有原访问仍活动时才允许自动进入挂载目录。
 *
 * @param deviceId 平台稳定设备身份。
 * @param action 平台操作；为空表示打开或挂载后打开。
 * @param newTab 是否在新标签进入设备。
 */
internal fun DefaultPaneComponent.performDeviceAction(
    deviceId: String,
    action: StorageDeviceAction? = null,
    newTab: Boolean = false,
) {
    val origin = activeTab() ?: return
    val computer = origin.entriesState as? ComputerEntriesState ?: return
    if (deviceId in deviceOperationJobs) return
    val device = computer.devices.firstOrNull { it.id == deviceId } ?: return
    val visitId = origin.tabState.browserState.visitId
    updateComputerState(origin.id, visitId) {
        it.copy(operationFailure = null)
    }
    // 先登记再启动，避免快速完成的原生操作先执行 finally，留下永远无法清理的任务。
    val operation =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result =
                    if (action != null) storageDevices.execute(device.id, action)
                    else storageDevices.openLocation(device.id)
                result.fold(
                    onSuccess = { location ->
                        val stillCurrent =
                            activeTab()?.let {
                                it.id == origin.id && it.tabState.browserState.visitId == visitId
                            } == true
                        val opensLocation = action == null || action == StorageDeviceAction.MOUNT
                        if (opensLocation && location != null && stillCurrent) {
                            if (newTab) createTab(location) else openDirectory(location)
                        }
                    },
                    onFailure = { failure ->
                        updateComputerState(origin.id, visitId) {
                            it.copy(operationFailure = failure.toI18nMessage())
                        }
                    },
                )
            } finally {
                if (deviceOperationJobs[deviceId] === currentCoroutineContext()[Job]) {
                    deviceOperationJobs.remove(deviceId)
                }
                synchronizeComputerOperations()
            }
        }
    deviceOperationJobs[deviceId] = operation
    synchronizeComputerOperations()
    operation.start()
}

/** 将真实在途任务同步到所有概览标签，后台标签不能永久保留已经完成的忙碌标记。 */
private fun DefaultPaneComponent.synchronizeComputerOperations() {
    val busy = deviceOperationJobs.keys.toSet()
    tabs().forEach { tab ->
        val computer = tab.entriesState as? ComputerEntriesState ?: return@forEach
        if (computer.busyDeviceIds != busy) {
            updateTab(tab.id) { current ->
                current.withTabState { it.copy(entriesState = computer.copy(busyDeviceIds = busy)) }
            }
        }
    }
}
