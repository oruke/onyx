package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.label_batch_rename_after
import onyx.composeapp.generated.resources.label_batch_rename_before
import onyx.composeapp.generated.resources.label_batch_rename_counter_digits
import onyx.composeapp.generated.resources.label_batch_rename_counter_start
import onyx.composeapp.generated.resources.label_batch_rename_find
import onyx.composeapp.generated.resources.label_batch_rename_mode_case
import onyx.composeapp.generated.resources.label_batch_rename_mode_counter
import onyx.composeapp.generated.resources.label_batch_rename_mode_find_replace
import onyx.composeapp.generated.resources.label_batch_rename_mode_prefix_suffix
import onyx.composeapp.generated.resources.label_batch_rename_prefix
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
    entries: List<VFile>,
    onConfirm: (List<Pair<VFile, String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current

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
                val ext = entry.name.substringAfterLast('.', "")
                val nameWithoutExt = if (ext.isNotEmpty()) entry.name.substringBeforeLast('.') else entry.name
                val newNameWithoutExt = when (mode) {
                    BatchRenameMode.FIND_REPLACE -> {
                        if (findText.isEmpty()) nameWithoutExt
                        else try {
                            if (useRegex) {
                                nameWithoutExt.replace(Regex(findText), replaceText)
                            } else {
                                nameWithoutExt.replace(findText, replaceText)
                            }
                        } catch (_: Exception) {
                            nameWithoutExt
                        }
                    }

                    BatchRenameMode.PREFIX_SUFFIX -> {
                        "$prefix$nameWithoutExt$suffix"
                    }

                    BatchRenameMode.COUNTER -> {
                        val start = counterStart.toIntOrNull() ?: 1
                        val digits = counterDigits.toIntOrNull()?.coerceIn(1, 10) ?: 3
                        val num = (start + index).toString().padStart(digits, '0')
                        "$counterTemplate$num"
                    }

                    BatchRenameMode.CASE_CHANGE -> {
                        when (caseMode) {
                            CaseMode.UPPERCASE -> nameWithoutExt.uppercase()
                            CaseMode.LOWERCASE -> nameWithoutExt.lowercase()
                            CaseMode.TITLE_CASE -> nameWithoutExt.split(" ", "_", "-").joinToString(" ") {
                                it.lowercase().replaceFirstChar { c -> c.uppercase() }
                            }
                        }
                    }
                }
                val newName = if (ext.isNotEmpty()) "$newNameWithoutExt.$ext" else newNameWithoutExt
                entry to newName
            }
        }
    }

    val hasChanges by remember(renameResults) {
        derivedStateOf {
            renameResults.any { (entry, newName) -> entry.name != newName && newName.isNotBlank() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .height(520.dp)
                .background(palette.floatingSurface, RoundedCornerShape(8.dp))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(8.dp))
                .padding(16.dp),
        ) {
            // ── 标题 ─────────────────────────────────────────────────
            Text(
                text = stringResource(Res.string.label_batch_rename_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ── 模式切换 Tab ─────────────────────────────────────────
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
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) palette.accent.copy(alpha = 0.15f) else Color.Transparent,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) palette.accent else Color.Transparent,
                                shape = RoundedCornerShape(4.dp),
                            )
                            .clickable { mode = m }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            fontSize = 11.sp,
                            color = if (selected) palette.accent else palette.foreground,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // ── 配置区域 ─────────────────────────────────────────────
            when (mode) {
                BatchRenameMode.FIND_REPLACE -> {
                    LabeledField(stringResource(Res.string.label_batch_rename_find), findText) { findText = it }
                    Spacer(modifier = Modifier.height(6.dp))
                    LabeledField(stringResource(Res.string.label_batch_rename_replace), replaceText) { replaceText = it }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (useRegex) palette.accent.copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(4.dp),
                                )
                                .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                                .clickable { useRegex = !useRegex }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.label_batch_rename_regex),
                                fontSize = 11.sp,
                                color = if (useRegex) palette.accent else palette.mutedForeground,
                            )
                        }
                    }
                }

                BatchRenameMode.PREFIX_SUFFIX -> {
                    LabeledField(stringResource(Res.string.label_batch_rename_prefix), prefix) { prefix = it }
                    Spacer(modifier = Modifier.height(6.dp))
                    LabeledField(stringResource(Res.string.label_batch_rename_suffix), suffix) { suffix = it }
                }

                BatchRenameMode.COUNTER -> {
                    LabeledField(stringResource(Res.string.label_batch_rename_template), counterTemplate) { counterTemplate = it }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            LabeledField(stringResource(Res.string.label_batch_rename_counter_start), counterStart) { counterStart = it }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            LabeledField(stringResource(Res.string.label_batch_rename_counter_digits), counterDigits) { counterDigits = it }
                        }
                    }
                }

                BatchRenameMode.CASE_CHANGE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val cases = listOf(
                            CaseMode.UPPERCASE to Res.string.label_case_uppercase,
                            CaseMode.LOWERCASE to Res.string.label_case_lowercase,
                            CaseMode.TITLE_CASE to Res.string.label_case_titlecase,
                        )
                        cases.forEach { (cm, labelRes) ->
                            val selected = caseMode == cm
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (selected) palette.accent.copy(alpha = 0.15f) else Color.Transparent,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .border(
                                        width = if (selected) 1.dp else 0.dp,
                                        color = if (selected) palette.accent else palette.outlineVariant,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .clickable { caseMode = cm }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    fontSize = 11.sp,
                                    color = if (selected) palette.accent else palette.foreground,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))

            // ── 预览列表 ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.label_batch_rename_before),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "→",
                    fontSize = 11.sp,
                    color = palette.mutedForeground,
                    modifier = Modifier.width(24.dp),
                )
                Text(
                    text = stringResource(Res.string.label_batch_rename_after),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                itemsIndexed(renameResults) { _, (entry, newName) ->
                    val changed = entry.name != newName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.name,
                            fontSize = 11.sp,
                            color = palette.foreground,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "→",
                            fontSize = 11.sp,
                            color = palette.mutedForeground,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(
                            text = newName,
                            fontSize = 11.sp,
                            color = if (changed) palette.accent else palette.mutedForeground,
                            fontWeight = if (changed) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── 底部按钮 ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${entries.size} files",
                    fontSize = 11.sp,
                    color = palette.mutedForeground,
                )
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.action_cancel_task),
                        fontSize = 12.sp,
                        color = palette.foreground,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(
                            if (hasChanges) palette.accent else palette.disabledForeground,
                            RoundedCornerShape(4.dp),
                        )
                        .clickable(enabled = hasChanges) {
                            val filteredMap = renameResults.filter { (entry, newName) ->
                                entry.name != newName && newName.isNotBlank()
                            }
                            onConfirm(filteredMap)
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.action_batch_rename),
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

// ── 工具组件 ────────────────────────────────────────────────────────────

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = palette.mutedForeground,
        )
        Spacer(modifier = Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            textStyle = TextStyle(color = palette.foreground, fontSize = 12.sp),
            singleLine = true,
            cursorBrush = SolidColor(palette.accent),
        )
    }
}
