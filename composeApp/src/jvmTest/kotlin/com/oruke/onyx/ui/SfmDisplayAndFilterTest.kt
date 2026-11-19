package com.oruke.onyx.ui

import com.oruke.onyx.app.component.delegate.EntrySorter
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.usecase.FileSearchRequest
import com.oruke.onyx.shared.usecase.FileSearchEvent
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.vfs.api.FileRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** 验证 SFM 私用区文件名在界面展示、面板内快速筛选与全局搜索中的一致性，且不污染底层路径。 */
class SfmDisplayAndFilterTest {
    /** 验证 displayFileName 还原 SFM 字符且保留底层 VFile.name / location 原值。 */
    @Test
    fun displayFileNameDecodesSfmWithoutMutatingRawVFile() {
        val sfmDir = VFile(
            id = "dir-1",
            name = "我说过要戴安全套吧..\uF029",
            location = "Z:\\unsorted\\video\\里番\\とるだ屋\\我说过要戴安全套吧..\uF029",
            parentLocation = "Z:\\unsorted\\video\\里番\\とるだ屋",
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )
        val sfmFile = VFile(
            id = "file-1",
            name = "大哥哥\uF025.mp4",
            location = "Z:\\video\\大哥哥\uF025.mp4",
            parentLocation = "Z:\\video",
            kind = VFileKind.FILE,
            sizeBytes = 1024L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )

        assertEquals("我说过要戴安全套吧...", sfmDir.displayFileName(showExtensions = true))
        assertEquals("我说过要戴安全套吧...", sfmDir.displayFileName(showExtensions = false))
        assertEquals("我说过要戴安全套吧..\uF029", sfmDir.name)

        assertEquals("大哥哥?.mp4", sfmFile.displayFileName(showExtensions = true))
        assertEquals("大哥哥?", sfmFile.displayFileName(showExtensions = false))
        assertEquals("大哥哥\uF025.mp4", sfmFile.name)
    }

    /** 验证面板快速筛选与全局搜索均支持以还原后的可读字符（如 `...`、`?`）命中 SFM 条目。 */
    @Test
    fun sorterAndSearchUseCaseMatchDecodedSfmNames() = runBlocking {
        val sfmDir = VFile(
            id = "dir-sfm",
            name = "我说过要戴安全套吧..\uF029",
            location = "/nas/我说过要戴安全套吧..\uF029",
            parentLocation = "/nas",
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )
        val sfmQuestion = VFile(
            id = "file-sfm",
            name = "一个人吗\uF025.mkv",
            location = "/nas/一个人吗\uF025.mkv",
            parentLocation = "/nas",
            kind = VFileKind.FILE,
            sizeBytes = 10L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = emptySet(),
        )
        val entries = listOf(sfmDir, sfmQuestion)
        val sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING)

        val matchedDots = EntrySorter.visibleSortedEntries(entries, true, sort, "安全套吧...")
        assertEquals(listOf(sfmDir), matchedDots)

        val matchedQuestion = EntrySorter.visibleSortedEntries(entries, true, sort, "一个人吗?")
        assertEquals(listOf(sfmQuestion), matchedQuestion)

        val repo = object : FileRepository {
            override suspend fun list(location: String): Result<List<VFile>> =
                Result.success(if (location == "/nas") entries else emptyList())
            override fun defaultLocation(): String = "/nas"
        }
        val events = FileSearchUseCase(repo).search(
            FileSearchRequest(rootLocation = "/nas", query = "安全套吧..."),
        ).toList()
        val finalResults = events.filterIsInstance<FileSearchEvent.Results>().last().entries
        assertEquals(listOf(sfmDir), finalResults)
    }
}
