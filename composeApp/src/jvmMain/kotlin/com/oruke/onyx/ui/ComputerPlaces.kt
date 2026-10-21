package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 横向排列有界的标准目录入口，面板变窄时换行，不占用七行竖排列表。
 * @param places 系统标准目录和主页。
 * @param model 当前面板的选择与焦点。
 * @param actions 面板组件事件入口。
 * @return 无返回值。
 */
@Composable
internal fun ComputerPlaces(
    places: List<SystemQuickAccessLocation>,
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
) {
    val appearance = LocalOnyxAppearance.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = appearance.entryContentSpacing
        val columns = ((maxWidth + spacing) / (appearance.computer.placeMinWidth + spacing))
            .toInt().coerceAtLeast(1)
        // 先按物理像素向下均分，避免缩放后每项四舍五入导致最后一列意外换行。
        val width = with(LocalDensity.current) {
            ((constraints.maxWidth - spacing.roundToPx() * (columns - 1)) / columns).toDp()
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            places.forEach { place ->
                ComputerEntrySurface(
                    place.location, model, actions, { actions.onOpenLocation(place.location) },
                    Modifier.width(width),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = appearance.listRowHeight)
                            .padding(appearance.entryHorizontalPadding, appearance.entryContentSpacing),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (place.isHome) AllIconsKeys.Nodes.HomeFolder else AllIconsKeys.Nodes.Folder,
                            null, Modifier.size(appearance.entryIconSize),
                        )
                        Text(
                            systemPlaceLabel(place), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = appearance.listFontSize,
                            color = appearance.fileNameColor ?: LocalOnyxPalette.current.foreground
                        )
                    }
                }
            }
        }
    }
}

/**
 * 以既有文字层级和细分隔线区分设备分区，数量不会抢占名称层级。
 * @param text 已本地化的分区名称。
 * @param count 当前可见条目数量；状态反馈不显示数量。
 * @return 无返回值。
 */
@Composable
internal fun ComputerSectionLabel(text: String, count: Int? = null) {
    val appearance = LocalOnyxAppearance.current
    Row(
        Modifier.fillMaxWidth().padding(
            top = appearance.computer.sectionSpacing,
            bottom = appearance.entryContentSpacing
        ).heightIn(min = appearance.headerHeight),
        horizontalArrangement = Arrangement.spacedBy(appearance.entryContentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, fontSize = appearance.headerFontSize, fontWeight = FontWeight.Medium,
            color = LocalOnyxPalette.current.mutedForeground
        )
        if (count != null) Text(
            count.toString(), fontSize = appearance.headerFontSize,
            color = LocalOnyxPalette.current.mutedForeground
        )
        Divider(Orientation.Horizontal, Modifier.weight(1f))
    }
}
