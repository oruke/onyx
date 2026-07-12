package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TrashRestorationStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Windows 回收站移动结果一致性测试。 */
class WindowsTrashMoveServiceTest {
    /**
     * 校验 Shell 已移动文件但元数据未出现时仍返回成功记录。
     */
    @Test
    fun returnsNonRestorableRecordWhenMetadataIsUnavailable() {
        val source = Path.of("source.txt").toAbsolutePath()
        val service = WindowsTrashMoveService(
            moveToSystemTrash = { true },
            resolveRecycleRecord = { _, _ -> null },
            currentTimeMillis = { 100L },
        )

        val record = service.move(file(source), source)

        assertEquals(TrashRestorationStatus.METADATA_UNAVAILABLE, record.restorationStatus)
        assertTrue(record.trashedLocation.isBlank())
        assertEquals(null, record.metadataLocation)
    }

    /**
     * 校验找到 Windows 回收站元数据时返回完整可恢复记录。
     */
    @Test
    fun returnsRestorableRecordWhenMetadataIsAvailable() {
        val source = Path.of("source.txt").toAbsolutePath()
        val info = Path.of("recycle-info")
        val content = Path.of("recycle-content")
        val service = WindowsTrashMoveService(
            moveToSystemTrash = { true },
            resolveRecycleRecord = { _, _ ->
                WindowsRecycleBinRecord(
                    infoPath = info,
                    contentPath = content,
                    originalLocation = source.toString(),
                    deletedAtMillis = 100L,
                )
            },
            currentTimeMillis = { 100L },
        )

        val record = service.move(file(source), source)

        assertEquals(TrashRestorationStatus.AVAILABLE, record.restorationStatus)
        assertEquals(content.toString(), record.trashedLocation)
        assertEquals(info.toString(), record.metadataLocation)
    }

    /**
     * 校验 Shell 拒绝移动时仍返回真正的失败。
     */
    @Test
    fun failsWhenSystemTrashMoveFails() {
        val source = Path.of("source.txt").toAbsolutePath()
        val service = WindowsTrashMoveService(
            moveToSystemTrash = { false },
            resolveRecycleRecord = { _, _ -> null },
        )

        assertFailsWith<IllegalStateException> {
            service.move(file(source), source)
        }
    }

    /**
     * 创建本地测试文件条目。
     *
     * @param path 文件路径。
     * @return 测试 VFS 文件。
     */
    private fun file(path: Path): VFile {
        return VFile(
            id = path.toString(),
            name = path.fileName.toString(),
            location = path.toString(),
            parentLocation = path.parent?.toString().orEmpty(),
            kind = VFileKind.FILE,
            sizeBytes = 1L,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = emptySet(),
        )
    }
}
