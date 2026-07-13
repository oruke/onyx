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
    )
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

/** UI 缩放允许的最小百分比。 */
internal const val MIN_UI_SCALE_PERCENT = 75

/** UI 缩放允许的最大百分比。 */
internal const val MAX_UI_SCALE_PERCENT = 200
