package com.oruke.onyx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.core.model.BatchRenameEditMode as BatchRenameMode
import com.oruke.onyx.core.model.BatchRenameEditCaseMode as CaseMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.BatchRenameNameTransformations
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.label_batch_rename_after
import onyx.composeapp.generated.resources.label_batch_rename_before
import onyx.composeapp.generated.resources.label_batch_rename_changes_count
import onyx.composeapp.generated.resources.label_batch_rename_completed_detail
import onyx.composeapp.generated.resources.label_batch_rename_counter_digits
import onyx.composeapp.generated.resources.label_batch_rename_counter_ext_hint
import onyx.composeapp.generated.resources.label_batch_rename_counter_start
import onyx.composeapp.generated.resources.label_batch_rename_error_prefix
import onyx.composeapp.generated.resources.label_batch_rename_file_count
import onyx.composeapp.generated.resources.label_batch_rename_find
import onyx.composeapp.generated.resources.label_batch_rename_invalid_regex
import onyx.composeapp.generated.resources.label_batch_rename_mode_case
import onyx.composeapp.generated.resources.label_batch_rename_mode_template
import onyx.composeapp.generated.resources.label_batch_rename_mode_counter
import onyx.composeapp.generated.resources.label_batch_rename_mode_find_replace
import onyx.composeapp.generated.resources.label_batch_rename_mode_prefix_suffix
import onyx.composeapp.generated.resources.label_batch_rename_prefix
import onyx.composeapp.generated.resources.label_batch_rename_progress_detail
import onyx.composeapp.generated.resources.label_batch_rename_regex
import onyx.composeapp.generated.resources.label_batch_rename_replace
import onyx.composeapp.generated.resources.label_batch_rename_suffix
import onyx.composeapp.generated.resources.label_batch_rename_template
import onyx.composeapp.generated.resources.label_batch_rename_title
import onyx.composeapp.generated.resources.label_case_lowercase
import onyx.composeapp.generated.resources.label_case_titlecase
import onyx.composeapp.generated.resources.label_case_uppercase
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/** 批量重命名窗口允许缩放到的最小宽度。 */
private const val BATCH_RENAME_MIN_WINDOW_WIDTH = 640

/** 批量重命名窗口允许缩放到的最小高度。 */
private const val BATCH_RENAME_MIN_WINDOW_HEIGHT = 480

/** 编辑区最多占用一半弹窗余量，为预览和底部动作保留空间。 */
private const val BATCH_RENAME_EDITOR_WEIGHT = 0.5f

// ── 对话框 ──────────────────────────────────────────────────────────────

/**
 * 在独立原生窗口渲染组件草稿、预设与执行预览。
 * @param state 组件提供的当前草稿及任务状态。
 * @param onConfirm 执行已经规划的文件名称变更。
 * @param onDismiss 关闭窗口事件。
 * @param settings 当前保存的外观与预设。
 * @param onIntent 更新草稿、加载和管理预设的组件事件入口。
 * @param initialWidth 初始逻辑窗口宽度。
 * @param initialHeight 初始逻辑窗口高度。
 * @param onWindowSizeChanged 保存窗口尺寸的回调。
 */
@Composable
internal fun BatchRenameDialog(
    state: RootDialogState.BatchRename,
    onConfirm: (List<Pair<VFile, String>>) -> Unit,
    onDismiss: () -> Unit,
    settings: OnyxSettings = OnyxSettings(),
    onIntent: (RootIntent) -> Unit = {},
    initialWidth: Int = 800,
    initialHeight: Int = 620,
    onWindowSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) {
    val entries = state.entries
    val editor = BatchRenameEditorState(state) { onIntent(RootIntent.UpdateBatchRenameDraft(it)) }
    val canConfirm = editor.hasChanges && state.validationMessage == null

    val title = stringResource(Res.string.label_batch_rename_title)

    val dialogState = rememberDialogState(width = initialWidth.dp, height = initialHeight.dp)

    LaunchedEffect(dialogState) {
        snapshotFlow { dialogState.size }
            .collect { size ->
                val w = size.width;
                val h = size.height
                if (w != Dp.Unspecified && h != Dp.Unspecified) {
                    onWindowSizeChanged?.invoke(w.value.toInt(), h.value.toInt())
                }
            }
    }

    DialogWindow(
        onCloseRequest = { if (!state.executing) onDismiss() },
        title = title,
        state = dialogState,
        resizable = true,
    ) {
        SideEffect {
            val scale = settings.uiScale / 100.0
            window.minimumSize = java.awt.Dimension(
                (BATCH_RENAME_MIN_WINDOW_WIDTH * scale).toInt(),
                (BATCH_RENAME_MIN_WINDOW_HEIGHT * scale).toInt(),
            )
        }
        OnyxWindowTheme(settings) {
            BatchRenameContent(state, editor, title, canConfirm, onConfirm, onDismiss, settings, onIntent)
        }
    }
}

/**
 * 组合批量重命名窗口的各个功能区域。
 *
 * @param state 当前批量重命名执行状态。
 * @param editor 重命名规则编辑状态。
 * @param title 窗口标题。
 * @param canConfirm 当前规则是否允许提交。
 * @param onConfirm 提交有效重命名结果的回调。
 * @param onDismiss 关闭窗口的回调。
 * @param settings 已保存的外观及预设。
 * @param onIntent 规则草稿与预设操作的组件事件。
 */
@Composable
private fun BatchRenameContent(
    state: RootDialogState.BatchRename,
    editor: BatchRenameEditorState,
    title: String,
    canConfirm: Boolean,
    onConfirm: (List<Pair<VFile, String>>) -> Unit,
    onDismiss: () -> Unit,
    settings: OnyxSettings,
    onIntent: (RootIntent) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Column(
        modifier = Modifier.fillMaxSize().background(palette.appBackground).padding(14.dp),
    ) {
        Text(
            text = title,
            fontSize = (appearance.listFontSize.value + 1).sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (!state.executing && !state.completed) {
            val editorScroll = rememberScrollState()
            Box(
                Modifier.fillMaxWidth().weight(BATCH_RENAME_EDITOR_WEIGHT, fill = false),
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(editorScroll).padding(end = 8.dp)) {
                    BatchRenamePresetBar(state, settings, onIntent)
                    BatchRenameModeEditor(editor)
                    state.validationMessage?.let { Text(it.resolve(), color = palette.error) }
                }
                // 预设编辑在大字号下占用较多高度，显式滚动条提示下方仍有模式与规则配置。
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(editorScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                    )
                }
            }
        }
        BatchRenameProgress(state)
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        BatchRenamePreviewHeader()
        BatchRenamePreview(editor.renameResults, Modifier.weight(1f).fillMaxWidth())
        BatchRenameFooter(state, editor, canConfirm, onConfirm, onDismiss)
    }
}

/**
 * 显示模式选择与当前模式对应的配置项。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun BatchRenameModeEditor(editor: BatchRenameEditorState) {
    BatchRenameModeTabs(editor)
    Spacer(modifier = Modifier.height(8.dp))
    when (editor.mode) {
        BatchRenameMode.FIND_REPLACE -> FindReplaceEditor(editor)
        BatchRenameMode.PREFIX_SUFFIX -> PrefixSuffixEditor(editor)
        BatchRenameMode.COUNTER -> CounterEditor(editor)
        BatchRenameMode.CASE_CHANGE -> CaseChangeEditor(editor)
        BatchRenameMode.TEMPLATE -> BatchRenameTemplateEditor(editor)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * 显示批量重命名模式标签栏。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun BatchRenameModeTabs(editor: BatchRenameEditorState) {
    val palette = LocalOnyxPalette.current
    val labelFontSize = LocalOnyxAppearance.current.headerFontSize
    val modes = listOf(
        BatchRenameMode.FIND_REPLACE to Res.string.label_batch_rename_mode_find_replace,
        BatchRenameMode.PREFIX_SUFFIX to Res.string.label_batch_rename_mode_prefix_suffix,
        BatchRenameMode.COUNTER to Res.string.label_batch_rename_mode_counter,
        BatchRenameMode.CASE_CHANGE to Res.string.label_batch_rename_mode_case,
        BatchRenameMode.TEMPLATE to Res.string.label_batch_rename_mode_template,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        modes.forEach { (mode, labelResource) ->
            HoverTab(
                text = stringResource(labelResource),
                selected = editor.mode == mode,
                accent = palette.accent,
                foreground = palette.foreground,
                fontSize = labelFontSize,
                onClick = { editor.mode = mode },
            )
        }
    }
}

/**
 * 显示查找替换模式的配置项及正则校验错误。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun FindReplaceEditor(editor: BatchRenameEditorState) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    LabeledField(
        stringResource(Res.string.label_batch_rename_find),
        editor.findText,
        appearance.headerFontSize,
        appearance.listFontSize,
    ) { editor.findText = it }
    Spacer(modifier = Modifier.height(6.dp))
    LabeledField(
        stringResource(Res.string.label_batch_rename_replace),
        editor.replaceText,
        appearance.headerFontSize,
        appearance.listFontSize,
    ) { editor.replaceText = it }
    Spacer(modifier = Modifier.height(6.dp))
    HoverTab(
        text = stringResource(Res.string.label_batch_rename_regex),
        selected = editor.useRegex,
        accent = palette.accent,
        foreground = palette.mutedForeground,
        fontSize = appearance.headerFontSize,
        onClick = { editor.useRegex = !editor.useRegex },
    )
}

/**
 * 显示前后缀模式的配置项。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun PrefixSuffixEditor(editor: BatchRenameEditorState) {
    val appearance = LocalOnyxAppearance.current
    LabeledField(
        stringResource(Res.string.label_batch_rename_prefix),
        editor.prefix,
        appearance.headerFontSize,
        appearance.listFontSize,
    ) { editor.prefix = it }
    Spacer(modifier = Modifier.height(6.dp))
    LabeledField(
        stringResource(Res.string.label_batch_rename_suffix),
        editor.suffix,
        appearance.headerFontSize,
        appearance.listFontSize,
    ) { editor.suffix = it }
}

/**
 * 显示序号模式的模板与起始参数。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun CounterEditor(editor: BatchRenameEditorState) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    LabeledField(
        stringResource(Res.string.label_batch_rename_template),
        editor.counterTemplate,
        appearance.headerFontSize,
        appearance.listFontSize,
    ) { editor.counterTemplate = it }
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            LabeledField(
                stringResource(Res.string.label_batch_rename_counter_start),
                editor.counterStart,
                appearance.headerFontSize,
                appearance.listFontSize,
            ) { editor.counterStart = it }
        }
        Box(modifier = Modifier.weight(1f)) {
            LabeledField(
                stringResource(Res.string.label_batch_rename_counter_digits),
                editor.counterDigits,
                appearance.headerFontSize,
                appearance.listFontSize,
            ) { editor.counterDigits = it }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(Res.string.label_batch_rename_counter_ext_hint),
        fontSize = appearance.headerFontSize,
        color = palette.mutedForeground,
    )
}

/**
 * 显示大小写转换模式的选项。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun CaseChangeEditor(editor: BatchRenameEditorState) {
    val palette = LocalOnyxPalette.current
    val fontSize = LocalOnyxAppearance.current.headerFontSize
    val cases = listOf(
        CaseMode.UPPERCASE to Res.string.label_case_uppercase,
        CaseMode.LOWERCASE to Res.string.label_case_lowercase,
        CaseMode.TITLE_CASE to Res.string.label_case_titlecase,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        cases.forEach { (caseMode, labelResource) ->
            HoverTab(
                text = stringResource(labelResource),
                selected = editor.caseMode == caseMode,
                accent = palette.accent,
                foreground = palette.foreground,
                fontSize = fontSize,
                onClick = { editor.caseMode = caseMode },
            )
        }
    }
}

/**
 * 显示批量重命名的执行进度或最终结果。
 *
 * @param state 当前批量重命名执行状态。
 */
@Composable
private fun BatchRenameProgress(state: RootDialogState.BatchRename) {
    if (!state.executing && !state.completed && state.errorMessage == null) return
    val palette = LocalOnyxPalette.current
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
    )
    val progressBarColor = when {
        state.errorMessage != null -> palette.error
        state.completed -> palette.success
        else -> palette.accent
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(4.dp)
                .background(palette.outlineVariant, RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(animatedProgress).height(4.dp)
                    .background(progressBarColor, RoundedCornerShape(2.dp)),
            )
        }
        BatchRenameProgressText(state)
    }
    Spacer(modifier = Modifier.height(6.dp))
}

/**
 * 显示批量重命名进度的状态文字。
 *
 * @param state 当前批量重命名执行状态。
 */
@Composable
private fun BatchRenameProgressText(state: RootDialogState.BatchRename) {
    val palette = LocalOnyxPalette.current
    val message = when {
        state.errorMessage != null -> stringResource(
            Res.string.label_batch_rename_error_prefix,
            state.errorMessage.resolve(),
        )

        state.completed -> stringResource(
            Res.string.label_batch_rename_completed_detail,
            state.processedCount,
            state.entries.size,
        )

        else -> stringResource(
            Res.string.label_batch_rename_progress_detail,
            state.processedCount,
            state.entries.size,
            state.currentDetail,
        )
    }
    Text(
        text = message,
        fontSize = LocalOnyxAppearance.current.headerFontSize,
        color = when {
            state.errorMessage != null -> palette.error
            state.completed -> palette.success
            else -> palette.mutedForeground
        },
        maxLines = 1,
    )
}

/** 显示重命名前后名称的预览表头。 */
@Composable
private fun BatchRenamePreviewHeader() {
    val palette = LocalOnyxPalette.current
    val fontSize = LocalOnyxAppearance.current.headerFontSize
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(
            text = stringResource(Res.string.label_batch_rename_before),
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = palette.mutedForeground,
            modifier = Modifier.weight(1f),
        )
        Text("→", fontSize = fontSize, color = palette.mutedForeground, modifier = Modifier.width(24.dp))
        Text(
            text = stringResource(Res.string.label_batch_rename_after),
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = palette.mutedForeground,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 显示全部条目的重命名预览列表。
 *
 * @param renameResults 原文件及其目标名称。
 * @param modifier 预览区域布局修饰符。
 */
@Composable
private fun BatchRenamePreview(
    renameResults: List<Pair<VFile, String>>,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    Box(modifier = modifier) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(renameResults) { index, (entry, newName) ->
                    BatchRenamePreviewRow(index, entry, newName)
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 显示单个文件的重命名前后名称。
 *
 * @param index 条目索引，用于斑马纹背景。
 * @param entry 原文件条目。
 * @param newName 目标文件名。
 */
@Composable
private fun BatchRenamePreviewRow(index: Int, entry: VFile, newName: String) {
    val palette = LocalOnyxPalette.current
    val fontSize = LocalOnyxAppearance.current.listFontSize
    val changed = entry.name != newName
    val background = if (index % 2 == 1) palette.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.name, fontSize = fontSize, color = palette.foreground, maxLines = 1, modifier = Modifier.weight(1f))
        Text("→", fontSize = fontSize, color = palette.mutedForeground, modifier = Modifier.width(24.dp))
        Text(
            text = newName,
            fontSize = fontSize,
            color = if (changed) palette.accent else palette.mutedForeground,
            fontWeight = if (changed) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 显示预览统计与提交、取消或关闭动作。
 *
 * @param state 当前批量重命名执行状态。
 * @param editor 重命名规则编辑状态。
 * @param canConfirm 当前规则是否允许提交。
 * @param onConfirm 提交有效重命名结果的回调。
 * @param onDismiss 关闭窗口的回调。
 */
@Composable
private fun BatchRenameFooter(
    state: RootDialogState.BatchRename,
    editor: BatchRenameEditorState,
    canConfirm: Boolean,
    onConfirm: (List<Pair<VFile, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatchRenameStatistics(editor)
        Spacer(modifier = Modifier.weight(1f))
        if (state.completed || state.errorMessage != null) {
            HoverButton(
                text = stringResource(Res.string.action_close_window),
                emphasized = true,
                accent = palette.accent,
                surface = palette.surfaceVariant,
                foreground = palette.foreground,
                fontSize = appearance.listFontSize,
                onClick = onDismiss,
            )
        } else if (!state.executing) {
            BatchRenameEditActions(editor, canConfirm, onConfirm, onDismiss)
        }
    }
}

/**
 * 显示批量重命名文件数与变更数。
 *
 * @param editor 重命名规则编辑状态。
 */
@Composable
private fun BatchRenameStatistics(editor: BatchRenameEditorState) {
    val palette = LocalOnyxPalette.current
    val fontSize = LocalOnyxAppearance.current.headerFontSize
    Text(
        text = stringResource(Res.string.label_batch_rename_file_count, editor.entries.size),
        fontSize = fontSize,
        color = palette.mutedForeground,
    )
    if (editor.hasChanges) {
        Text("  ·  ", fontSize = fontSize, color = palette.mutedForeground)
        Text(
            text = stringResource(Res.string.label_batch_rename_changes_count, editor.changeCount),
            fontSize = fontSize,
            color = palette.accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 显示编辑状态下的取消与确认动作。
 *
 * @param editor 重命名规则编辑状态。
 * @param canConfirm 当前规则是否允许提交。
 * @param onConfirm 提交有效重命名结果的回调。
 * @param onDismiss 关闭窗口的回调。
 */
@Composable
private fun BatchRenameEditActions(
    editor: BatchRenameEditorState,
    canConfirm: Boolean,
    onConfirm: (List<Pair<VFile, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val fontSize = LocalOnyxAppearance.current.listFontSize
    HoverButton(
        text = stringResource(Res.string.action_close_window),
        emphasized = false,
        accent = palette.accent,
        surface = palette.surfaceVariant,
        foreground = palette.foreground,
        fontSize = fontSize,
        onClick = onDismiss,
    )
    Spacer(modifier = Modifier.width(8.dp))
    HoverButton(
        text = stringResource(Res.string.action_apply),
        emphasized = canConfirm,
        accent = palette.accent,
        surface = palette.surfaceVariant,
        foreground = palette.foreground,
        fontSize = fontSize,
        onClick = {
            if (canConfirm) {
                val validRenames = editor.renameResults.filter { (entry, newName) ->
                    entry.name != newName && newName.isNotBlank()
                }
                onConfirm(validRenames)
            }
        },
    )
}
