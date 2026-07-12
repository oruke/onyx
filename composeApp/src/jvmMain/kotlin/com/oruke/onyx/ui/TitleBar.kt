package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.orEmpty
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_open_settings
import onyx.composeapp.generated.resources.action_search
import onyx.composeapp.generated.resources.action_toggle_sidebar
import onyx.composeapp.generated.resources.action_toggle_preview_pane
import onyx.composeapp.generated.resources.app_name
import onyx.composeapp.generated.resources.label_feedback_copy_path_failed
import onyx.composeapp.generated.resources.label_feedback_create_directory_failed
import onyx.composeapp.generated.resources.label_feedback_create_file_failed
import onyx.composeapp.generated.resources.label_feedback_file_operation_failed
import onyx.composeapp.generated.resources.label_feedback_open_failed
import onyx.composeapp.generated.resources.label_feedback_rename_failed
import onyx.composeapp.generated.resources.label_feedback_watch_degraded
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.TitleBarScope

/** 操作失败反馈条使用的主题错误色透明度。 */
private const val FEEDBACK_BACKGROUND_ALPHA = 0.2f

@Composable
internal fun OperationFeedbackBar(
    feedback: PaneOperationFeedback,
    onDismiss: () -> Unit,
) {
    val text = when (feedback.kind) {
        PaneOperationFeedbackKind.OPEN_FAILED ->
            stringResource(Res.string.label_feedback_open_failed, feedback.detail?.resolve().orEmpty())

        PaneOperationFeedbackKind.RENAME_FAILED ->
            stringResource(Res.string.label_feedback_rename_failed, feedback.detail?.resolve().orEmpty())

        PaneOperationFeedbackKind.CREATE_FILE_FAILED ->
            stringResource(Res.string.label_feedback_create_file_failed, feedback.detail?.resolve().orEmpty())

        PaneOperationFeedbackKind.CREATE_DIRECTORY_FAILED ->
            stringResource(Res.string.label_feedback_create_directory_failed, feedback.detail?.resolve().orEmpty())

        PaneOperationFeedbackKind.COPY_PATH_FAILED ->
            stringResource(Res.string.label_feedback_copy_path_failed, feedback.detail?.resolve().orEmpty())

        PaneOperationFeedbackKind.FILE_OPERATION_FAILED ->
            stringResource(Res.string.label_feedback_file_operation_failed, feedback.detail?.resolve().orEmpty())

        PaneOperationFeedbackKind.WATCH_DEGRADED ->
            stringResource(Res.string.label_feedback_watch_degraded, feedback.detail?.resolve().orEmpty())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalOnyxPalette.current.error.copy(alpha = FEEDBACK_BACKGROUND_ALPHA))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = AllIconsKeys.Actions.Close,
                contentDescription = stringResource(Res.string.action_close_menu),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

// ── Title bar ───────────────────────────────────────────────────────────────

@Composable
internal fun TitleBarScope.TitleBarContent(
    rootComponent: RootComponent,
    layoutMode: PaneLayoutMode,
    uiScale: Int,
    onUiScaleChange: (Int) -> Unit,
    onToggleSidebar: () -> Unit,
    showPreviewPane: Boolean,
    onTogglePreviewPane: () -> Unit,
    searchPanelVisible: Boolean,
    onToggleSearchPanel: () -> Unit,
) {
    fun dispatch(intent: RootIntent) {
        rootComponent.dispatch(intent)
    }

    // 左侧：应用名称 — 使用 TitleBarScope.align(Start) 自动避让系统控件
    Row(
        modifier = Modifier.align(Alignment.Start)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = LocalOnyxPalette.current.foreground,
        )
    }

    // 中间：缩放滑块 — 使用 TitleBarScope.align(Center)
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$uiScale%",
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.mutedForeground,
        )
        ZoomSlider(
            value = uiScale,
            onValueChange = onUiScaleChange,
            minValue = 50,
            maxValue = 200,
        )
    }

    // 右侧：布局/侧栏/预览/设置按钮 — 使用 TitleBarScope.align(End)
    // Jewel 的 TitleBarMeasurePolicy 会在 Windows 上自动在右侧预留系统按钮空间
    Row(
        modifier = Modifier.align(Alignment.End)
            .padding(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayoutIconButton(
            selected = layoutMode == PaneLayoutMode.SINGLE,
            onClick = { dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE)) },
            tooltip = stringResource(Res.string.action_layout_single),
        ) {
            Icon(
                key = AllIconsKeys.Windows.Maximize,
                contentDescription = stringResource(Res.string.action_layout_single),
            )
        }
        LayoutIconButton(
            selected = layoutMode == PaneLayoutMode.DUAL_VERTICAL,
            onClick = { dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.DUAL_VERTICAL)) },
            tooltip = stringResource(Res.string.action_layout_dual_vertical),
        ) {
            Icon(
                key = AllIconsKeys.Actions.SplitVertically,
                contentDescription = stringResource(Res.string.action_layout_dual_vertical),
            )
        }
        LayoutIconButton(
            selected = layoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
            onClick = { dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.DUAL_HORIZONTAL)) },
            tooltip = stringResource(Res.string.action_layout_dual_horizontal),
        ) {
            Icon(
                key = AllIconsKeys.Actions.SplitHorizontally,
                contentDescription = stringResource(Res.string.action_layout_dual_horizontal),
            )
        }

        Spacer(modifier = Modifier.width(3.dp))

        TitleBarIconButton(
            onClick = onToggleSidebar,
            tooltip = stringResource(Res.string.action_toggle_sidebar),
        ) {
            Icon(
                key = AllIconsKeys.Actions.PreviewDetails,
                contentDescription = stringResource(Res.string.action_toggle_sidebar),
                modifier = Modifier.graphicsLayer(scaleX = -1f),
            )
        }

        TitleBarIconButton(
            onClick = onTogglePreviewPane,
            tooltip = stringResource(Res.string.action_toggle_preview_pane),
        ) {
            Icon(
                key = if (showPreviewPane) AllIconsKeys.Actions.Preview else AllIconsKeys.Actions.PreviewDetails,
                contentDescription = stringResource(Res.string.action_toggle_preview_pane),
            )
        }

        LayoutIconButton(
            selected = searchPanelVisible,
            onClick = onToggleSearchPanel,
            tooltip = stringResource(Res.string.action_search),
        ) {
            Icon(
                key = AllIconsKeys.Actions.Find,
                contentDescription = stringResource(Res.string.action_search),
            )
        }

        TitleBarIconButton(
            onClick = { dispatch(RootIntent.OpenSettings) },
            tooltip = stringResource(Res.string.action_open_settings),
        ) {
            Icon(
                key = AllIconsKeys.General.GearPlain,
                contentDescription = stringResource(Res.string.action_open_settings)
            )
        }
    }
}
