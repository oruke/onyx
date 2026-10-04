package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_filter
import onyx.composeapp.generated.resources.action_go_back
import onyx.composeapp.generated.resources.action_go_forward
import onyx.composeapp.generated.resources.action_go_home
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_toggle_favorite
import onyx.composeapp.generated.resources.action_toggle_hidden_files
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染面板导航工具栏。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
internal fun PaneNavigationToolbar(runtime: PaneSurfaceRuntime) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().background(LocalOnyxPalette.current.headerBackground)) {
        if (maxWidth < COMPACT_NAVIGATION_WIDTH) {
            val toolbarScroll = rememberScrollState()
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("pane-navigation-toolbar")
                        .horizontalScroll(toolbarScroll)
                        .height(28.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    PaneHistoryButtons(runtime)
                    PaneLocationButtons(runtime)
                    PaneViewButtons(runtime)
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(toolbarScroll),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                PaneAddressBar(runtime, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("pane-navigation-toolbar")
                    .height(28.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                PaneHistoryButtons(runtime)
                PaneLocationButtons(runtime)
                Spacer(Modifier.width(4.dp))
                PaneAddressBar(runtime, Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                PaneViewButtons(runtime)
            }
        }
    }
}

/** 高缩放或窄面板时将地址栏独立成行的逻辑宽度阈值。 */
private val COMPACT_NAVIGATION_WIDTH = 420.dp

/**
 * 渲染统一地址栏，编辑焦点状态回传面板运行时。
 *
 * @param runtime 面板状态和事件入口。
 * @param modifier 地址栏可用空间。
 */
@Composable
private fun PaneAddressBar(runtime: PaneSurfaceRuntime, modifier: Modifier) {
    Box(modifier) {
        HybridAddressBar(
            location = runtime.state.location,
            onActivate = runtime.model.onActivate,
            onOpenLocation = { location -> runtime.dispatch(PaneIntent.OpenDirectory(location)) },
            buildBreadcrumbs = runtime.services.buildBreadcrumbs,
            onEditingChange = { editing -> runtime.ui.addressBarEditing = editing },
        )
    }
}

/**
 * 渲染后退与前进按钮。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneHistoryButtons(runtime: PaneSurfaceRuntime) {
    ToolbarIconButton(
        enabled = runtime.state.canGoBack,
        onClick = { runtime.model.onActivate(); runtime.dispatch(PaneIntent.GoBack) },
        tooltip = stringResource(Res.string.action_go_back),
    ) {
        Icon(AllIconsKeys.Actions.Back, stringResource(Res.string.action_go_back))
    }
    ToolbarIconButton(
        enabled = runtime.state.canGoForward,
        onClick = { runtime.model.onActivate(); runtime.dispatch(PaneIntent.GoForward) },
        tooltip = stringResource(Res.string.action_go_forward),
    ) {
        Icon(AllIconsKeys.Actions.Forward, stringResource(Res.string.action_go_forward))
    }
}

/**
 * 渲染上级、主页与收藏按钮。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneLocationButtons(runtime: PaneSurfaceRuntime) {
    val favorite = runtime.state.location in runtime.model.favoriteLocations
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.commands.execute(PaneCommand.GO_UP) },
        tooltip = stringResource(Res.string.action_go_up),
    ) {
        Icon(AllIconsKeys.General.ArrowUp, stringResource(Res.string.action_go_up))
    }
    ToolbarIconButton(
        enabled = true,
        onClick = {
            runtime.model.onActivate()
            runtime.dispatch(PaneIntent.OpenDirectory(System.getProperty("user.home")))
        },
        tooltip = stringResource(Res.string.action_go_home),
    ) {
        Icon(AllIconsKeys.Nodes.HomeFolder, stringResource(Res.string.action_go_home))
    }
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.commands.execute(PaneCommand.TOGGLE_FAVORITE) },
        tooltip = stringResource(Res.string.action_toggle_favorite),
        selected = favorite,
    ) {
        Text(
            text = if (favorite) "★" else "☆",
            fontSize = 11.sp,
            color = if (favorite) LocalOnyxPalette.current.favorite else LocalOnyxPalette.current.foreground,
        )
    }
}

/**
 * 渲染过滤、刷新与隐藏文件按钮。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneViewButtons(runtime: PaneSurfaceRuntime) {
    UserCommandToolbar(runtime)
    val filterVisible = runtime.state.filterInputVisible || runtime.state.filterQuery.isNotEmpty()
    ToolbarIconButton(
        enabled = true,
        onClick = {
            runtime.model.onActivate()
            if (filterVisible) runtime.closeFilterInput() else runtime.openFilterInput()
        },
        tooltip = onyxCommandTooltip(
            stringResource(Res.string.action_filter),
            OnyxCommand.Filter,
            runtime.model.commandShortcuts,
        ),
        selected = filterVisible,
    ) {
        Icon(AllIconsKeys.Actions.Find, stringResource(Res.string.action_filter))
    }
    Spacer(modifier = Modifier.width(4.dp))
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.commands.execute(PaneCommand.REFRESH) },
        tooltip = stringResource(Res.string.action_refresh_active),
    ) {
        Icon(AllIconsKeys.Actions.Refresh, stringResource(Res.string.action_refresh_active))
    }
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.dispatch(PaneIntent.ToggleHiddenItems) },
        tooltip = stringResource(Res.string.action_toggle_hidden_files),
        selected = runtime.state.showHiddenItems,
    ) {
        val icon = if (runtime.state.showHiddenItems) {
            AllIconsKeys.General.Show
        } else {
            AllIconsKeys.Actions.ToggleVisibility
        }
        Icon(icon, stringResource(Res.string.action_toggle_hidden_files))
    }
}
