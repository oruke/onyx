package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings

/**
 * 清理设置中的空值和重复项，用户主动保存的收藏和连接不按数量截断。
 *
 * @return 可持久化的规范化设置。
 */
internal fun OnyxSettings.sanitizeRootSettings(): OnyxSettings {
    return copy(
        uiScale = uiScale.coerceIn(MIN_UI_SCALE_PERCENT, MAX_UI_SCALE_PERCENT),
        favoriteLocations = favoriteLocations
            .map { favorite -> favorite.trim() }
            .filter { favorite -> favorite.isNotEmpty() }
            .distinct(),
        recentLocations = recentLocations
            .map { recent -> recent.trim() }
            .filter { recent -> recent.isNotEmpty() }
            .distinct()
            .take(MAX_RECENT_LOCATIONS),
        remoteConnections = remoteConnections
            .map { connection ->
                connection.copy(
                    name = connection.name.trim(),
                    location = connection.location.trim(),
                    username = connection.username.trim(),
                    domain = connection.domain.trim(),
                    s3Config = connection.s3Config.copy(
                        endpoint = connection.s3Config.endpoint.trim(),
                        region = connection.s3Config.region.trim(),
                    ),
                )
            }
            .filter { connection -> connection.name.isNotEmpty() && connection.location.isNotEmpty() }
            .distinctBy { connection -> connection.id },
        searchHistory = searchHistory
            .map { query -> query.trim() }
            .filter { query -> query.isNotEmpty() }
            .distinct()
            .take(MAX_SEARCH_HISTORY),
    )
}

/**
 * 将一次成功执行的查询记录到搜索历史，最新在前并去重。
 *
 * @param query 成功执行的查询文本。
 * @return 记录后的设置；无变化时返回原实例。
 */
internal fun OnyxSettings.recordSearchQuery(query: String): OnyxSettings {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    val next = buildList {
        add(trimmed)
        addAll(searchHistory.filterNot { candidate -> candidate == trimmed })
    }.take(MAX_SEARCH_HISTORY)
    return if (next == searchHistory) this else copy(searchHistory = next)
}

/**
 * 记录最近浏览的位置；自动生成的历史仅保留最近十条。
 *
 * @param locations 本次访问的位置。
 * @param isArchiveLocation 判断位置是否为不应记录的压缩包内部路径。
 * @return 更新后的设置。
 */
internal fun OnyxSettings.recordRecentLocations(
    locations: List<String>,
    isArchiveLocation: (String) -> Boolean,
): OnyxSettings {
    val normalizedLocations = locations
        .map { location -> location.trim() }
        .filter { location -> location.isNotEmpty() && !isArchiveLocation(location) }
    val nextRecentLocations = if (normalizedLocations.isEmpty()) {
        recentLocations
    } else {
        buildList {
            addAll(normalizedLocations)
            addAll(recentLocations)
        }.distinct().take(MAX_RECENT_LOCATIONS)
    }
    return if (nextRecentLocations == recentLocations) {
        this
    } else {
        copy(recentLocations = nextRecentLocations).sanitizeRootSettings()
    }
}

/**
 * 按明确的可用性判断清理收藏与最近位置。
 *
 * @param isLocationAvailable 统一位置服务的可用性判断。
 * @return 清理后的设置。
 */
internal fun OnyxSettings.cleanupInvalidLocations(
    isLocationAvailable: (String) -> Boolean,
): OnyxSettings {
    return copy(
        favoriteLocations = favoriteLocations.filter(isLocationAvailable),
        recentLocations = recentLocations.filter(isLocationAvailable),
    ).sanitizeRootSettings()
}

/** 最近位置允许保留的最大数量。 */
private const val MAX_RECENT_LOCATIONS = 10

/** 搜索历史允许保留的最大数量。 */
private const val MAX_SEARCH_HISTORY = 20

/** UI 缩放允许的最小百分比。 */
internal const val MIN_UI_SCALE_PERCENT = 75

/** UI 缩放允许的最大百分比。 */
internal const val MAX_UI_SCALE_PERCENT = 200
