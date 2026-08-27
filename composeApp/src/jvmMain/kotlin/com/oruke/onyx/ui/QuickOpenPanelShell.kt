package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneContextMenuCommand
import com.oruke.onyx.app.component.PaneContextMenuIcon
import com.oruke.onyx.app.component.PaneContextMenuModel
import com.oruke.onyx.app.component.PaneContextMenuNode
import com.oruke.onyx.app.component.PaneContextMenuText
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_in_folder
import onyx.composeapp.generated.resources.quick_open_placeholder
import onyx.composeapp.generated.resources.shortcut_key_enter
import onyx.composeapp.generated.resources.shortcut_key_escape
import onyx.composeapp.generated.resources.shortcut_modifier_ctrl
import onyx.composeapp.generated.resources.shortcut_separator
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染快速打开的主体容器，负责组合输入、结果和扫描进度。
 *
 * @param state 当前搜索面板状态。
 * @param focusRequester 关键字输入框的焦点请求器。
 * @param listState 结果列表的滚动状态。
 * @param selectedIndex 当前选中结果的下标。
 * @param locationLabel 将文件位置转换为可展示文本的函数。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onSelectedIndexChange 选中结果下标变更回调。
 * @param onShowContextMenu 展示指定结果右键菜单的回调。
 * @param modifier 应用于主体容器的修饰符。
 */
@Composable
internal fun QuickOpenDialogSurface(
    state: SearchPanelState,
    focusRequester: FocusRequester,
    listState: LazyListState,
    selectedIndex: Int,
    locationLabel: (String) -> String,
    actions: QuickOpenPanelActions,
    onSelectedIndexChange: (Int) -> Unit,
    onShowContextMenu: (Int, VFile, IntOffset) -> Unit,
    modifier: Modifier,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = modifier
            .width(640.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(palette.floatingSurface)
            .border(1.dp, palette.outline, RoundedCornerShape(8.dp))
            .clickable(enabled = false) {}
            .onPreviewKeyEvent { event ->
                handleQuickOpenKeyboardEvent(
                    event = event,
                    results = state.results,
                    selectedIndex = selectedIndex,
                    actions = actions,
                    onSelectedIndexChange = onSelectedIndexChange,
                )
            },
    ) {
        QuickOpenInputRow(
            query = state.query,
            focusRequester = focusRequester,
            onQueryChange = actions.onQueryChange,
        )
        QuickOpenResultsView(
            state = state,
            listState = listState,
            selectedIndex = selectedIndex,
            locationLabel = locationLabel,
            actions = actions,
            onShowContextMenu = onShowContextMenu,
        )
        if (state.status == SearchStatus.RUNNING && state.results.isNotEmpty()) {
            QuickOpenSearchProgress(state)
        }
    }
}

/**
 * 渲染快速打开关键字输入行和可发现的键盘快捷键提示。
 *
 * @param query 当前搜索关键字。
 * @param focusRequester 关键字输入框的焦点请求器。
 * @param onQueryChange 搜索关键字变更回调。
 */
@Composable
private fun QuickOpenInputRow(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            key = AllIconsKeys.Actions.Find,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = palette.accent,
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .background(palette.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            textStyle = TextStyle(fontSize = 13.sp, color = palette.foreground),
            singleLine = true,
            cursorBrush = SolidColor(palette.accent),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.quick_open_placeholder),
                            fontSize = 12.sp,
                            color = palette.disabledForeground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        QuickOpenShortcutHints()
    }
}

/** 渲染快速打开支持的 Enter、组合键和 Escape 快捷键提示。 */
@Composable
private fun QuickOpenShortcutHints() {
    val enterKey = stringResource(Res.string.shortcut_key_enter)
    val controlKey = stringResource(Res.string.shortcut_modifier_ctrl)
    val separator = stringResource(Res.string.shortcut_separator)
    val openInFolderShortcut = "$controlKey$separator$enterKey"
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickOpenShortcutChip(
            shortcut = enterKey,
            label = stringResource(Res.string.action_open),
        )
        QuickOpenShortcutChip(
            shortcut = openInFolderShortcut,
            label = stringResource(Res.string.action_open_in_folder),
        )
        QuickOpenShortcutChip(
            shortcut = stringResource(Res.string.shortcut_key_escape),
            label = stringResource(Res.string.action_close),
        )
    }
}

/**
 * 渲染单个快捷键与其动作说明。
 *
 * @param shortcut 要展示的快捷键文本。
 * @param label 快捷键对应的已国际化动作说明。
 */
@Composable
private fun QuickOpenShortcutChip(shortcut: String, label: String) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .background(palette.surfaceVariant, RoundedCornerShape(3.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = shortcut,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = palette.accent,
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = palette.mutedForeground,
        )
    }
}

/**
 * 渲染快速打开结果的右键菜单，并将菜单命令转换为对应文件动作。
 *
 * @param request 当前待展示的右键菜单请求；为空时不渲染菜单。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onClose 关闭右键菜单的回调。
 */
@Composable
internal fun BoxScope.QuickOpenResultContextMenu(
    request: QuickOpenContextMenuRequest?,
    actions: QuickOpenPanelActions,
    onClose: () -> Unit,
) {
    request?.let { contextMenuRequest ->
        val openInFolderText = stringResource(Res.string.action_open_in_folder)
        val menuModel = remember(contextMenuRequest.file, openInFolderText) {
            PaneContextMenuModel(
                nodes = listOf(
                    PaneContextMenuNode.Item(
                        id = "open",
                        text = PaneContextMenuText.OPEN,
                        icon = PaneContextMenuIcon.OPEN,
                        command = PaneContextMenuCommand.Pane(PaneCommand.OPEN_SELECTION),
                    ),
                    PaneContextMenuNode.Item(
                        id = "open_in_folder",
                        displayName = openInFolderText,
                        icon = PaneContextMenuIcon.FOLDER,
                        command = PaneContextMenuCommand.CopyPath,
                    ),
                ),
            )
        }
        PaneContextMenu(
            anchorOffset = contextMenuRequest.anchorOffset,
            model = menuModel,
            onCommand = { command ->
                handleQuickOpenContextMenuCommand(
                    command = command,
                    targetFile = contextMenuRequest.file,
                    actions = actions,
                )
                onClose()
            },
            onClose = onClose,
        )
    }
}

/**
 * 执行快速打开右键菜单中允许的文件动作。
 *
 * @param command 用户选择的菜单命令。
 * @param targetFile 右键点击的目标文件。
 * @param actions 快速打开面板可发出的组件层动作。
 */
private fun handleQuickOpenContextMenuCommand(
    command: PaneContextMenuCommand,
    targetFile: VFile,
    actions: QuickOpenPanelActions,
) {
    if (command is PaneContextMenuCommand.Pane) {
        actions.onOpenResult(targetFile)
    }
    if (command is PaneContextMenuCommand.CopyPath) {
        actions.onOpenResultInFolder(targetFile)
    }
}
