package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.orEmpty
import com.oruke.onyx.ui.theme.rememberOnyxTitleBarIconPainter
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_open_settings
import onyx.composeapp.generated.resources.action_search
import onyx.composeapp.generated.resources.action_toggle_preview_pane
import onyx.composeapp.generated.resources.action_toggle_sidebar
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

/** 沉浸式标题栏左侧应用品牌图标的标准尺寸。 */
private const val TITLE_BAR_BRAND_ICON_SIZE_DP = 16

/** 沉浸式标题栏品牌图标与窗口标题文字之间的间距。 */
private const val TITLE_BAR_BRAND_ICON_GAP_DP = 7

/**
 * 将操作失败映射为本地化文字，并使用公共面板反馈条提供关闭入口。
 *
 * @param feedback 统一操作失败模型。
 * @param onDismiss 关闭本次临时反馈。
 */
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
    PaneFeedbackBar(text) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = AllIconsKeys.Actions.Close,
                contentDescription = stringResource(Res.string.action_close_window),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

// ── Title bar ───────────────────────────────────────────────────────────────

/**
 * 在沉浸式标题栏起始位置渲染统一的应用品牌 Logo 与窗口标题，自动避让各平台原生窗口控制区。
 *
 * @param title 标题栏显示的窗口标题。
 * @param startPadding 起始侧内边距。
 * @param fontSize 标题文字字号。
 * @param fontWeight 标题文字字重。
 * @param textColor 标题文字颜色，默认继承 Jewel 标题栏样式内容色。
 */
@Composable
internal fun TitleBarScope.TitleBarBrandTitle(
    title: String,
    startPadding: Dp = 10.dp,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    textColor: Color = Color.Unspecified,
) {
    val brandPainter = rememberOnyxTitleBarIconPainter(TITLE_BAR_BRAND_ICON_SIZE_DP.dp)
    Row(
        modifier = Modifier
            .align(Alignment.Start)
            .padding(start = startPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TITLE_BAR_BRAND_ICON_GAP_DP.dp),
    ) {
        Image(
            painter = brandPainter,
            contentDescription = null,
            modifier = Modifier.size(TITLE_BAR_BRAND_ICON_SIZE_DP.dp),
        )
        Text(
            text = title,
            fontWeight = fontWeight,
            fontSize = fontSize,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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

    // 左侧：应用品牌图标与名称 — 使用 TitleBarScope.align(Start) 自动避让系统控件
    TitleBarBrandTitle(
        title = stringResource(Res.string.app_name),
        startPadding = 10.dp,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textColor = LocalOnyxPalette.current.foreground,
    )

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
            minValue = 75,
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
                contentDescription = stringResource(Res.string.action_open_settings),
            )
        }
        TaskCenterButton(rootComponent.taskCenter)
    }
}
