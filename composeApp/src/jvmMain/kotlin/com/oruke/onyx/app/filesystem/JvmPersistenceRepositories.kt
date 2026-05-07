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

private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")
private val IS_MAC = System.getProperty("os.name").lowercase().contains("mac")

private fun onyxConfigDirectory(): Path {
    // 优先检查 XDG 环境变量（所有平台通用覆盖）
    val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    if (xdgConfigHome != null) return Path.of(xdgConfigHome).resolve("onyx")

    val userHome = System.getProperty("user.home")
    return when {
        IS_WINDOWS -> {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: Path.of(userHome, "AppData", "Roaming").toString()
            Path.of(appData).resolve("Onyx")
        }
        IS_MAC -> Path.of(userHome, "Library", "Application Support", "Onyx")
        else -> Path.of(userHome, ".config", "onyx")
    }
}

private fun onyxStateDirectory(): Path {
    val xdgStateHome = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
    if (xdgStateHome != null) return Path.of(xdgStateHome).resolve("onyx")

    val userHome = System.getProperty("user.home")
    return when {
        IS_WINDOWS -> {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: Path.of(userHome, "AppData", "Local").toString()
            Path.of(localAppData).resolve("Onyx")
        }
        IS_MAC -> Path.of(userHome, "Library", "Caches", "Onyx")
        else -> Path.of(userHome, ".local", "state", "onyx")
    }
}
