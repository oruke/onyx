package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenameEditCaseMode
import com.oruke.onyx.core.model.BatchRenameEditMode
import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 批量重命名预览冲突类型。
 */
enum class BatchRenameConflictKind {
    /** 目标名称为空、为 `.`/`..` 或包含路径分隔符/非法字符。 */
    INVALID_NAME,

    /** 同一父目录下有多个条目映射到相同目标名称。 */
    DUPLICATE_TARGET,

    /** 目标名称与同目录内未参与变更的既有文件或目录重名。 */
    EXISTING_SIBLING,
}

/** 将组件草稿转换为名称预览，统一目录、扩展名、数字参数和目标碰撞校验。 */
object BatchRenameDraftPlanner {
    /**
     * 在任何文件变更前计算并校验整批名称。
     * @param entries 按界面顺序排列的原始条目。
     * @param draft 用户草稿。
     * @return 完整条目与新名称，非法参数返回本地化异常。
     */
    fun preview(entries: List<VFile>, draft: BatchRenameDraft): Result<List<Pair<VFile, String>>> = runCatching {
        val counter = draft.validatedCounterParameters(entries.size)
        entries.mapIndexed { index, entry ->
            val name = when (draft.mode) {
                BatchRenameEditMode.FIND_REPLACE -> BatchRenameNameTransformations.applyFindReplace(
                    entry.name, draft.findText, draft.replaceText, draft.useRegex,
                ).getOrElse { throw LocalizedException(I18nMessage(MessageKey.MSG_BATCH_RENAME_INVALID_PATTERN), it) }

                BatchRenameEditMode.PREFIX_SUFFIX -> entry.transformBaseName { "${draft.prefix}$it${draft.suffix}" }
                BatchRenameEditMode.COUNTER -> entry.transformBaseName {
                    val parameters = requireNotNull(counter)
                    draft.counterTemplate + (parameters.start + index).toString().padStart(parameters.digits, '0')
                }

                BatchRenameEditMode.CASE_CHANGE -> entry.transformBaseName { base ->
                    when (draft.caseMode) {
                        BatchRenameEditCaseMode.UPPERCASE -> base.uppercase()
                        BatchRenameEditCaseMode.LOWERCASE -> base.lowercase()
                        BatchRenameEditCaseMode.TITLE_CASE -> base.split(" ", "_", "-").joinToString(" ") { word ->
                            word.lowercase().replaceFirstChar { it.uppercase() }
                        }
                    }
                }

                BatchRenameEditMode.TEMPLATE -> BatchRenameTemplateRenderer.render(
                    entry, index,
                    BatchRenamePreset(
                        id = "", name = "", pattern = draft.findText, replacement = draft.replaceText,
                        useRegex = draft.useRegex, startIndex = requireNotNull(counter).start.toInt(),
                        padding = counter.digits, caseMode = draft.templateCaseMode,
                    ),
                )
            }
            entry to name
        }
    }

    /**
     * 检测批量重命名预览结果中的非法名称、批次内重复目标名及与目录既有兄弟条目的碰撞。
     *
     * 链式平移（如 `1.txt -> 2.txt, 2.txt -> 3.txt`）或循环互换（`a.txt <-> b.txt`）属于可由两阶段中转
     * 安全执行的合法方案，不会被误判为与既有兄弟条目冲突。
     *
     * @param renameResults 原条目与预览目标名列表。
     * @param siblingNamesByParent 按父目录分组的既有全部条目名称（包含未选中的兄弟条目）。
     * @param caseInsensitive 是否按大小写不敏感规则比较重名（Windows 本地与 SMB 为 `true`）。
     * @return 冲突条目 ID 到冲突原因的映射；无冲突时为空映射。
     */
    fun findConflicts(
        renameResults: List<Pair<VFile, String>>,
        siblingNamesByParent: Map<String, Set<String>> = emptyMap(),
        caseInsensitive: Boolean = true,
    ): Map<String, BatchRenameConflictKind> {
        if (renameResults.isEmpty()) return emptyMap()
        val conflicts = linkedMapOf<String, BatchRenameConflictKind>()
        val normalize: (String) -> String = { raw -> if (caseInsensitive) raw.lowercase() else raw }

        renameResults.groupBy { (entry, _) -> entry.parentLocation.orEmpty() }.forEach { (parent, group) ->
            // 1. 校验非法名称。
            group.forEach { (entry, targetName) ->
                if (!isValidTargetFileName(targetName)) {
                    conflicts[entry.id] = BatchRenameConflictKind.INVALID_NAME
                }
            }

            // 2. 检测批次内目标名称重复（包括某条目重命名为另一未改动条目的名字）。
            val targetBuckets = group.groupBy { (_, targetName) -> normalize(targetName.trim()) }
            targetBuckets.values.forEach { bucket ->
                if (bucket.size > 1) {
                    bucket.forEach { (entry, _) ->
                        conflicts.putIfAbsent(entry.id, BatchRenameConflictKind.DUPLICATE_TARGET)
                    }
                }
            }

            // 3. 检测与同目录内未参与本次重命名的既有兄弟条目冲突。
            val allSiblings = siblingNamesByParent[parent].orEmpty()
            if (allSiblings.isNotEmpty()) {
                val participatingSourceKeys = group.map { (entry, _) -> normalize(entry.name) }.toSet()
                val untouchedSiblingKeys = allSiblings
                    .map(normalize)
                    .filterNot { key -> key in participatingSourceKeys }
                    .toSet()
                group.forEach { (entry, targetName) ->
                    val trimmed = targetName.trim()
                    if (trimmed != entry.name && normalize(trimmed) in untouchedSiblingKeys) {
                        conflicts.putIfAbsent(entry.id, BatchRenameConflictKind.EXISTING_SIBLING)
                    }
                }
            }
        }
        return conflicts
    }

    /** 校验单个目标文件名是否合法（非空、非 `.`/`..`、不含路径分隔符与控制字符）。 */
    private fun isValidTargetFileName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
        return trimmed.none { ch -> ch == '/' || ch == '\\' || ch.code < 0x20 }
    }
}

/**
 * 转换基础名称，文件保留真实扩展名，目录和隐藏文件不拆出伪扩展名。
 * @param transform 基础名称转换。
 * @return 转换后的完整名称。
 */
private fun VFile.transformBaseName(transform: (String) -> String): String {
    val dot = name.lastIndexOf('.')
    val hasExtension = kind == VFileKind.FILE && dot > 0 && dot < name.lastIndex
    return if (hasExtension) transform(name.substring(0, dot)) + name.substring(dot) else transform(name)
}
