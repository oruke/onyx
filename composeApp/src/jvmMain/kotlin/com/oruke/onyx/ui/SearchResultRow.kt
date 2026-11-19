package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.core.model.isTrashItemLocation
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_search_enter_directory
import onyx.composeapp.generated.resources.action_search_locate_in_folder
import onyx.composeapp.generated.resources.action_search_open_item
import onyx.composeapp.generated.resources.label_directory_badge
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染单条搜索结果；支持关键词高亮、目录标识，以及悬停/选中时的快捷“进入/打开”与“定位所在位置”按钮。
 *
 * @param entry 当前搜索结果。
 * @param query 当前搜索关键词，用于高亮名称命中片段。
 * @param isSelected 当前结果是否被键盘或点击选中。
 * @param onSelect 将当前结果设为组件选中项。
 * @param onOpen 打开当前搜索结果（文件夹直接进入主窗口）。
 * @param onLocateInFolder 在所属父目录中定位并选中该搜索结果。
 * @param onContextMenu 在指定窗口坐标显示右键菜单。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SearchResultRow(
    entry: VFile,
    query: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onLocateInFolder: () -> Unit,
    onContextMenu: (IntOffset) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    var isHovered by remember { mutableStateOf(false) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val backgroundColor = when {
        isSelected -> palette.selectionBackground
        isHovered -> palette.rowHoverBackground
        else -> Color.Transparent
    }
    val isDirectory = entry.kind == VFileKind.DIRECTORY
    val canLocate = entry.parentLocation != null || isTrashItemLocation(entry.location)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(appearance.listRowHeight.coerceAtLeast(24.dp))
            .testTag("search-result-${entry.id}")
            .semantics { selected = isSelected }
            .onGloballyPositioned { rowCoordinates = it }
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    val pointer = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val windowPos = rowCoordinates?.localToWindow(pointer) ?: pointer
                    onContextMenu(IntOffset(windowPos.x.roundToInt(), windowPos.y.roundToInt()))
                }
            }
            .background(backgroundColor)
            .combinedClickable(onClick = onSelect, onDoubleClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnyxFileIcon(entry = entry, size = 14.dp)
        Text(
            text = highlightMatchText(
                text = entry.name.decodeSfmDisplayText(),
                query = query,
                normalColor = if (isSelected) palette.selectionForeground else palette.foreground,
                highlightColor = if (isSelected) palette.selectionForeground else palette.accent,
                highlightBackground = if (isSelected) {
                    palette.surface.copy(alpha = 0.28f)
                } else {
                    palette.accent.copy(alpha = 0.14f)
                },
            ),
            fontSize = appearance.listFontSize,
            fontWeight = if (isDirectory) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isHovered || isSelected) {
            SearchResultQuickActions(
                entryId = entry.id,
                isDirectory = isDirectory,
                canLocate = canLocate,
                onOpen = onOpen,
                onLocateInFolder = onLocateInFolder,
            )
        }
        Text(
            text = if (isDirectory) {
                stringResource(Res.string.label_directory_badge)
            } else {
                formatFileSize(entry.sizeBytes)
            },
            color = palette.mutedForeground,
            fontSize = appearance.headerFontSize,
            maxLines = 1,
        )
        Text(
            text = formatModifiedTime(entry.modifiedAtEpochMillis),
            color = palette.mutedForeground,
            fontSize = appearance.headerFontSize,
            maxLines = 1,
        )
    }
}

/**
 * 渲染搜索结果行右侧的悬停/选中快捷动作按钮（直接打开/进入文件夹、打开所在位置）。
 */
@Composable
private fun SearchResultQuickActions(
    entryId: String,
    isDirectory: Boolean,
    canLocate: Boolean,
    onOpen: () -> Unit,
    onLocateInFolder: () -> Unit,
) {
    val openTooltip = stringResource(
        if (isDirectory) Res.string.action_search_enter_directory else Res.string.action_search_open_item,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchRowActionIconButton(
            iconKey = AllIconsKeys.Actions.MenuOpen,
            tooltip = openTooltip,
            testTag = "search-result-open-$entryId",
            onClick = onOpen,
        )
        if (canLocate) {
            SearchRowActionIconButton(
                iconKey = AllIconsKeys.Nodes.Folder,
                tooltip = stringResource(Res.string.action_search_locate_in_folder),
                testTag = "search-result-locate-$entryId",
                onClick = onLocateInFolder,
            )
        }
    }
}

/**
 * 单个搜索结果行内紧凑图标按钮。
 */
@Composable
private fun SearchRowActionIconButton(
    iconKey: IconKey,
    tooltip: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    OnyxTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .hoverable(interactionSource)
                .background(if (hovered) palette.surface.copy(alpha = 0.72f) else Color.Transparent)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = iconKey,
                contentDescription = tooltip,
                modifier = Modifier.size(12.dp),
                tint = if (hovered) palette.accent else palette.foreground,
            )
        }
    }
}
