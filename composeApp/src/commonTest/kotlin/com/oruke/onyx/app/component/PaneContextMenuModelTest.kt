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
}
