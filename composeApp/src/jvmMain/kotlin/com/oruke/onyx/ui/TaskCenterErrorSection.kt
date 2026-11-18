package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_copy_all_errors
import onyx.composeapp.generated.resources.action_copy_error_file_name
import onyx.composeapp.generated.resources.action_task_collapse_errors
import onyx.composeapp.generated.resources.action_task_expand_errors
import onyx.composeapp.generated.resources.label_task_copied_feedback
import onyx.composeapp.generated.resources.label_task_error_reason
import onyx.composeapp.generated.resources.label_task_errors_count
import onyx.composeapp.generated.resources.label_task_more_errors_expand
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 收起态默认直接预览的错误条数，保持多任务卡片紧凑同时直接暴露文件名与失败原因。 */
private const val COLLAPSED_VISIBLE_ERRORS = 2

/** 展开态单任务最多直接渲染的错误条数上限，避免极端成千上万条错误阻塞合成。 */
private const val MAX_EXPANDED_RENDERED_ERRORS = 120

/** 错误竖条指示器宽度。 */
private val ERROR_ACCENT_BAR_WIDTH = 2.dp

/** 错误容器圆角。 */
private val ERROR_CONTAINER_RADIUS = 4.dp

/**
 * 渲染高密度结构化任务错误详情区：
 * - 文件名与失败原因上下分层，避免超长文件名将真实失败原因挤出可视区域；
 * - 支持一键展开查看全部失败项，以及复制全部错误报告或单个失败文件名。
 *
 * @param task 当前后台任务快照。
 * @param expanded 当前任务错误列表是否已展开全部条目。
 * @param copiedFeedbackKey 最近一次成功写入剪贴板的反馈键。
 * @param onToggleExpand 切换展开/收起回调。
 * @param onCopyErrorText 复制文本到系统剪贴板回调（参数为 `feedbackKey` 与文本内容）。
 */
@Composable
internal fun TaskErrorSection(
    task: BackgroundTask,
    expanded: Boolean,
    copiedFeedbackKey: String?,
    onToggleExpand: () -> Unit,
    onCopyErrorText: (String, String) -> Unit,
) {
    if (task.errors.isEmpty()) return
    val palette = LocalOnyxPalette.current
    val canExpand = task.errors.size > COLLAPSED_VISIBLE_ERRORS
    val resolvedErrors = task.errors.map { error ->
        ResolvedTaskError(
            fileName = error.fileName,
            reason = resolveTaskErrorReason(error),
        )
    }
    val visibleItems = if (expanded || !canExpand) {
        resolvedErrors.take(MAX_EXPANDED_RENDERED_ERRORS)
    } else {
        resolvedErrors.take(COLLAPSED_VISIBLE_ERRORS)
    }
    val copyAllKey = "all:${task.id}"

    Spacer(modifier = Modifier.height(2.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task-errors-section-${task.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TaskErrorHeaderBar(
            taskId = task.id,
            totalErrors = task.errors.size,
            canExpand = canExpand,
            expanded = expanded,
            copiedAll = copiedFeedbackKey == copyAllKey,
            onToggleExpand = onToggleExpand,
            onCopyAll = {
                onCopyErrorText(copyAllKey, buildFormattedErrorReport(resolvedErrors))
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ERROR_CONTAINER_RADIUS))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(ERROR_CONTAINER_RADIUS))
                .background(palette.surfaceVariant),
        ) {
            visibleItems.forEachIndexed { index, item ->
                val itemCopyKey = "item:${task.id}:$index"
                TaskErrorItemRow(
                    taskId = task.id,
                    index = index,
                    item = item,
                    expanded = expanded,
                    copied = copiedFeedbackKey == itemCopyKey,
                    onCopyFileName = { onCopyErrorText(itemCopyKey, item.fileName) },
                )
                if (index < visibleItems.lastIndex || (canExpand && !expanded)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.outlineVariant),
                    )
                }
            }

            if (canExpand && !expanded) {
                val hiddenCount = task.errors.size - visibleItems.size
                TaskErrorMoreFooter(
                    taskId = task.id,
                    hiddenCount = hiddenCount,
                    onClick = onToggleExpand,
                )
            }
        }
    }
}

@Composable
private fun TaskErrorHeaderBar(
    taskId: String,
    totalErrors: Int,
    canExpand: Boolean,
    expanded: Boolean,
    copiedAll: Boolean,
    onToggleExpand: () -> Unit,
    onCopyAll: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val headerInteraction = remember { MutableInteractionSource() }
    val headerHovered by headerInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val summaryModifier = if (canExpand) {
            Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(ERROR_CONTAINER_RADIUS))
                .hoverable(headerInteraction)
                .background(if (headerHovered) palette.rowHoverBackground else Color.Transparent)
                .clickable(interactionSource = headerInteraction, indication = null, onClick = onToggleExpand)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag("task-errors-toggle-$taskId")
        } else {
            Modifier.weight(1f, fill = false).padding(vertical = 2.dp)
        }

        Row(
            modifier = summaryModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                key = when {
                    !canExpand -> AllIconsKeys.General.Warning
                    expanded -> AllIconsKeys.General.ArrowDown
                    else -> AllIconsKeys.General.ArrowRight
                },
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = stringResource(Res.string.label_task_errors_count, totalErrors),
                fontSize = appearance.headerFontSize,
                fontWeight = FontWeight.SemiBold,
                color = palette.error,
                maxLines = 1,
            )
            if (canExpand) {
                Text(
                    text = if (expanded) {
                        stringResource(Res.string.action_task_collapse_errors)
                    } else {
                        stringResource(Res.string.action_task_expand_errors, totalErrors)
                    },
                    fontSize = appearance.headerFontSize,
                    color = palette.accent,
                    maxLines = 1,
                )
            }
        }

        TaskErrorCopyAllAction(
            taskId = taskId,
            copied = copiedAll,
            onClick = onCopyAll,
        )
    }
}

@Composable
private fun TaskErrorCopyAllAction(
    taskId: String,
    copied: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(ERROR_CONTAINER_RADIUS))
            .hoverable(interaction)
            .background(if (hovered) palette.rowHoverBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("task-errors-copy-all-$taskId"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!copied) {
            Icon(
                key = AllIconsKeys.Actions.Copy,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(
            text = if (copied) {
                "✓ ${stringResource(Res.string.label_task_copied_feedback)}"
            } else {
                stringResource(Res.string.action_copy_all_errors)
            },
            fontSize = appearance.headerFontSize,
            color = if (copied) palette.success else palette.mutedForeground,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskErrorItemRow(
    taskId: String,
    index: Int,
    item: ResolvedTaskError,
    expanded: Boolean,
    copied: Boolean,
    onCopyFileName: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val copyDescription = stringResource(Res.string.action_copy_error_file_name)
    val copyInteraction = remember { MutableInteractionSource() }
    val copyHovered by copyInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("task-error-item-$taskId-$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(ERROR_ACCENT_BAR_WIDTH)
                .fillMaxHeight()
                .background(palette.error.copy(alpha = 0.85f)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, top = 5.dp, bottom = 5.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "#${index + 1}  ${item.fileName}",
                fontSize = appearance.headerFontSize,
                fontWeight = FontWeight.Medium,
                color = palette.foreground,
                maxLines = if (expanded) 2 else 1,
                overflow = if (expanded) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis,
            )
            Text(
                text = stringResource(Res.string.label_task_error_reason, item.reason),
                fontSize = appearance.headerFontSize,
                color = palette.errorForeground,
                maxLines = if (expanded) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OnyxTooltip(text = copyDescription) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(ERROR_CONTAINER_RADIUS))
                    .hoverable(copyInteraction)
                    .background(if (copyHovered) palette.rowHoverBackground else Color.Transparent)
                    .clickable(interactionSource = copyInteraction, indication = null, onClick = onCopyFileName)
                    .testTag("task-error-copy-$taskId-$index"),
                contentAlignment = Alignment.Center,
            ) {
                if (copied) {
                    Text(
                        text = "✓",
                        fontSize = appearance.headerFontSize,
                        fontWeight = FontWeight.Bold,
                        color = palette.success,
                    )
                } else {
                    Icon(
                        key = AllIconsKeys.Actions.Copy,
                        contentDescription = copyDescription,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskErrorMoreFooter(
    taskId: String,
    hiddenCount: Int,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (hovered) palette.rowHoverBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("task-errors-more-$taskId"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_task_more_errors_expand, hiddenCount),
            fontSize = appearance.headerFontSize,
            color = palette.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            key = AllIconsKeys.General.ArrowDown,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
        )
    }
}

private data class ResolvedTaskError(
    val fileName: String,
    val reason: String,
)

@Composable
private fun resolveTaskErrorReason(error: TaskError): String {
    val raw = error.detail?.resolve() ?: error.message
    return raw.trim().ifEmpty { "-" }
}

private fun buildFormattedErrorReport(errors: List<ResolvedTaskError>): String {
    return errors.mapIndexed { index, item ->
        "[${index + 1}] ${item.fileName}\n    ${item.reason}"
    }.joinToString(separator = "\n")
}
