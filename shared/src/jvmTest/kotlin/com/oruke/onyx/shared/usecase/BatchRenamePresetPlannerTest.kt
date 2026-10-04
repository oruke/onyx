package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.BatchRenameCaseMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 批量重命名预设规划器测试。 */
class BatchRenamePresetPlannerTest {
    /**
     * 普通文本查找替换应保留未匹配部分与文件扩展名。
     */
    @Test
    fun appliesLiteralFindReplaceToOriginalName() {
        val preview = BatchRenamePresetPlanner().preview(
            entries = listOf(testFile("report-old.txt")),
            preset = BatchRenamePreset(
                id = "literal-find-replace",
                name = "Literal find replace",
                pattern = "old",
                replacement = "new",
            ),
        ).getOrThrow()

        assertEquals("report-new.txt", preview.items.single().newName)
    }

    /**
     * 正则表达式替换应支持捕获组并保留未匹配的扩展名部分。
     */
    @Test
    fun appliesRegexFindReplaceWithCaptureGroup() {
        val preview = BatchRenamePresetPlanner().preview(
            entries = listOf(testFile("report-42.txt")),
            preset = BatchRenamePreset(
                id = "regex-find-replace",
                name = "Regex find replace",
                pattern = "report-(\\d+)",
                replacement = "archive-\$1",
                useRegex = true,
            ),
        ).getOrThrow()

        assertEquals("archive-42.txt", preview.items.single().newName)
    }

    /** 模板预设恢复为可编辑草稿并重新保存后，应保留序号、扩展名和大小写转换。 */
    @Test
    fun templateParametersSurviveEditingAndSaving() {
        val original = BatchRenamePreset(
            id = "template", name = "Template", replacement = "{index}_{name}.{ext}",
            startIndex = 7, padding = 2, caseMode = BatchRenameCaseMode.UPPER,
        )
        val entries = listOf(testFile("report.txt"), testFile("日本語.txt"))
        val saved = original.copy(name = "Edited", draft = original.toEditableDraft())
        val planner = BatchRenamePresetPlanner()
        assertEquals(
            listOf("07_REPORT.TXT", "08_日本語.TXT"),
            planner.preview(entries, saved).getOrThrow().items.map { it.newName })
        assertEquals(
            planner.preview(entries, original).getOrThrow().items,
            planner.preview(entries, saved).getOrThrow().items
        )
    }

    /** 模板允许使用最后一个有效序号，但同批后续条目溢出时整批预览必须拒绝。 */
    @Test
    fun templateCounterRejectsOverflowBeforeAnyRename() {
        val preset = BatchRenamePreset(
            id = "last-index", name = "Last index", replacement = "{index}_{name}",
            startIndex = Int.MAX_VALUE, padding = 0,
        ).let { it.copy(draft = it.toEditableDraft()) }
        val planner = BatchRenamePresetPlanner()
        assertEquals(
            "${Int.MAX_VALUE}_report", planner.preview(listOf(testFile("report.txt")), preset)
                .getOrThrow().items.single().newName
        )
        assertTrue(planner.preview(listOf(testFile("report.txt"), testFile("next.txt")), preset).isFailure)
    }

    /**
     * 构造用于名称规则测试的最小虚拟文件快照。
     *
     * @param name 测试文件名。
     * @return 支持重命名的测试文件。
     */
    private fun testFile(name: String): VFile {
        return VFile(
            id = "test://$name",
            name = name,
            location = "test://$name",
            parentLocation = "test://",
            kind = VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.RENAME),
        )
    }
}
