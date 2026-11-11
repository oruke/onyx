package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.archive.ArchiveService
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path

internal fun VFile.systemLocalPathOrNull(): Path? {
    if (
        location.contains("://") ||
        ArchiveService.isArchiveLocation(location) ||
        RemoteVfsLocationParser.parse(location) != null
    ) {
        return null
    }
    return runCatching { Path.of(location) }
        .getOrNull()
        ?.takeIf { candidate -> candidate.isAbsolute }
}

internal fun VFile.requireSystemLocalPath(capability: String): Path {
    return checkNotNull(systemLocalPathOrNull()) {
        "Only local files support $capability: $location"
    }
}

internal fun VFile.guessSystemMimeType(): String? {
    return runCatching { Files.probeContentType(Path.of(name)) }.getOrNull()
        ?: URLConnection.guessContentTypeFromName(name)
}
