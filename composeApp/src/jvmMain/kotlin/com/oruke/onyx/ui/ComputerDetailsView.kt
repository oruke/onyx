package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.deviceSections
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_computer_places
import onyx.composeapp.generated.resources.label_storage_free
import onyx.composeapp.generated.resources.label_storage_total
import onyx.composeapp.generated.resources.label_storage_usage
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 在既有访问视口内渲染紧凑列表，表头与设备行按相同列宽自适应。
 * @param model 当前面板的视图与访问状态。
 * @param actions 面板组件事件入口。
 * @param state 当前设备与标准目录。
 * @return 无返回值。
 */
@Composable
internal fun ComputerDetails(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    state: ComputerEntriesState,
) {
    val appearance = LocalOnyxAppearance.current
    val layoutModel = model.forComputerLayout(state)
    val horizontal = rememberPaneHorizontalScroll(layoutModel)
    val scroll = rememberPaneListViewport(layoutModel, actions, computerLayoutKeys(state, true), false, horizontal)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ComputerDetailsColumns(
            totalVisible = maxWidth >= appearance.storageCompactWidth + appearance.computer.totalColumnWidth,
            usageVisible = maxWidth >= appearance.storageCompactWidth,
        )
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().testTag("computer-list-${model.paneId}"),
            contentPadding = PaddingValues(horizontal = appearance.entryHorizontalPadding),
            userScrollEnabled = !model.contextMenuVisible,
        ) {
            if (state.visiblePlaces.isNotEmpty()) {
                item("computer-places") {
                    ComputerSectionLabel(stringResource(Res.string.label_computer_places), state.visiblePlaces.size)
                }
                item("computer-places-grid") { ComputerPlaces(state.visiblePlaces, model, actions) }
            }
            item("computer-columns") { ComputerDetailsHeader(columns) }
            state.deviceSections.forEach { section ->
                item(computerSectionKey(section.kind)) {
                    ComputerSectionLabel(computerDeviceSectionTitle(section.kind), section.devices.size)
                }
                items(section.devices, key = { it.id }) { device ->
                    ComputerDeviceRow(device, model, actions, device.id in state.busyDeviceIds, columns)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * 绘制紧凑列表的共同列头，避免名称与容量错位。
 * @param columns 当前窗口宽度允许显示的附加列。
 * @return 无返回值。
 */
@Composable
private fun ComputerDetailsHeader(columns: ComputerDetailsColumns) {
    val appearance = LocalOnyxAppearance.current
    val dimensions = appearance.computer
    val color = LocalOnyxPalette.current.mutedForeground
    Row(
        Modifier.fillMaxWidth().padding(top = dimensions.sectionSpacing)
            .background(LocalOnyxPalette.current.headerBackground)
            .heightIn(min = appearance.headerHeight)
            .padding(horizontal = appearance.entryHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(dimensions.itemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.label_column_name), Modifier.weight(1f),
            fontSize = appearance.headerFontSize, color = color
        )
        Text(
            stringResource(Res.string.label_storage_free), Modifier.width(dimensions.availableColumnWidth),
            textAlign = TextAlign.End, fontSize = appearance.headerFontSize, color = color
        )
        if (columns.totalVisible) Text(
            stringResource(Res.string.label_storage_total),
            Modifier.width(dimensions.totalColumnWidth), textAlign = TextAlign.End,
            fontSize = appearance.headerFontSize, color = color
        )
        if (columns.usageVisible) Text(
            stringResource(Res.string.label_storage_usage),
            Modifier.width(dimensions.usageColumnWidth), fontSize = appearance.headerFontSize, color = color
        )
    }
}
