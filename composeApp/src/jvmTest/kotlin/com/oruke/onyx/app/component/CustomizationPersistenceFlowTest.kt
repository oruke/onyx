package com.oruke.onyx.app.component

import com.oruke.onyx.app.cache.OnyxLocalDatabaseService
import com.oruke.onyx.app.cache.SettingsDatabaseRepository
import com.oruke.onyx.app.cache.SqliteSettingsRepository
import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.FileLabelMatcher
import com.oruke.onyx.core.model.FileLabelRule
import com.oruke.onyx.core.model.FileListColors
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.UserCommandDefinition
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 验证组件规范化、真实 SQLite 保存及重新装配后的完整恢复链。 */
class CustomizationPersistenceFlowTest {
    /** 超过原容量的收藏、连接及新增外观、预设、命令均完整保存，无静默截断。 */
    @Test
    fun largeCollectionsAndCustomizationsSurviveReload() = runBlocking {
        val root = Files.createTempDirectory("onyx-customization-settings-")
        val path = root.resolve("state.db")
        try {
            val settings = OnyxSettings(
                favoriteLocations = (1..40).map { "local:/fixture/favorite-$it" },
                remoteConnections = (1..32).map {
                    RemoteConnectionProfile(
                        "connection-$it",
                        "Fixture $it",
                        RemoteConnectionProtocol.WEBDAV,
                        "webdav://fixture.invalid/share/$it"
                    )
                },
                fileListColors = FileListColors(backgroundArgb = 0xFF112233L, selectionArgb = 0xFF446688L),
                fileLabelRules = listOf(
                    FileLabelRule(
                        "text",
                        "Text",
                        FileLabelMatcher(extension = "txt"),
                        textColorArgb = 0xFF334455L
                    )
                ),
                batchRenamePresets = listOf(
                    BatchRenamePreset(
                        "replace",
                        "Replace",
                        replacement = "checked",
                        draft = BatchRenameDraft(findText = "txt", replaceText = "checked")
                    )
                ),
                userCommands = listOf(
                    UserCommandDefinition(
                        "tool",
                        "Tool",
                        "configured-program",
                        listOf("{selection}")
                    )
                ),
            ).sanitizeRootSettings()
            SqliteSettingsRepository(
                OnyxLocalDatabaseService(path),
                SettingsDatabaseRepository()
            ).saveSettings(settings).getOrThrow()
            val loaded =
                SqliteSettingsRepository(OnyxLocalDatabaseService(path), SettingsDatabaseRepository()).loadSettings()
                    .getOrThrow()
            assertNotNull(loaded)
            assertEquals(40, loaded.favoriteLocations.size)
            assertEquals(32, loaded.remoteConnections.size)
            assertEquals(settings, loaded)
        } finally {
            Files.newDirectoryStream(root).use { entries -> entries.forEach(Files::delete) }
            Files.delete(root)
        }
    }
}
