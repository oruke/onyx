package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings

internal fun OnyxSettings.sanitizeRootSettings(): OnyxSettings {
    return copy(
        uiScale = uiScale.coerceIn(MIN_UI_SCALE_PERCENT, MAX_UI_SCALE_PERCENT),
        favoriteLocations = favoriteLocations
            .map { favorite -> favorite.trim() }
            .filter { favorite -> favorite.isNotEmpty() }
            .distinct()
            .take(MAX_FAVORITE_LOCATIONS),
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
            .distinctBy { connection -> connection.id }
            .take(MAX_REMOTE_CONNECTIONS),
        searchHistory = searchHistory
            .map { query -> query.trim() }
            .filter { query -> query.isNotEmpty() }
            .distinct()
            .take(MAX_SEARCH_HISTORY),
        searchDrawerHeight = searchDrawerHeight.coerceIn(MIN_SEARCH_DRAWER_HEIGHT, MAX_SEARCH_DRAWER_HEIGHT),
        jobsDrawerHeight = jobsDrawerHeight.coerceIn(MIN_JOBS_DRAWER_HEIGHT, MAX_JOBS_DRAWER_HEIGHT),
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

internal fun OnyxSettings.cleanupInvalidLocations(
    isLocationAvailable: (String) -> Boolean,
): OnyxSettings {
    return copy(
        favoriteLocations = favoriteLocations.filter(isLocationAvailable),
        recentLocations = recentLocations.filter(isLocationAvailable),
    ).sanitizeRootSettings()
}

/** 收藏位置允许保留的最大数量。 */
private const val MAX_FAVORITE_LOCATIONS = 12

/** 最近位置允许保留的最大数量。 */
private const val MAX_RECENT_LOCATIONS = 10

/** 网络连接配置允许保留的最大数量。 */
private const val MAX_REMOTE_CONNECTIONS = 24

/** 搜索历史允许保留的最大数量。 */
private const val MAX_SEARCH_HISTORY = 20

/** 搜索抽屉高度允许的最小比例。 */
internal const val MIN_SEARCH_DRAWER_HEIGHT = 0.2f

/** 搜索抽屉高度允许的最大比例。 */
internal const val MAX_SEARCH_DRAWER_HEIGHT = 0.8f

/** 任务中心抽屉高度允许的最小比例。 */
internal const val MIN_JOBS_DRAWER_HEIGHT = 0.15f

/** 任务中心抽屉高度允许的最大比例。 */
internal const val MAX_JOBS_DRAWER_HEIGHT = 0.6f

/** UI 缩放允许的最小百分比。 */
internal const val MIN_UI_SCALE_PERCENT = 75

/** UI 缩放允许的最大百分比。 */
internal const val MAX_UI_SCALE_PERCENT = 200
