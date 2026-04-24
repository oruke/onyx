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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RootComponent
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
import onyx.composeapp.generated.resources.action_toggle_sidebar
import onyx.composeapp.generated.resources.app_name
import onyx.composeapp.generated.resources.label_feedback_copy_path_failed
import onyx.composeapp.generated.resources.label_feedback_create_directory_failed
import onyx.composeapp.generated.resources.label_feedback_create_file_failed
import onyx.composeapp.generated.resources.label_feedback_open_failed
import onyx.composeapp.generated.resources.label_feedback_rename_failed
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

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
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33D74E4E))
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
internal fun TitleBarContent(
    rootComponent: RootComponent,
    layoutMode: PaneLayoutMode,
    uiScale: Int,
    sidebarVisible: Boolean,
    onUiScaleChange: (Int) -> Unit,
    onToggleSidebar: () -> Unit,
    showPreviewPane: Boolean,
    onTogglePreviewPane: () -> Unit,
) {
    // Jewel DecoratedWindow 的标题栏内容区域
    // 注意：DecoratedWindow 会自动处理窗口拖拽和系统按钮区域
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = LocalOnyxPalette.current.foreground,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
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
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayoutIconButton(
                selected = layoutMode == PaneLayoutMode.SINGLE,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.SINGLE) },
                tooltip = stringResource(Res.string.action_layout_single),
            ) {
                Icon(
                    key = AllIconsKeys.General.Layout,
                    contentDescription = stringResource(Res.string.action_layout_single),
                )
            }
            LayoutIconButton(
                selected = layoutMode == PaneLayoutMode.DUAL_VERTICAL,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_VERTICAL) },
                tooltip = stringResource(Res.string.action_layout_dual_vertical),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.SplitVertically,
                    contentDescription = stringResource(Res.string.action_layout_dual_vertical),
                )
            }
            LayoutIconButton(
                selected = layoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_HORIZONTAL) },
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
                    key = if (sidebarVisible) AllIconsKeys.Actions.ToggleVisibility else AllIconsKeys.General.Show,
                    contentDescription = stringResource(Res.string.action_toggle_sidebar),
                )
            }

            TitleBarIconButton(
                onClick = onTogglePreviewPane,
                tooltip = "Toggle Preview Pane",
            ) {
                Icon(
                    key = if (showPreviewPane) AllIconsKeys.Actions.Preview else AllIconsKeys.Actions.PreviewDetails,
                    contentDescription = "Toggle Preview Pane",
                )
            }

            TitleBarIconButton(
                onClick = rootComponent::openSettings,
                tooltip = stringResource(Res.string.action_open_settings),
            ) {
                Icon(
                    key = AllIconsKeys.General.GearPlain,
                    contentDescription = stringResource(Res.string.action_open_settings)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

