package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.component.delegate.toTaskErrors
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 从真实任务错误入口验证 JSON 保存、重启恢复及 JSONL 归档的本地化信息完整性。 */
class JsonTaskLocalizationPersistenceTest {
    /** 测试独占的文件和仓储目录，场景结束后由 JUnit 回收。 */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 真实文件操作失败和结构化协议错误经重启、归档后保留消息键、参数类型及原始诊断。
     *
     * @return 无返回值。
     */
    @Test
    fun savingReloadingAndArchivingKeepsLocalizedTaskErrorsAndTypedArguments() = runBlocking {
        val directory = temporaryFolder.newFolder("task-state").toPath()
        val filePath = directory.resolve("tasks.json")
        val archivePath = directory.resolve("tasks-archive.jsonl")
        val provider = JvmLocalFileProvider()
        val entry = provider.createFile(directory.toString(), "document.txt").getOrThrow()
        val localFailure = assertNotNull(provider.createFile(directory.toString(), " ").exceptionOrNull())
        assertIs<LocalizedException>(localFailure)
        val remoteFailure = VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                location = "webdav://storage.example.invalid/documents",
                capability = VfsProviderCapability.COPY,
            ),
        )
        val tasks = listOf(
            localizationTask("local-failure", localFailure.toTaskErrors(listOf(entry), directory.toString())),
            localizationTask("protocol-failure", remoteFailure.toTaskErrors(listOf(entry), directory.toString())),
        )
        val expectedLocalMessage = I18nMessage(MessageKey.MSG_FILE_NAME_EMPTY)
        val expectedRemoteMessage = I18nMessage(
            MessageKey.MSG_VFS_UNSUPPORTED_OPERATION, VfsProtocol.WEBDAV, VfsProviderCapability.COPY,
        )
        assertEquals(expectedLocalMessage, tasks[0].errors.single().detail)
        assertEquals(expectedRemoteMessage, tasks[1].errors.single().detail)
        JsonTaskPersistenceRepository(filePath, archivePath).saveTasks(tasks).getOrThrow()

        // 读取真实磁盘 JSON，先核对存储格式，再由全新仓储实例恢复运行时类型。
        val storedTasks = Json.parseToJsonElement(Files.readString(filePath)).jsonObject.getValue("tasks").jsonArray
        val storedLocalError = storedTasks[0].jsonObject.getValue("errors").jsonArray.single().jsonObject
        val storedRemoteError = storedTasks[1].jsonObject.getValue("errors").jsonArray.single().jsonObject
        assertEquals("document.txt", storedLocalError.getValue("fileName").jsonPrimitive.content)
        assertEquals(localFailure.message, storedLocalError.getValue("message").jsonPrimitive.content)
        assertEquals("MSG_FILE_NAME_EMPTY", storedLocalError.getValue("detail").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals(remoteFailure.message, storedRemoteError.getValue("message").jsonPrimitive.content)
        assertStoredProtocolMessage(storedRemoteError.getValue("detail").jsonObject)
        assertStoredProtocolMessage(storedTasks[1].jsonObject.getValue("detail").jsonObject)

        val reloadedRepository = JsonTaskPersistenceRepository(filePath, archivePath)
        val reloadedTasks = reloadedRepository.loadTasks().getOrThrow()
        assertEquals(tasks, reloadedTasks)
        val reloadedMessage = assertNotNull(reloadedTasks[1].errors.single().detail)
        assertEquals(VfsProtocol.WEBDAV, assertIs<VfsProtocol>(reloadedMessage.args[0]))
        assertEquals(VfsProviderCapability.COPY, assertIs<VfsProviderCapability>(reloadedMessage.args[1]))
        assertEquals(expectedLocalMessage, reloadedTasks[0].errors.single().detail)
        assertTrue(Files.exists(directory.resolve("document.txt")))

        reloadedRepository.archiveTasks(reloadedTasks).getOrThrow()
        val archiveLines = Files.readAllLines(archivePath)
        assertEquals(2, archiveLines.size)
        archiveLines.forEachIndexed { index, line ->
            val record = Json.parseToJsonElement(line).jsonObject
            assertTrue(record.getValue("archivedAtMillis").jsonPrimitive.long > 0L)
            assertEquals(storedTasks[index], record.getValue("task"))
        }
        assertEquals(reloadedTasks, JsonTaskPersistenceRepository(filePath, archivePath).loadTasks().getOrThrow())
    }

    /**
     * 历史 TaskError 仅含文件名和诊断时仍可加载，再保存与归档不会丢失诊断或伪造消息键。
     *
     * @return 无返回值。
     */
    @Test
    fun legacyErrorsWithoutLocalizedDetailRemainReadableAndArchivable() = runBlocking {
        val directory = temporaryFolder.newFolder("legacy-task-state").toPath()
        val filePath = directory.resolve("tasks.json")
        val archivePath = directory.resolve("tasks-archive.jsonl")
        Files.writeString(
            filePath,
            """
            {
              "tasks": [{
                "id": "legacy-failure",
                "kind": "COPY",
                "title": {"key": "MSG_COPY_FAILED"},
                "status": "FAILED",
                "detail": {"key": "MSG_COPY_FAILED"},
                "errors": [{"fileName": "legacy-document.txt", "message": "Native tool failed with code 17"}]
              }]
            }
            """.trimIndent(),
        )
        val originalRecord = Json.parseToJsonElement(Files.readString(filePath)).jsonObject
        val originalError = originalRecord.getValue("tasks").jsonArray.single().jsonObject
            .getValue("errors").jsonArray.single().jsonObject
        assertTrue("detail" !in originalError)

        val repository = JsonTaskPersistenceRepository(filePath, archivePath)
        val loaded = repository.loadTasks().getOrThrow()
        val legacyError = loaded.single().errors.single()
        assertEquals("legacy-document.txt", legacyError.fileName)
        assertEquals("Native tool failed with code 17", legacyError.message)
        assertNull(legacyError.detail)
        repository.saveTasks(loaded).getOrThrow()
        val restored = JsonTaskPersistenceRepository(filePath, archivePath).loadTasks().getOrThrow()
        assertEquals(loaded, restored)

        repository.archiveTasks(restored).getOrThrow()
        val storedTask = Json.parseToJsonElement(Files.readString(filePath)).jsonObject
            .getValue("tasks").jsonArray.single().jsonObject
        assertEquals(originalError, storedTask.getValue("errors").jsonArray.single())
        val archived = Json.parseToJsonElement(Files.readAllLines(archivePath).single()).jsonObject
        assertEquals(storedTask, archived.getValue("task"))
    }
}

/**
 * 将真实错误转换为完整失败任务，任务详情和文件错误共用同一可本地化消息。
 *
 * @param id 当前测试场景的稳定任务标识。
 * @param errors 经生产错误映射入口得到的文件错误列表。
 * @return 可交给生产仓储保存的失败任务。
 */
private fun localizationTask(id: String, errors: List<TaskError>): BackgroundTask = BackgroundTask(
    id = id,
    kind = BackgroundTaskKind.COPY,
    title = I18nMessage(MessageKey.MSG_COPY_FAILED),
    status = BackgroundTaskStatus.FAILED,
    detail = assertNotNull(errors.single().detail),
    errors = errors,
)

/**
 * 核对磁盘 JSON 中的消息键及协议、能力参数类型，避免重启后退化为无法翻译的普通字符串。
 *
 * @param message 从实际任务 JSON 或 JSONL 读取的消息对象。
 * @return 无返回值。
 */
private fun assertStoredProtocolMessage(message: JsonObject) {
    assertEquals("MSG_VFS_UNSUPPORTED_OPERATION", message.getValue("key").jsonPrimitive.content)
    assertEquals(
        Json.parseToJsonElement(
            """[{"type":"vfs_protocol","value":"WEBDAV"},{"type":"vfs_capability","value":"COPY"}]""",
        ),
        message.getValue("args"),
    )
}
