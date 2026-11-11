package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 计算内联编辑初始选区范围。
 *
 * @param name 当前编辑的条目名称。
 * @param selectWithoutExtension 是否排除文件扩展名选中。
 * @return 初始选区文本范围。
 */
internal fun initialInlineEditSelection(name: String, selectWithoutExtension: Boolean): TextRange {
    if (name.isEmpty()) return TextRange.Zero
    val extensionDotIndex = name.lastIndexOf('.')
    val selectionEnd = if (selectWithoutExtension && extensionDotIndex in 1 until name.lastIndex) {
        extensionDotIndex
    } else {
        name.length
    }
    return TextRange(start = 0, end = selectionEnd)
}

/**
 * 记忆并同步内联编辑输入框的状态绑定。
 *
 * @param draftName 外部传入的草稿名称。
 * @param selectWithoutExtension 是否排除扩展名初始选中。
 * @param onUpdateDraft 草稿更新回调。
 * @return 由当前输入状态与输入变更回调组成的二元组。
 */
@Composable
internal fun rememberInlineEditFieldBinding(
    draftName: String,
    selectWithoutExtension: Boolean,
    onUpdateDraft: (String) -> Unit,
): Pair<TextFieldValue, (TextFieldValue) -> Unit> {
    val currentOnUpdateDraft by rememberUpdatedState(onUpdateDraft)
    var lastExternalDraft by remember(selectWithoutExtension) { mutableStateOf(draftName) }
    var textFieldValue by remember(selectWithoutExtension) {
        mutableStateOf(
            TextFieldValue(
                text = draftName,
                selection = initialInlineEditSelection(draftName, selectWithoutExtension),
            ),
        )
    }

    if (draftName != lastExternalDraft) {
        lastExternalDraft = draftName
        if (draftName != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = draftName,
                selection = initialInlineEditSelection(draftName, selectWithoutExtension),
            )
        }
    }

    val onValueChange: (TextFieldValue) -> Unit = { nextValue ->
        val textChanged = nextValue.text != textFieldValue.text
        textFieldValue = nextValue
        if (textChanged) {
            currentOnUpdateDraft(nextValue.text)
        }
    }

    return textFieldValue to onValueChange
}
