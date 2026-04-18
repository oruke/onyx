package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val PersistenceJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

class JsonSettingsRepository(
    private val filePath: Path = onyxConfigDirectory().resolve("settings.json"),
) : SettingsRepository {
    override suspend fun loadSettings(): Result<OnyxSettings?> = withContext(Dispatchers.IO) {
        runCatching {
            if (!filePath.exists()) {
                null
            } else {
                val content = filePath.readText().trim()
                if (content.isBlank()) {
                    null
                } else {
                    PersistenceJson.decodeFromString<OnyxSettings>(content)
                }
            }
        }
    }

    override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureParentDirectory(filePath)
            filePath.writeText(PersistenceJson.encodeToString(settings))
        }
    }
}

class JsonSessionRepository(
    private val filePath: Path = onyxStateDirectory().resolve("session.json"),
) : SessionRepository {
    override suspend fun loadSession(): Result<AppSessionSnapshot?> = withContext(Dispatchers.IO) {
        runCatching {
            if (!filePath.exists()) {
                null
            } else {
                val content = filePath.readText().trim()
                if (content.isBlank()) {
                    null
                } else {
                    PersistenceJson.decodeFromString<AppSessionSnapshot>(content)
                }
            }
        }
    }

    override suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureParentDirectory(filePath)
            filePath.writeText(PersistenceJson.encodeToString(snapshot))
        }
    }
}

private fun ensureParentDirectory(filePath: Path) {
    filePath.parent?.createDirectories()
}

private fun onyxConfigDirectory(): Path {
    val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    return if (xdgConfigHome != null) {
        Path.of(xdgConfigHome).resolve("onyx")
    } else {
        Path.of(System.getProperty("user.home"), ".config", "onyx")
    }
}

private fun onyxStateDirectory(): Path {
    val xdgStateHome = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
    return if (xdgStateHome != null) {
        Path.of(xdgStateHome).resolve("onyx")
    } else {
        Path.of(System.getProperty("user.home"), ".local", "state", "onyx")
    }
}
