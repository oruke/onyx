package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.VFileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnyxFileIconTest {

    @Test
    fun `resolveFileIconCategory maps all 21 file categories accurately`() {
        assertEquals(
            OnyxFileIconCategory.DIRECTORY,
            resolveFileIconCategory("Projects", VFileKind.DIRECTORY),
        )
        assertEquals(OnyxFileIconCategory.ARCHIVE, resolveFileIconCategory("backup.7z"))
        assertEquals(OnyxFileIconCategory.IMAGE, resolveFileIconCategory("photo.webp"))
        assertEquals(OnyxFileIconCategory.VIDEO, resolveFileIconCategory("movie.mkv"))
        assertEquals(OnyxFileIconCategory.AUDIO, resolveFileIconCategory("track.flac"))
        assertEquals(OnyxFileIconCategory.PDF, resolveFileIconCategory("manual.pdf"))
        assertEquals(OnyxFileIconCategory.WORD_DOC, resolveFileIconCategory("report.docx"))
        assertEquals(OnyxFileIconCategory.SPREADSHEET, resolveFileIconCategory("finance.xlsx"))
        assertEquals(OnyxFileIconCategory.PRESENTATION, resolveFileIconCategory("slides.pptx"))
        assertEquals(OnyxFileIconCategory.MARKDOWN, resolveFileIconCategory("README.md"))
        assertEquals(OnyxFileIconCategory.TEXT, resolveFileIconCategory("notes.txt"))
        assertEquals(OnyxFileIconCategory.CODE, resolveFileIconCategory("Main.kt"))
        assertEquals(OnyxFileIconCategory.CODE, resolveFileIconCategory("Dockerfile"))
        assertEquals(OnyxFileIconCategory.CONFIG_DATA, resolveFileIconCategory("config.toml"))
        assertEquals(OnyxFileIconCategory.CONFIG_DATA, resolveFileIconCategory(".env.local"))
        assertEquals(OnyxFileIconCategory.SCRIPT_SHELL, resolveFileIconCategory("deploy.ps1"))
        assertEquals(OnyxFileIconCategory.EXECUTABLE, resolveFileIconCategory("Onyx.exe"))
        assertEquals(OnyxFileIconCategory.AI_MODEL, resolveFileIconCategory("qwen3-8b-q4_k_m.gguf"))
        assertEquals(OnyxFileIconCategory.AI_MODEL, resolveFileIconCategory("model.safetensors"))
        assertEquals(OnyxFileIconCategory.DATABASE, resolveFileIconCategory("cache.sqlite3"))
        assertEquals(OnyxFileIconCategory.DISK_IMAGE, resolveFileIconCategory("windows11.iso"))
        assertEquals(OnyxFileIconCategory.FONT, resolveFileIconCategory("Inter-Regular.ttf"))
        assertEquals(OnyxFileIconCategory.SHORTCUT_LINK, resolveFileIconCategory("VSCode.lnk"))
        assertEquals(OnyxFileIconCategory.GENERIC, resolveFileIconCategory("unknown_blob.xyz"))
    }

    @Test
    fun `requiresInstanceLookup distinguishes local instance-icon files from extension-cached or remote files`() {
        assertTrue(SystemFileIconLoader.requiresInstanceLookup("Onyx.exe", "C:\\Apps\\Onyx.exe"))
        assertTrue(SystemFileIconLoader.requiresInstanceLookup("Shortcut.lnk", "C:\\Users\\Desktop\\Shortcut.lnk"))
        assertFalse(SystemFileIconLoader.requiresInstanceLookup("Onyx.exe", "smb://nas/share/Onyx.exe"))
        assertFalse(SystemFileIconLoader.requiresInstanceLookup("report.pdf", "C:\\Docs\\report.pdf"))
    }

    @Test
    fun `parseIconLocationSpec extracts file path and resource index`() {
        val indexed = SystemMenuIconBitmapLoader.parseIconLocationSpec(
            "\"C:\\Windows\\System32\\imageres.dll\",-5302",
        )
        assertEquals("C:\\Windows\\System32\\imageres.dll", indexed?.filePath)
        assertEquals(-5302, indexed?.iconIndex)

        val plain = SystemMenuIconBitmapLoader.parseIconLocationSpec("C:\\Program Files\\App\\app.exe")
        assertEquals("C:\\Program Files\\App\\app.exe", plain?.filePath)
        assertEquals(null, plain?.iconIndex)
    }

    @Test
    fun `windows system icon loader extracts valid non-degenerate bitmaps`() = kotlinx.coroutines.test.runTest {
        if (com.oruke.onyx.app.platform.currentDesktopOperatingSystem() !=
            com.oruke.onyx.app.platform.DesktopOperatingSystem.WINDOWS
        ) {
            return@runTest
        }
        val explorerIcon = SystemFileIconLoader.load("explorer.exe", "C:\\Windows\\explorer.exe", large = false)
        val largeExplorerIcon = SystemFileIconLoader.load("explorer.exe", "C:\\Windows\\explorer.exe", large = true)
        val iniIcon = SystemFileIconLoader.load("bugreport_config.ini", null, large = false)
        val dllIcon = SystemFileIconLoader.load("crash_report_helper.dll", null, large = false)

        listOf(
            "explorer.exe (16)" to explorerIcon,
            "explorer.exe (32)" to largeExplorerIcon,
        ).forEach { (label, bitmap) ->
            kotlin.test.assertNotNull(bitmap, "$label should not be null")
            assertTrue(bitmap.width >= 16 && bitmap.height >= 16, "$label dimensions should be >= 16x16")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            val visiblePixels = pixels.filter { (it ushr 24) > 16 }
            assertTrue(visiblePixels.size > 16, "$label should have > 16 visible pixels")
            val distinctRgb = visiblePixels.map { it and 0x00FFFFFF }.toSet()
            assertTrue(distinctRgb.size >= 4, "$label should have rich color/shade variation, not solid black/white")
        }

        // Windows default .ini / .dll generic grayscale white-paper icons are filtered out
        // so OnyxFileIcon renders high-contrast colorful vector icons instead of washed-out white paper.
        assertEquals(null, iniIcon)
        assertEquals(null, dllIcon)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `visual render capture of real EntryRowCells for WeGameLauncher without prewarming`() =
        androidx.compose.ui.test.runDesktopComposeUiTest(width = 860, height = 780) {
            val rootDir = java.io.File("D:\\WeGameApps\\英雄联盟")
            val subDir = java.io.File("D:\\WeGameApps\\英雄联盟\\WeGameLauncher")
            if (!rootDir.exists() || !subDir.exists()) return@runDesktopComposeUiTest

            fun java.io.File.toVFile(): com.oruke.onyx.core.model.VFile = com.oruke.onyx.core.model.VFile(
                id = absolutePath,
                name = name,
                location = absolutePath,
                parentLocation = parentFile?.absolutePath,
                kind = if (isDirectory) VFileKind.DIRECTORY else VFileKind.FILE,
                sizeBytes = if (isFile) length() else null,
                modifiedAtEpochMillis = lastModified(),
                hidden = isHidden,
                capabilities = if (isDirectory) {
                    setOf(com.oruke.onyx.core.model.VFileCapability.LIST_CHILDREN)
                } else {
                    emptySet()
                },
            )

            val rootFiles = rootDir.listFiles().orEmpty()
                .sortedWith(compareBy<java.io.File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .map { it.toVFile() }
            val subFiles = subDir.listFiles().orEmpty()
                .sortedWith(compareBy<java.io.File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .map { it.toVFile() }

            val flattened = flattenEntries(
                entries = rootFiles,
                expandedLocations = setOf(subDir.absolutePath),
                expandedEntries = mapOf(
                    subDir.absolutePath to com.oruke.onyx.app.component.InlineExpandedEntry(
                        parentLocation = subDir.absolutePath,
                        depth = 1,
                        entries = subFiles,
                    ),
                ),
                depth = 0,
            )
            val columns = listOf(
                com.oruke.onyx.core.model.DetailsColumn.NAME,
                com.oruke.onyx.core.model.DetailsColumn.TYPE,
                com.oruke.onyx.core.model.DetailsColumn.SIZE,
                com.oruke.onyx.core.model.DetailsColumn.MODIFIED,
            )
            val columnWidths = mapOf(
                com.oruke.onyx.core.model.DetailsColumn.NAME to 380f,
                com.oruke.onyx.core.model.DetailsColumn.TYPE to 90f,
                com.oruke.onyx.core.model.DetailsColumn.SIZE to 110f,
                com.oruke.onyx.core.model.DetailsColumn.MODIFIED to 180f,
            )
            val dummyActions = EntryRowActions(
                onActivate = {},
                onOpenEntry = {},
                onSelectEntry = { _, _, _ -> },
                onStartFileDrag = { _, _ -> },
                onFileDragPositionChange = {},
                onFileDragEnd = {},
                onFileDropZoneChange = {},
                onShowContextMenu = { _, _, _ -> },
                onDismissContextMenu = {},
            )

            setContent {
                com.oruke.onyx.ui.theme.OnyxWindowTheme(settings = com.oruke.onyx.core.model.OnyxSettings()) {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(12.dp),
                    ) {
                            flattened.forEachIndexed { index, item ->
                                val rowState = EntryRowState(
                                    columns = columns,
                                    columnWidths = columnWidths,
                                    entry = item.entry,
                                    zebra = index % 2 == 1,
                                    selected = false,
                                    selectedEntryCount = 0,
                                    paneActive = true,
                                    paneId = com.oruke.onyx.core.model.PaneId.PRIMARY,
                                    fileDropTarget = null,
                                    depth = item.depth,
                                    isExpanded = item.isExpanded,
                                    isExpandable = item.isExpandable,
                                    isExpandLoading = item.isLoading,
                                )
                                androidx.compose.foundation.layout.Row(
                                    modifier = androidx.compose.ui.Modifier
                                        .background(entryRowBackground(rowState))
                                        .padding(vertical = 3.dp, horizontal = 6.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    EntryRowCells(
                                        state = rowState,
                                        actions = dummyActions,
                                        onNameCoordinates = {},
                                        onExpandCoordinates = {},
                                    )
                                }
                            }
                        }
                    }
            }

            waitUntil(timeoutMillis = 5_000) {
                flattened.filter { it.entry.kind == VFileKind.FILE }.all {
                    SystemFileIconLoader.getCached(it.entry.name, it.entry.location, large = false) != null
                }
            }
            waitForIdle()
            val image = onRoot().captureToImage().toAwtImage()
            assertTrue(image.width >= 800 && image.height >= 700)
        }
}
