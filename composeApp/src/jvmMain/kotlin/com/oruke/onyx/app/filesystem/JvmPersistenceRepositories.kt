package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.app.storage.OnyxDataDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.TaskPersistenceRepository

private val PersistenceJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

private val PersistenceLineJson = Json {
    ignoreUnknownKeys = true
}

/**
 * 使用 JSON 文件持久化应用会话快照。
 *
 * @param filePath 会话文件路径。
 */
class JsonSessionRepository(
    private val filePath: Path = OnyxDataDirectories.stateDirectory().resolve("session.json"),
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

/**
 * 使用 JSON 与 JSONL 文件持久化活动任务和任务归档。
 *
 * @param filePath 活动任务文件路径。
 * @param archivePath 已归档任务文件路径。
 * @param maxArchiveEntries 最大归档条目数。
 */
class JsonTaskPersistenceRepository(
    private val filePath: Path = OnyxDataDirectories.stateDirectory().resolve("tasks.json"),
    private val archivePath: Path = OnyxDataDirectories.stateDirectory().resolve("tasks-archive.jsonl"),
    private val maxArchiveEntries: Int = 1_000,
) : TaskPersistenceRepository {
    override suspend fun loadTasks(): Result<List<BackgroundTask>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!filePath.exists()) {
                emptyList()
            } else {
                val content = filePath.readText().trim()
                if (content.isBlank()) {
                    emptyList()
                } else {
                    PersistenceJson.decodeFromString<PersistedTaskList>(content)
                        .tasks
                        .map { task -> task.toBackgroundTask() }
                }
            }
        }
    }

    override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureParentDirectory(filePath)
            val snapshot = PersistedTaskList(tasks = tasks.map { task -> task.toPersistedTask() })
            filePath.writeText(PersistenceJson.encodeToString(snapshot))
        }
    }

    override suspend fun archiveTasks(tasks: List<BackgroundTask>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (tasks.isEmpty()) return@runCatching
            ensureParentDirectory(archivePath)
            val archivedAt = System.currentTimeMillis()
            val archivedLines = tasks.map { task ->
                PersistenceLineJson.encodeToString(
                    PersistedTaskArchiveEntry(
                        archivedAtMillis = archivedAt,
                        task = task.toPersistedTask(),
                    )
                )
            }
            val existingLines = if (archivePath.exists()) {
                archivePath.readText()
                    .lineSequence()
                    .map { line -> line.trim() }
                    .filter { line -> line.isNotEmpty() }
                    .toList()
            } else {
                emptyList()
            }
            val nextLines = (existingLines + archivedLines).takeLast(maxArchiveEntries.coerceAtLeast(1))
            archivePath.writeText(nextLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}

private fun ensureParentDirectory(filePath: Path) {
    filePath.parent?.createDirectories()
}

@Serializable
private data class PersistedTaskList(
    val version: Int = 1,
    val tasks: List<PersistedTask> = emptyList(),
)

@Serializable
private data class PersistedTaskArchiveEntry(
    val version: Int = 1,
    val archivedAtMillis: Long,
    val task: PersistedTask,
)

@Serializable
private data class PersistedTask(
    val id: String,
    val kind: BackgroundTaskKind,
    val title: PersistedI18nMessage,
    val status: BackgroundTaskStatus,
    val detail: PersistedI18nMessage,
    val progress: Float? = null,
    val currentFileName: String? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val startTimeMillis: Long = 0L,
    val errors: List<TaskError> = emptyList(),
)

@Serializable
private data class PersistedI18nMessage(
    val key: MessageKey,
    val args: List<PersistedMessageArg> = emptyList(),
)

@Serializable
private data class PersistedMessageArg(
    val type: String,
    val value: String,
)

private fun BackgroundTask.toPersistedTask(): PersistedTask {
    return PersistedTask(
        id = id,
        kind = kind,
        title = title.toPersistedMessage(),
        status = status,
        detail = detail.toPersistedMessage(),
        progress = progress,
        currentFileName = currentFileName,
        processedCount = processedCount,
        totalCount = totalCount,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        startTimeMillis = startTimeMillis,
        errors = errors,
    )
}

private fun PersistedTask.toBackgroundTask(): BackgroundTask {
    return BackgroundTask(
        id = id,
        kind = kind,
        title = title.toI18nMessage(),
        status = status,
        detail = detail.toI18nMessage(),
        progress = progress,
        currentFileName = currentFileName,
        processedCount = processedCount,
        totalCount = totalCount,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        startTimeMillis = startTimeMillis,
        errors = errors,
    )
}

private fun I18nMessage.toPersistedMessage(): PersistedI18nMessage {
    return PersistedI18nMessage(
        key = key,
        args = args.map { arg -> arg.toPersistedMessageArg() },
    )
}

private fun PersistedI18nMessage.toI18nMessage(): I18nMessage {
    return I18nMessage(key = key, args = args.map { arg -> arg.toRuntimeArg() })
}

private fun Any.toPersistedMessageArg(): PersistedMessageArg {
    return when (this) {
        is Int -> PersistedMessageArg("int", toString())
        is Long -> PersistedMessageArg("long", toString())
        is Float -> PersistedMessageArg("float", toString())
        is Double -> PersistedMessageArg("double", toString())
        is Boolean -> PersistedMessageArg("boolean", toString())
        else -> PersistedMessageArg("string", toString())
    }
}

private fun PersistedMessageArg.toRuntimeArg(): Any {
    return when (type) {
        "int" -> value.toIntOrNull() ?: value
        "long" -> value.toLongOrNull() ?: value
        "float" -> value.toFloatOrNull() ?: value
        "double" -> value.toDoubleOrNull() ?: value
        "boolean" -> value.toBooleanStrictOrNull() ?: value
        else -> value
    }
}
