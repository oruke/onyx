package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * 创建目录测试条目。
     *
     * @param location 目录位置。
     * @return 目录条目。
     */
    private fun directory(location: String): VFile {
        return VFile(
            id = location,
            name = location.substringAfterLast('/'),
            location = location,
            parentLocation = null,
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = emptySet(),
        )
    }
}
