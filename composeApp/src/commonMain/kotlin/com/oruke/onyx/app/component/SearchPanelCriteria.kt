package com.oruke.onyx.app.component

/**
 * 判断当前搜索状态是否具备可执行的查询条件。
 *
 * 内容检索开关必须与关键词组合才有意义；同时选择文件与目录等同于未指定类型，
 * 因此两者都不能单独触发一次搜索。
 *
 * @return 至少存在一个可转换为底层搜索语法的条件时返回 `true`。
 */
internal fun SearchPanelState.hasExecutableSearchCriteria(): Boolean {
    return query.isNotBlank() ||
        filters.types.size == 1 ||
        filters.modifiedSinceEpochMillis != null ||
        filters.minSizeBytes != null ||
        filters.maxSizeBytes != null
}

/**
 * 判断搜索面板是否应优先展示历史记录。
 *
 * 仅在空闲且没有可执行条件时展示历史，避免纯结构化筛选已执行后仍被历史区域遮挡结果。
 *
 * @return 当前状态应展示搜索历史时返回 `true`。
 */
internal fun SearchPanelState.shouldShowSearchHistory(): Boolean {
    return status == SearchStatus.IDLE && !hasExecutableSearchCriteria()
}
