package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.deviceSections
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.label_computer_places
import onyx.composeapp.generated.resources.label_storage_empty
import onyx.composeapp.generated.resources.label_storage_loading
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Link

/**
 * 此电脑默认以磁盘概览呈现：顶部紧凑文件夹入口，下方按类型排列名称、容量条和限制状态。
 * 保持 Jewel 外观，所有交互继续交给面板组件；设备更新不替换访问身份或滚动适配器。
 * @param model 当前面板视图与访问状态。
 * @param actions 面板组件事件入口。
 * @param state 已完成过滤、排序和状态校正的设备概览。
 * @return 无返回值。
 */
@Composable
internal fun ComputerEntriesView(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    state: ComputerEntriesState,
) {
    Column(Modifier.fillMaxSize().testTag("computer-${model.paneId}")) {
        listOfNotNull(state.failure, state.placesFailure, state.operationFailure).forEach { failure ->
            PaneFeedbackBar(failure.resolve()) {
                Link(stringResource(Res.string.action_refresh_active), onClick = actions.onRefreshComputer)
            }
        }
        when {
            state.loading && state.devices.isEmpty() && state.visiblePlaces.isEmpty() ->
                ComputerSectionLabel(stringResource(Res.string.label_storage_loading))

            state.visibleDevices.isEmpty() && state.visiblePlaces.isEmpty() ->
                ComputerSectionLabel(stringResource(Res.string.label_storage_empty))

            model.viewMode == ViewMode.GALLERY -> ComputerOverview(model, actions, state)
            else -> ComputerDetails(model, actions, state)
        }
    }
}

/**
 * 使用固定最小可读宽度的虚拟网格排列磁盘，单个网络盘也不会被拉伸为整行容量条。
 * @param model 当前视图与访问状态。
 * @param actions 面板组件事件入口。
 * @param state 设备与标准目录的派生状态。
 * @return 无返回值。
 */
@Composable
private fun ComputerOverview(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    state: ComputerEntriesState,
) {
    val appearance = LocalOnyxAppearance.current
    val layoutModel = model.forComputerLayout(state)
    val scroll = rememberPaneGridViewport(layoutModel, actions, computerLayoutKeys(state, false), false)
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(appearance.computer.deviceMinWidth),
            state = scroll,
            modifier = Modifier.fillMaxSize().testTag("computer-grid-${model.paneId}"),
            contentPadding = PaddingValues(horizontal = appearance.entryHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(appearance.computer.itemSpacing),
            userScrollEnabled = !model.contextMenuVisible,
        ) {
            if (state.visiblePlaces.isNotEmpty()) {
                item("computer-places", span = { GridItemSpan(maxLineSpan) }) {
                    ComputerSectionLabel(stringResource(Res.string.label_computer_places), state.visiblePlaces.size)
                }
                item("computer-places-grid", span = { GridItemSpan(maxLineSpan) }) {
                    ComputerPlaces(state.visiblePlaces, model, actions)
                }
            }
            state.deviceSections.forEach { section ->
                item(computerSectionKey(section.kind), span = { GridItemSpan(maxLineSpan) }) {
                    ComputerSectionLabel(computerDeviceSectionTitle(section.kind), section.devices.size)
                }
                items(section.devices, key = { it.id }) { device ->
                    ComputerDeviceTile(device, model, actions, device.id in state.busyDeviceIds)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
