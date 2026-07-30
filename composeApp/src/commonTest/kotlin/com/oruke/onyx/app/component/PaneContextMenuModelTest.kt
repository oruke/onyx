package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 面板右键菜单模型测试。
 */
class PaneContextMenuModelTest {
    /**
     * 校验单个目录的菜单包含可执行的新窗口命令。
     *
     * @return 无返回值。
     */
    @Test
    fun includesOpenDirectoryInNewWindowCommand() {
        val model = PaneContextMenuModelBuilder.build(
            PaneContextMenuBuildInput(
                entries = listOf(directory("test://local/workspace")),
                canPaste = false,
                canUndo = false,
                canRedo = false,
                canExtractSelection = false,
                contextMenuSections = emptyList(),
            )
        )

        val item = model.nodes
            .filterIsInstance<PaneContextMenuNode.Item>()
            .first { node -> node.id == "open-new-window" }

        assertTrue(item.enabled)
        assertEquals(PaneContextMenuText.OPEN_IN_NEW_WINDOW, item.text)
        assertEquals(
            PaneContextMenuCommand.Pane(PaneCommand.OPEN_SELECTION_IN_NEW_WINDOW),
            item.command,
        )
    }

    /**
     * 校验不可枚举目录会禁用新窗口打开命令。
     *
     * @return 无返回值。
     */
    @Test
    fun disablesOpenInNewWindowForUnlistableDirectory() {
        val model = PaneContextMenuModelBuilder.build(
            PaneContextMenuBuildInput(
                entries = listOf(directory("test://local/protected", listable = false)),
                canPaste = false,
                canUndo = false,
                canRedo = false,
                canExtractSelection = false,
                contextMenuSections = emptyList(),
            )
        )

        val item = model.nodes
            .filterIsInstance<PaneContextMenuNode.Item>()
            .first { node -> node.id == "open-new-window" }

        assertFalse(item.enabled)
    }

    /**
     * 校验压缩与解压操作会收敛到同一个二级菜单。
     *
     * @return 无返回值。
     */
    @Test
    fun groupsArchiveActionsIntoNestedMenu() {
        val model = PaneContextMenuModelBuilder.build(
            PaneContextMenuBuildInput(
                entries = listOf(file("test://local/archive.zip")),
                canPaste = false,
                canUndo = false,
                canRedo = false,
                canExtractSelection = true,
                contextMenuSections = emptyList(),
            )
        )

        val archiveMenu = model.nodes
            .filterIsInstance<PaneContextMenuNode.Item>()
            .single { node -> node.id == "archive" }
        val childItems = archiveMenu.children.filterIsInstance<PaneContextMenuNode.Item>()

        assertEquals(PaneContextMenuText.ARCHIVE, archiveMenu.text)
        assertEquals(
            listOf("extract-here", "extract-to-directory", "extract-smart", "create-zip"),
            childItems.map { item -> item.id },
        )
        assertEquals(
            PaneContextMenuCommand.CreateZipArchive,
            childItems.single { item -> item.id == "create-zip" }.command,
        )
        assertFalse(model.nodes.filterIsInstance<PaneContextMenuNode.Item>().any { item ->
            item.id == "extract-here" || item.id == "create-zip"
        })
    }

    /**
     * 创建目录测试条目。
     *
     * @param location 目录位置。
     * @param listable 是否允许枚举子项。
     * @return 目录条目。
     */
    private fun directory(location: String, listable: Boolean = true): VFile {
        return VFile(
            id = location,
            name = location.substringAfterLast('/'),
            location = location,
            parentLocation = null,
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = if (listable) setOf(VFileCapability.LIST_CHILDREN) else emptySet(),
        )
    }

    /**
     * 创建文件测试条目。
     *
     * @param location 文件位置。
     * @return 文件条目。
     */
    private fun file(location: String): VFile {
        return VFile(
            id = location,
            name = location.substringAfterLast('/'),
            location = location,
            parentLocation = null,
            kind = VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }
}
