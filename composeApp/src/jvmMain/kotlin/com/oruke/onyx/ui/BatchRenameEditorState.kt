package com.oruke.onyx.ui

import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenameEditMode
import com.oruke.onyx.core.model.BatchRenameEditCaseMode
import com.oruke.onyx.core.model.BatchRenameCaseMode

/**
 * 将现有表单控件适配到组件草稿；这里只读取状态和发送事件，不计算业务名称。
 * @param state 当前对话框的组件快照。
 * @param onChange 更新组件草稿的事件入口。
 */
internal class BatchRenameEditorState(
    /** 当前组件快照。 */
    private val state: RootDialogState.BatchRename,
    /** 组件草稿更新事件。 */
    private val onChange: (BatchRenameDraft) -> Unit,
) {
    /** 原始条目。 */
    val entries get() = state.entries

    /** 组件计算后的整批名称。 */
    val renameResults get() = state.renameResults

    /** 组件校验错误。 */
    val validationMessage get() = state.validationMessage

    /** 当前需要变更的名称数量。 */
    val changeCount get() = renameResults.count { (entry, name) -> entry.name != name && name.isNotBlank() }

    /** 是否有有效变更。 */
    val hasChanges get() = changeCount > 0

    /** 当前编辑模式。 */
    var mode: BatchRenameEditMode
        get() = state.draft.mode
        set(value) = onChange(state.draft.copy(mode = value))

    /** 查找文本。 */
    var findText: String
        get() = state.draft.findText
        set(value) = onChange(state.draft.copy(findText = value))

    /** 替换文本。 */
    var replaceText: String
        get() = state.draft.replaceText
        set(value) = onChange(state.draft.copy(replaceText = value))

    /** 正则开关。 */
    var useRegex: Boolean
        get() = state.draft.useRegex
        set(value) = onChange(state.draft.copy(useRegex = value))

    /** 基础名称前缀。 */
    var prefix: String
        get() = state.draft.prefix
        set(value) = onChange(state.draft.copy(prefix = value))

    /** 基础名称后缀。 */
    var suffix: String
        get() = state.draft.suffix
        set(value) = onChange(state.draft.copy(suffix = value))

    /** 序号模板。 */
    var counterTemplate: String
        get() = state.draft.counterTemplate
        set(value) = onChange(state.draft.copy(counterTemplate = value))

    /** 序号起点。 */
    var counterStart: String
        get() = state.draft.counterStart
        set(value) = onChange(state.draft.copy(counterStart = value))

    /** 序号位数。 */
    var counterDigits: String
        get() = state.draft.counterDigits
        set(value) = onChange(state.draft.copy(counterDigits = value))

    /** 基础名称大小写。 */
    var caseMode: BatchRenameEditCaseMode
        get() = state.draft.caseMode
        set(value) = onChange(state.draft.copy(caseMode = value))

    /** 模板输出名称的大小写。 */
    var templateCaseMode: BatchRenameCaseMode
        get() = state.draft.templateCaseMode
        set(value) = onChange(state.draft.copy(templateCaseMode = value))
}
