package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile

/** 一个独立搜索会话的查询、结果、运行状态与交互状态。 */
internal data class SearchPanelState(
    /** 当前搜索窗口或浮层是否显示。 */
    val visible: Boolean = false,
    /** 当前会话的显示形态；完整搜索与快速打开各自持有实例。 */
    val mode: SearchPanelMode = SearchPanelMode.SEARCH_PANEL,
    /** 搜索结果返回的来源面板。 */
    val paneId: PaneId = PaneId.PRIMARY,
    /** 显式绑定的当前目录搜索根，重新打开窗口不会自动改变。 */
    val rootLocation: String = "",
    /** 当前查询表达式。 */
    val query: String = "",
    /** 当前搜索根范围。 */
    val scope: SearchScope = SearchScope.CURRENT_DIRECTORY,
    /** 执行时转换为统一搜索语法的结构化条件。 */
    val filters: SearchFilters = SearchFilters(),
    /** 当前搜索执行状态。 */
    val status: SearchStatus = SearchStatus.IDLE,
    /** 已发布的完整结果快照。 */
    val results: List<VFile> = emptyList(),
    /** 已扫描条目数量。 */
    val scannedEntryCount: Int = 0,
    /** 搜索是否达到结果数量限制。 */
    val limitReached: Boolean = false,
    /** 可展示的搜索失败原因。 */
    val error: I18nMessage? = null,
    /** 每次显示时递增，用于唤起已经打开或最小化的窗口。 */
    val activationRequest: Long = 0L,
    /** 当前选中结果索引，空结果使用零。 */
    val selectedIndex: Int = 0,
    /** 高级筛选条件区域是否展开。 */
    val advancedFiltersExpanded: Boolean = false,
    /** 是否允许扫描网络位置，默认排除显式网络位置。 */
    val includeRemoteLocations: Boolean = false,
)

/** 搜索会话的展示形态。 */
internal enum class SearchPanelMode {
    /** 一次性快速打开浮层，独立于完整搜索会话。 */
    QUICK_OPEN,
    /** 可保持后台搜索的独立窗口。 */
    SEARCH_PANEL,
}

/** 执行时转换为统一搜索语法的筛选条件。 */
internal data class SearchFilters(
    /** 允许的条目类型，空集合表示不限。 */
    val types: Set<SearchFilterFileType> = emptySet(),
    /** 最早修改时间，单位 epoch millis。 */
    val modifiedSinceEpochMillis: Long? = null,
    /** 最小文件大小，单位字节。 */
    val minSizeBytes: Long? = null,
    /** 最大文件大小，单位字节。 */
    val maxSizeBytes: Long? = null,
    /** 是否检索文件内容。 */
    val searchInContent: Boolean = false,
)

/** 结构化搜索允许的条目类型。 */
internal enum class SearchFilterFileType { FILE, DIRECTORY }

/** 搜索会话的执行状态；停止后保留已发布结果。 */
internal enum class SearchStatus { IDLE, RUNNING, COMPLETED, FAILED, CANCELLED }
