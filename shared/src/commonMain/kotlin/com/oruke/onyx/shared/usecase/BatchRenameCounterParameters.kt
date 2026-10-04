package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenameEditMode
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey

/** 已按整批条目数量校验的序号参数，用于序号和模板两种重命名模式。 */
internal data class BatchRenameCounterParameters(
    /** 首个条目的序号。 */
    val start: Long,
    /** 序号的最小补零宽度，模板允许零位。 */
    val digits: Int,
)

/** 批量序号允许的最大补零宽度。 */
private const val MAX_COUNTER_DIGITS = 10

/**
 * 解析并校验序号输入，确保整批最后一个序号也不会溢出。
 * @param entryCount 本批条目数量。
 * @return 已校验参数；不使用序号的模式返回 null，非法输入抛出本地化异常。
 */
internal fun BatchRenameDraft.validatedCounterParameters(entryCount: Int): BatchRenameCounterParameters? {
    if (mode != BatchRenameEditMode.COUNTER && mode != BatchRenameEditMode.TEMPLATE) return null
    val start = counterStart.toLongOrNull()
    val digits = counterDigits.toIntOrNull()
    val maximumIndex = if (mode == BatchRenameEditMode.TEMPLATE) Int.MAX_VALUE.toLong() else Long.MAX_VALUE
    // 起始序号本身对应第一个条目，只有后续条目增加偏移；模板渲染使用 Int 序号。
    val lastOffset = (entryCount - 1).coerceAtLeast(0).toLong()
    val minimumDigits = if (mode == BatchRenameEditMode.TEMPLATE) 0 else 1
    if (start == null || start < 0 || start > maximumIndex - lastOffset) {
        throw LocalizedException(I18nMessage(MessageKey.MSG_BATCH_RENAME_INVALID_COUNTER))
    }
    if (digits == null || digits !in minimumDigits..MAX_COUNTER_DIGITS) {
        throw LocalizedException(I18nMessage(MessageKey.MSG_BATCH_RENAME_INVALID_COUNTER))
    }
    return BatchRenameCounterParameters(start, digits)
}
