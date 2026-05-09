package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_close_menu
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
import onyx.composeapp.generated.resources.label_batch_rename_mode_case
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
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

// ── 批量重命名模式 ─────────────────────────────────────────────────────

private enum class BatchRenameMode {
    FIND_REPLACE,
    PREFIX_SUFFIX,
    COUNTER,
    CASE_CHANGE,
}

private enum class CaseMode {
    UPPERCASE,
    LOWERCASE,
    TITLE_CASE,
}

// ── 对话框 ──────────────────────────────────────────────────────────────

@Composable
internal fun BatchRenameDialog(
    state: RootDialogState.BatchRename,
    onConfirm: (List<Pair<VFile, String>>) -> Unit,
    onDismiss: () -> Unit,
    initialWidth: Int = 800,
    initialHeight: Int = 620,
    onWindowSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) {
    val entries = state.entries
    var mode by remember { mutableStateOf(BatchRenameMode.FIND_REPLACE) }

    // 查找替换
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }

    // 前缀/后缀
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }

    // 序号
    var counterTemplate by remember { mutableStateOf("IMG_") }
    var counterStart by remember { mutableStateOf("1") }
    var counterDigits by remember { mutableStateOf("3") }

    // 大小写
    var caseMode by remember { mutableStateOf(CaseMode.LOWERCASE) }

    val renameResults by remember(mode, findText, replaceText, useRegex, prefix, suffix, counterTemplate, counterStart, counterDigits, caseMode) {
        derivedStateOf {
            entries.mapIndexed { index, entry ->
                val newName = when (mode) {
                    // 查找替换：在完整文件名上操作
                    BatchRenameMode.FIND_REPLACE -> {
                        if (findText.isEmpty()) entry.name
                        else try {
                            if (useRegex) {
                                entry.name.replace(Regex(findText), replaceText)
                            } else {
                                entry.name.replace(findText, replaceText)
                            }
                        } catch (_: Exception) {
                            entry.name
                        }
                    }

                    // 前缀/后缀、序号、大小写：只操作文件名部分，保留扩展名
                    BatchRenameMode.PREFIX_SUFFIX -> {
                        val ext = entry.name.substringAfterLast('.', "")
                        val nameWithoutExt = if (ext.isNotEmpty()) entry.name.substringBeforeLast('.') else entry.name
                        val result = "$prefix$nameWithoutExt$suffix"
                        if (ext.isNotEmpty()) "$result.$ext" else result
                    }

                    BatchRenameMode.COUNTER -> {
                        val ext = entry.name.substringAfterLast('.', "")
                        val start = counterStart.toIntOrNull() ?: 1
                        val digits = counterDigits.toIntOrNull()?.coerceIn(1, 10) ?: 3
                        val num = (start + index).toString().padStart(digits, '0')
                        val result = "$counterTemplate$num"
                        if (ext.isNotEmpty()) "$result.$ext" else result
                    }

                    BatchRenameMode.CASE_CHANGE -> {
                        val ext = entry.name.substringAfterLast('.', "")
                        val nameWithoutExt = if (ext.isNotEmpty()) entry.name.substringBeforeLast('.') else entry.name
                        val result = when (caseMode) {
                            CaseMode.UPPERCASE -> nameWithoutExt.uppercase()
                            CaseMode.LOWERCASE -> nameWithoutExt.lowercase()
                            CaseMode.TITLE_CASE -> nameWithoutExt.split(" ", "_", "-").joinToString(" ") {
                                it.lowercase().replaceFirstChar { c -> c.uppercase() }
                            }
                        }
                        if (ext.isNotEmpty()) "$result.$ext" else result
                    }
                }
                entry to newName
            }
        }
    }

    val changeCount by remember(renameResults) {
        derivedStateOf {
            renameResults.count { (entry, newName) -> entry.name != newName && newName.isNotBlank() }
        }
    }

    val hasChanges by remember(changeCount) {
        derivedStateOf { changeCount > 0 }
    }

    val title = stringResource(Res.string.label_batch_rename_title)

    val dialogState = rememberDialogState(width = initialWidth.dp, height = initialHeight.dp)

    LaunchedEffect(dialogState) {
        snapshotFlow { dialogState.size }
            .collect { size ->
                val w = size.width; val h = size.height
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
        window.minimumSize = java.awt.Dimension(640, 480)
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            val palette = LocalOnyxPalette.current
            val appearance = LocalOnyxAppearance.current
            val bodyFontSize = appearance.listFontSize   // 主体列表字号
            val labelFontSize = appearance.headerFontSize // 表头/标签字号

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.appBackground)
                    .padding(14.dp),
            ) {
                // ── 标题 ─────────────────────────────────────────────────
                Text(
                    text = title,
                    fontSize = (bodyFontSize.value + 1).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.foreground,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ── 模式切换 Tab（执行中/完成后禁用）─────────────────────
                if (!state.executing && !state.completed) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val modes = listOf(
                            BatchRenameMode.FIND_REPLACE to Res.string.label_batch_rename_mode_find_replace,
                            BatchRenameMode.PREFIX_SUFFIX to Res.string.label_batch_rename_mode_prefix_suffix,
                            BatchRenameMode.COUNTER to Res.string.label_batch_rename_mode_counter,
                            BatchRenameMode.CASE_CHANGE to Res.string.label_batch_rename_mode_case,
                        )
                        modes.forEach { (m, labelRes) ->
                            val selected = mode == m
                            HoverTab(
                                text = stringResource(labelRes),
                                selected = selected,
                                accent = palette.accent,
                                foreground = palette.foreground,
                                fontSize = labelFontSize,
                                onClick = { mode = m },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── 配置区域 ─────────────────────────────────────────
                    when (mode) {
                        BatchRenameMode.FIND_REPLACE -> {
                            LabeledField(stringResource(Res.string.label_batch_rename_find), findText, labelFontSize, bodyFontSize) { findText = it }
                            Spacer(modifier = Modifier.height(6.dp))
                            LabeledField(stringResource(Res.string.label_batch_rename_replace), replaceText, labelFontSize, bodyFontSize) { replaceText = it }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HoverTab(
                                    text = stringResource(Res.string.label_batch_rename_regex),
                                    selected = useRegex,
                                    accent = palette.accent,
                                    foreground = palette.mutedForeground,
                                    fontSize = labelFontSize,
                                    onClick = { useRegex = !useRegex },
                                )
                            }
                        }

                        BatchRenameMode.PREFIX_SUFFIX -> {
                            LabeledField(stringResource(Res.string.label_batch_rename_prefix), prefix, labelFontSize, bodyFontSize) { prefix = it }
                            Spacer(modifier = Modifier.height(6.dp))
                            LabeledField(stringResource(Res.string.label_batch_rename_suffix), suffix, labelFontSize, bodyFontSize) { suffix = it }
                        }

                        BatchRenameMode.COUNTER -> {
                            LabeledField(stringResource(Res.string.label_batch_rename_template), counterTemplate, labelFontSize, bodyFontSize) { counterTemplate = it }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(modifier = Modifier.weight(1f)) {
                                    LabeledField(stringResource(Res.string.label_batch_rename_counter_start), counterStart, labelFontSize, bodyFontSize) { counterStart = it }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    LabeledField(stringResource(Res.string.label_batch_rename_counter_digits), counterDigits, labelFontSize, bodyFontSize) { counterDigits = it }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.label_batch_rename_counter_ext_hint),
                                fontSize = labelFontSize,
                                color = palette.mutedForeground,
                            )
                        }

                        BatchRenameMode.CASE_CHANGE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val cases = listOf(
                                    CaseMode.UPPERCASE to Res.string.label_case_uppercase,
                                    CaseMode.LOWERCASE to Res.string.label_case_lowercase,
                                    CaseMode.TITLE_CASE to Res.string.label_case_titlecase,
                                )
                                cases.forEach { (cm, labelRes) ->
                                    HoverTab(
                                        text = stringResource(labelRes),
                                        selected = caseMode == cm,
                                        accent = palette.accent,
                                        foreground = palette.foreground,
                                        fontSize = labelFontSize,
                                        onClick = { caseMode = cm },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── 进度条（执行中显示）──────────────────────────────────
                if (state.executing || state.completed || state.errorMessage != null) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progress.coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 300),
                    )
                    val progressBarColor = when {
                        state.errorMessage != null -> Color(0xFFD74E4E)
                        state.completed -> Color(0xFF4CAF50)
                        else -> palette.accent
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(palette.outlineVariant, RoundedCornerShape(2.dp)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedProgress)
                                    .height(4.dp)
                                    .background(progressBarColor, RoundedCornerShape(2.dp)),
                            )
                        }
                        Text(
                            text = when {
                                state.errorMessage != null -> stringResource(
                                    Res.string.label_batch_rename_error_prefix,
                                    state.errorMessage.resolve(),
                                )
                                state.completed -> stringResource(
                                    Res.string.label_batch_rename_completed_detail,
                                    state.processedCount,
                                    entries.size,
                                )
                                else -> stringResource(
                                    Res.string.label_batch_rename_progress_detail,
                                    state.processedCount,
                                    entries.size,
                                    state.currentDetail,
                                )
                            },
                            fontSize = labelFontSize,
                            color = when {
                                state.errorMessage != null -> Color(0xFFD74E4E)
                                state.completed -> Color(0xFF4CAF50)
                                else -> palette.mutedForeground
                            },
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(4.dp))

                // ── 预览列表表头 ─────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.label_batch_rename_before),
                        fontSize = labelFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.mutedForeground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "→",
                        fontSize = labelFontSize,
                        color = palette.mutedForeground,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = stringResource(Res.string.label_batch_rename_after),
                        fontSize = labelFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.mutedForeground,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── 预览列表（文本可选复制 + 斑马纹 + 滚动条）────────────
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val listState = rememberLazyListState()
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(renameResults) { index, (entry, newName) ->
                                val changed = entry.name != newName
                                val zebraBackground = if (index % 2 == 1) {
                                    palette.surfaceVariant.copy(alpha = 0.5f)
                                } else {
                                    Color.Transparent
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(zebraBackground)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.name,
                                        fontSize = bodyFontSize,
                                        color = palette.foreground,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "→",
                                        fontSize = bodyFontSize,
                                        color = palette.mutedForeground,
                                        modifier = Modifier.width(24.dp),
                                    )
                                    Text(
                                        text = newName,
                                        fontSize = bodyFontSize,
                                        color = if (changed) palette.accent else palette.mutedForeground,
                                        fontWeight = if (changed) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }

                // ── 底部按钮（固定在右下角）─────────────────────────────
                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧统计信息：文件总数 + 变更数
                    Text(
                        text = stringResource(Res.string.label_batch_rename_file_count, entries.size),
                        fontSize = labelFontSize,
                        color = palette.mutedForeground,
                    )
                    if (hasChanges) {
                        Text(
                            text = "  ·  ",
                            fontSize = labelFontSize,
                            color = palette.mutedForeground,
                        )
                        Text(
                            text = stringResource(Res.string.label_batch_rename_changes_count, changeCount),
                            fontSize = labelFontSize,
                            color = palette.accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    when {
                        state.executing -> {
                            // 执行中：只展示进度，不允许关闭
                        }
                        state.completed || state.errorMessage != null -> {
                            HoverButton(
                                text = stringResource(Res.string.action_close_menu),
                                emphasized = true,
                                accent = palette.accent,
                                surface = palette.surfaceVariant,
                                foreground = palette.foreground,
                                fontSize = bodyFontSize,
                                onClick = onDismiss,
                            )
                        }
                        else -> {
                            HoverButton(
                                text = stringResource(Res.string.action_cancel_task),
                                emphasized = false,
                                accent = palette.accent,
                                surface = palette.surfaceVariant,
                                foreground = palette.foreground,
                                fontSize = bodyFontSize,
                                onClick = onDismiss,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            HoverButton(
                                text = stringResource(Res.string.action_batch_rename),
                                emphasized = hasChanges,
                                accent = palette.accent,
                                surface = palette.surfaceVariant,
                                foreground = palette.foreground,
                                fontSize = bodyFontSize,
                                onClick = {
                                    if (hasChanges) {
                                        val filtered = renameResults.filter { (entry, newName) ->
                                            entry.name != newName && newName.isNotBlank()
                                        }
                                        onConfirm(filtered)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 带 Hover 动画的 Tab ─────────────────────────────────────────────────

@Composable
private fun HoverTab(
    text: String,
    selected: Boolean,
    accent: Color,
    foreground: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> accent.copy(alpha = 0.15f)
            isHovered -> accent.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> accent
            isHovered -> accent.copy(alpha = 0.4f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> accent
            isHovered -> accent.copy(alpha = 0.8f)
            else -> foreground
        },
        animationSpec = tween(150),
    )

    Box(
        modifier = Modifier
            .hoverable(interactionSource)
            .background(bgColor, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = textColor,
        )
    }
}

// ── 带 Hover 动画的按钮 ─────────────────────────────────────────────────

@Composable
private fun HoverButton(
    text: String,
    emphasized: Boolean,
    accent: Color,
    surface: Color,
    foreground: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val restBg = if (emphasized) accent else surface
    val hoverBg = if (emphasized) accent.copy(alpha = 0.85f) else accent.copy(alpha = 0.12f)
    val contentColor = if (emphasized) Color.White else foreground

    val bgColor by animateColorAsState(
        targetValue = if (isHovered) hoverBg else restBg,
        animationSpec = tween(150),
    )

    Box(
        modifier = Modifier
            .hoverable(interactionSource)
            .background(bgColor, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = contentColor,
        )
    }
}

// ── 工具组件 ────────────────────────────────────────────────────────────

@Composable
private fun LabeledField(
    label: String,
    value: String,
    labelFontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    inputFontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            fontSize = labelFontSize,
            color = palette.mutedForeground,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            textStyle = TextStyle(color = palette.foreground, fontSize = inputFontSize),
            singleLine = true,
            cursorBrush = SolidColor(palette.accent),
        )
    }
}
