package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings

internal fun OnyxSettings.sanitizeRootSettings(): OnyxSettings {
    return copy(
        uiScale = uiScale.coerceIn(75, 200),
        favoriteLocations = favoriteLocations
            .map { favorite -> favorite.trim() }
            .filter { favorite -> favorite.isNotEmpty() }
            .distinct()
            .take(MaxFavoriteLocations),
        recentLocations = recentLocations
            .map { recent -> recent.trim() }
            .filter { recent -> recent.isNotEmpty() }
            .distinct()
            .take(MaxRecentLocations),
    )
}

internal fun OnyxSettings.recordRecentLocations(
    locations: List<String>,
    isArchiveLocation: (String) -> Boolean,
): OnyxSettings {
    val normalizedLocations = locations
        .map { location -> location.trim() }
        .filter { location -> location.isNotEmpty() && !isArchiveLocation(location) }
    if (normalizedLocations.isEmpty()) {
        return this
    }
    val nextRecentLocations = buildList {
        addAll(normalizedLocations)
        addAll(recentLocations)
    }.distinct().take(MaxRecentLocations)
    if (nextRecentLocations == recentLocations) {
        return this
    }
    return copy(recentLocations = nextRecentLocations).sanitizeRootSettings()
}

private const val MaxFavoriteLocations = 12
private const val MaxRecentLocations = 10
