package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path

internal fun VFile.systemLocalPathOrNull(): Path? {
    val path = runCatching { Path.of(location) }.getOrNull() ?: return null
    return path.takeIf { candidate -> candidate.isAbsolute }
}

internal fun VFile.requireSystemLocalPath(capability: String): Path {
    return systemLocalPathOrNull()
        ?: throw IllegalStateException("Only local files support $capability: $location")
}

internal fun VFile.guessSystemMimeType(): String? {
    return runCatching { Files.probeContentType(Path.of(name)) }.getOrNull()
        ?: URLConnection.guessContentTypeFromName(name)
}
