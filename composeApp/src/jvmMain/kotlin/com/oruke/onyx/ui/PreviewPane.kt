package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File

@Composable
internal fun PreviewPane(
    selectedEntry: VFile?,
    palette: OnyxPalette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.surface)
    ) {
        if (selectedEntry == null) {
            Text(
                text = "No item selected",
                color = palette.mutedForeground,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Large preview/icon
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val isImage = selectedEntry.name.lowercase().let {
                        it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp")
                    }
                    if (isImage) {
                        AsyncImage(
                            model = selectedEntry.location,
                            contentDescription = selectedEntry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val iconKey = if (selectedEntry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else fileIconKey(selectedEntry.name)
                        Icon(
                            key = iconKey,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Metadata
                Text(
                    text = selectedEntry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Type: ${if (selectedEntry.kind == VFileKind.DIRECTORY) "Folder" else selectedEntry.name.substringAfterLast('.', "File").uppercase()}",
                    fontSize = 12.sp,
                    color = palette.mutedForeground
                )

                if (selectedEntry.kind == VFileKind.FILE) {
                    Text(
                        text = "Size: ${formatFileSize(selectedEntry.sizeBytes)}",
                        fontSize = 12.sp,
                        color = palette.mutedForeground
                    )
                }

                Text(
                    text = "Modified: ${formatModifiedTime(selectedEntry.modifiedAtEpochMillis)}",
                    fontSize = 12.sp,
                    color = palette.mutedForeground
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Text preview
                val isText = selectedEntry.name.lowercase().let {
                    it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".log") || it.endsWith(".xml") || it.endsWith(".json") || it.endsWith(".java") || it.endsWith(".kt") || it.endsWith(".js") || it.endsWith(".css") || it.endsWith(".csv") || it.endsWith(".html")
                }

                if (isText && selectedEntry.kind == VFileKind.FILE) {
                    var previewText by remember(selectedEntry.location) { mutableStateOf<String?>("Loading preview...") }
                    
                    LaunchedEffect(selectedEntry.location) {
                        previewText = withContext(Dispatchers.IO) {
                            try {
                                val file = File(selectedEntry.location)
                                if (file.exists() && file.length() < 1024 * 1024) { // Only read files < 1MB
                                    file.bufferedReader().useLines { lines ->
                                        lines.take(100).joinToString("\n")
                                    }
                                } else if (file.length() >= 1024 * 1024) {
                                    "File too large for preview"
                                } else {
                                    "Preview not available"
                                }
                            } catch (e: Exception) {
                                "Preview not available"
                            }
                        }
                    }

                    Text(
                        text = previewText ?: "",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.foreground,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.05f))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
