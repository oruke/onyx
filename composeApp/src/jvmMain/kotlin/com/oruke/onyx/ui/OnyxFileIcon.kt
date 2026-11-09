@file:Suppress("MatchingDeclarationName")

package com.oruke.onyx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxAppearance

/**
 * Onyx 全品类文件类型图标分类。
 */
@Suppress("MatchingDeclarationName", "MagicNumber")
internal enum class OnyxFileIconCategory(
    val primaryColor: Color,
    val accentColor: Color,
) {
    DIRECTORY(Color(0xFFF59E0B), Color(0xFFFBBF24)),
    ARCHIVE(Color(0xFFD97706), Color(0xFFFBBF24)),
    IMAGE(Color(0xFF10B981), Color(0xFF6EE7B7)),
    VIDEO(Color(0xFF8B5CF6), Color(0xFFC4B5FD)),
    AUDIO(Color(0xFFEC4899), Color(0xFFF9A8D4)),
    PDF(Color(0xFFEF4444), Color(0xFFFCA5A5)),
    WORD_DOC(Color(0xFF2563EB), Color(0xFF93C5FD)),
    SPREADSHEET(Color(0xFF16A34A), Color(0xFF86EFAC)),
    PRESENTATION(Color(0xFFEA580C), Color(0xFFFDBA74)),
    MARKDOWN(Color(0xFF0284C7), Color(0xFF7DD3FC)),
    TEXT(Color(0xFF64748B), Color(0xFFCBD5E1)),
    CODE(Color(0xFF0EA5E9), Color(0xFF7DD3FC)),
    CONFIG_DATA(Color(0xFF0D9488), Color(0xFF5EEAD4)),
    SCRIPT_SHELL(Color(0xFF334155), Color(0xFF38BDF8)),
    EXECUTABLE(Color(0xFF4F46E5), Color(0xFFA5B4FC)),
    AI_MODEL(Color(0xFF7C3AED), Color(0xFFDDD6FE)),
    DATABASE(Color(0xFF0891B2), Color(0xFF67E8F9)),
    DISK_IMAGE(Color(0xFF3B82F6), Color(0xFFBFDBFE)),
    FONT(Color(0xFF9333EA), Color(0xFFD8B4FE)),
    SHORTCUT_LINK(Color(0xFF0284C7), Color(0xFFBAE6FD)),
    GENERIC(Color(0xFF64748B), Color(0xFFE2E8F0)),
}

/**
 * 渲染统一的文件或目录图标：支持混合系统关联应用图标与内置高辨识度彩色矢量图标。
 *
 * @param entry 文件条目。
 * @param size 图标边长。
 * @param modifier 附加修饰符。
 */
@Composable
internal fun OnyxFileIcon(
    entry: VFile?,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    OnyxFileIcon(
        fileName = entry?.name,
        kind = entry?.kind ?: VFileKind.FILE,
        location = entry?.location,
        size = size,
        modifier = modifier,
    )
}

/**
 * 按文件名、类型与可选路径渲染文件图标。
 *
 * @param fileName 文件名称。
 * @param kind 条目类型（目录或文件）。
 * @param location 条目位置（用于 `.exe`/`.lnk` 实例图标读取）。
 * @param size 图标边长。
 * @param modifier 附加修饰符。
 */
@Composable
internal fun OnyxFileIcon(
    fileName: String?,
    kind: VFileKind,
    location: String? = null,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val useSystemIcons = LocalOnyxAppearance.current.useSystemFileIcons
    val large = size >= 24.dp
    if (kind != VFileKind.DIRECTORY && useSystemIcons && !fileName.isNullOrBlank()) {
        val initialCached = remember(fileName, location, large, "v2") {
            SystemFileIconLoader.getCached(fileName, location, large)
        }
        var systemBitmap by remember(fileName, location, large, "v2") {
            mutableStateOf(initialCached?.orElse(null))
        }
        LaunchedEffect(fileName, location, large, "v2") {
            if (initialCached == null) {
                systemBitmap = SystemFileIconLoader.load(fileName, location, large)
            }
        }
        val resolvedBitmap = systemBitmap
        if (resolvedBitmap != null && resolvedBitmap.width > 1 && resolvedBitmap.height > 1) {
            Image(
                bitmap = resolvedBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = modifier.size(size),
            )
            return
        }
    }
    val category = remember(fileName, kind) { resolveFileIconCategory(fileName, kind) }
    OnyxVectorFileTypeIcon(
        category = category,
        size = size,
        modifier = modifier,
    )
}

/**
 * 根据文件名与条目类型解析对应的视觉图标分类。
 *
 * @param fileName 文件名称。
 * @param kind 条目类型。
 * @return 匹配的图标分类。
 */
@Suppress("CyclomaticComplexMethod")
internal fun resolveFileIconCategory(
    fileName: String?,
    kind: VFileKind = VFileKind.FILE,
): OnyxFileIconCategory {
    val lowerName = fileName?.lowercase().orEmpty()
    val ext = lowerName.substringAfterLast('.', "")
    return when {
        kind == VFileKind.DIRECTORY -> OnyxFileIconCategory.DIRECTORY
        lowerName.isBlank() -> OnyxFileIconCategory.GENERIC
        lowerName == "dockerfile" || lowerName == "makefile" || lowerName == "cmakelists.txt" ->
            OnyxFileIconCategory.CODE
        lowerName.startsWith(".env") || lowerName == ".gitignore" || lowerName == ".editorconfig" ->
            OnyxFileIconCategory.CONFIG_DATA
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2", "cab", "lz4") ->
            OnyxFileIconCategory.ARCHIVE
        ext in setOf(
            "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp",
            "ico", "tiff", "tif", "heic", "avif", "raw", "psd", "ai",
        ) -> OnyxFileIconCategory.IMAGE
        ext in setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "mpg", "mpeg", "ts", "m2ts", "3gp") ->
            OnyxFileIconCategory.VIDEO
        ext in setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "ape", "alac", "mid", "midi", "amr") ->
            OnyxFileIconCategory.AUDIO
        ext == "pdf" ->
            OnyxFileIconCategory.PDF
        ext in setOf("doc", "docx", "rtf", "odt", "pages", "wps") ->
            OnyxFileIconCategory.WORD_DOC
        ext in setOf("xls", "xlsx", "csv", "tsv", "ods", "numbers", "et") ->
            OnyxFileIconCategory.SPREADSHEET
        ext in setOf("ppt", "pptx", "odp", "key", "dps") ->
            OnyxFileIconCategory.PRESENTATION
        ext in setOf("md", "mdx", "markdown", "rst", "adoc") ->
            OnyxFileIconCategory.MARKDOWN
        ext in setOf("txt", "log", "nfo", "me", "lic", "license", "readme") ->
            OnyxFileIconCategory.TEXT
        ext in setOf(
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "c", "cpp", "cc", "h", "hpp", "cs", "go", "rs", "rb", "php", "swift",
            "dart", "scala", "lua", "zig", "vue", "svelte", "html", "htm", "css", "scss", "less",
        ) -> OnyxFileIconCategory.CODE
        ext in setOf(
            "json", "jsonc", "json5", "xml", "yaml", "yml",
            "toml", "ini", "conf", "cfg", "properties", "gradle", "plist",
        ) -> OnyxFileIconCategory.CONFIG_DATA
        ext in setOf("sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "vbs", "awk") ->
            OnyxFileIconCategory.SCRIPT_SHELL
        ext in setOf(
            "exe", "msi", "msix", "app", "pkg", "deb", "rpm",
            "apk", "ipa", "appimage", "jar", "dll", "so", "dylib", "sys",
        ) -> OnyxFileIconCategory.EXECUTABLE
        ext in setOf("gguf", "safetensors", "onnx", "pt", "pth", "ckpt", "tflite", "h5", "pkl", "bin") ->
            OnyxFileIconCategory.AI_MODEL
        ext in setOf("db", "sqlite", "sqlite3", "sql", "mdb", "accdb", "ldb", "parquet", "arrow") ->
            OnyxFileIconCategory.DATABASE
        ext in setOf("iso", "img", "vhd", "vhdx", "vmdk", "qcow2", "dmg", "wim") ->
            OnyxFileIconCategory.DISK_IMAGE
        ext in setOf("ttf", "otf", "woff", "woff2", "ttc", "fon") ->
            OnyxFileIconCategory.FONT
        ext in setOf("lnk", "url", "desktop", "webloc") ->
            OnyxFileIconCategory.SHORTCUT_LINK
        else ->
            OnyxFileIconCategory.GENERIC
    }
}

/**
 * 使用 Compose Canvas 绘制高清彩色矢量文件类型图标。
 */
@Composable
@Suppress("MagicNumber", "CyclomaticComplexMethod")
internal fun OnyxVectorFileTypeIcon(
    category: OnyxFileIconCategory,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        if (category == OnyxFileIconCategory.DIRECTORY) {
            drawFolderVector(category.primaryColor, category.accentColor)
        } else {
            drawDocumentBase(category.primaryColor, category.accentColor)
            when (category) {
                OnyxFileIconCategory.ARCHIVE -> drawArchiveGlyph(category.accentColor)
                OnyxFileIconCategory.IMAGE -> drawImageGlyph()
                OnyxFileIconCategory.VIDEO -> drawVideoGlyph()
                OnyxFileIconCategory.AUDIO -> drawAudioGlyph()
                OnyxFileIconCategory.PDF -> drawPdfGlyph()
                OnyxFileIconCategory.WORD_DOC -> drawWordGlyph()
                OnyxFileIconCategory.SPREADSHEET -> drawSpreadsheetGlyph()
                OnyxFileIconCategory.PRESENTATION -> drawPresentationGlyph()
                OnyxFileIconCategory.MARKDOWN -> drawMarkdownGlyph()
                OnyxFileIconCategory.CODE -> drawCodeGlyph()
                OnyxFileIconCategory.CONFIG_DATA -> drawConfigGlyph()
                OnyxFileIconCategory.SCRIPT_SHELL -> drawShellGlyph(category.accentColor)
                OnyxFileIconCategory.EXECUTABLE -> drawExecutableGlyph()
                OnyxFileIconCategory.AI_MODEL -> drawAiModelGlyph(category.accentColor)
                OnyxFileIconCategory.DATABASE -> drawDatabaseGlyph()
                OnyxFileIconCategory.DISK_IMAGE -> drawDiskImageGlyph()
                OnyxFileIconCategory.FONT -> drawFontGlyph()
                OnyxFileIconCategory.SHORTCUT_LINK -> drawShortcutGlyph()
                OnyxFileIconCategory.TEXT,
                OnyxFileIconCategory.GENERIC,
                OnyxFileIconCategory.DIRECTORY -> drawTextLinesGlyph()
            }
        }
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawFolderVector(primary: Color, accent: Color) {
    val w = size.width
    val h = size.height
    val backPath = Path().apply {
        moveTo(w * 0.08f, h * 0.22f)
        lineTo(w * 0.42f, h * 0.22f)
        lineTo(w * 0.52f, h * 0.34f)
        lineTo(w * 0.92f, h * 0.34f)
        lineTo(w * 0.92f, h * 0.84f)
        lineTo(w * 0.08f, h * 0.84f)
        close()
    }
    drawPath(backPath, color = primary)
    drawRoundRect(
        color = accent,
        topLeft = Offset(w * 0.08f, h * 0.34f),
        size = Size(w * 0.84f, h * 0.52f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.45f),
        start = Offset(w * 0.14f, h * 0.41f),
        end = Offset(w * 0.86f, h * 0.41f),
        strokeWidth = (w * 0.045f).coerceAtLeast(1f),
        cap = StrokeCap.Round,
    )
}

@Suppress("MagicNumber")
private fun DrawScope.drawDocumentBase(primary: Color, accent: Color) {
    val w = size.width
    val h = size.height
    val left = w * 0.16f
    val right = w * 0.84f
    val top = h * 0.08f
    val bottom = h * 0.92f
    val fold = w * 0.22f

    val bodyPath = Path().apply {
        moveTo(left, top)
        lineTo(right - fold, top)
        lineTo(right, top + fold)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }
    drawPath(bodyPath, color = primary)

    val earPath = Path().apply {
        moveTo(right - fold, top)
        lineTo(right - fold, top + fold)
        lineTo(right, top + fold)
        close()
    }
    drawPath(earPath, color = accent)
}

@Suppress("MagicNumber")
private fun DrawScope.drawArchiveGlyph(accent: Color) {
    val w = size.width
    val h = size.height
    val cx = w * 0.44f
    val toothW = w * 0.12f
    val toothH = h * 0.065f
    for (idx in 0 until 5) {
        val y = h * (0.22f + idx * 0.11f)
        val x = if (idx % 2 == 0) cx - toothW * 0.5f else cx
        drawRect(color = accent, topLeft = Offset(x, y), size = Size(toothW, toothH))
    }
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(cx - w * 0.07f, h * 0.72f),
        size = Size(w * 0.18f, h * 0.12f),
        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
    )
}

@Suppress("MagicNumber")
private fun DrawScope.drawImageGlyph() {
    val w = size.width
    val h = size.height
    drawCircle(
        color = Color.White,
        radius = w * 0.075f,
        center = Offset(w * 0.36f, h * 0.44f),
    )
    val mountain = Path().apply {
        moveTo(w * 0.24f, h * 0.78f)
        lineTo(w * 0.42f, h * 0.54f)
        lineTo(w * 0.54f, h * 0.68f)
        lineTo(w * 0.64f, h * 0.56f)
        lineTo(w * 0.76f, h * 0.78f)
        close()
    }
    drawPath(mountain, color = Color.White.copy(alpha = 0.92f))
}

@Suppress("MagicNumber")
private fun DrawScope.drawVideoGlyph() {
    val w = size.width
    val h = size.height
    val play = Path().apply {
        moveTo(w * 0.38f, h * 0.40f)
        lineTo(w * 0.66f, h * 0.56f)
        lineTo(w * 0.38f, h * 0.72f)
        close()
    }
    drawPath(play, color = Color.White)
}

@Suppress("MagicNumber")
private fun DrawScope.drawAudioGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.065f).coerceAtLeast(1.2f)
    drawCircle(color = Color.White, radius = w * 0.08f, center = Offset(w * 0.38f, h * 0.70f))
    drawCircle(color = Color.White, radius = w * 0.08f, center = Offset(w * 0.60f, h * 0.65f))
    drawLine(Color.White, Offset(w * 0.44f, h * 0.70f), Offset(w * 0.44f, h * 0.42f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.66f, h * 0.65f), Offset(w * 0.66f, h * 0.37f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.44f, h * 0.42f), Offset(w * 0.66f, h * 0.37f), stroke * 1.2f, StrokeCap.Round)
}

@Suppress("MagicNumber")
private fun DrawScope.drawPdfGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.06f).coerceAtLeast(1.2f)
    val curve = Path().apply {
        moveTo(w * 0.30f, h * 0.72f)
        cubicTo(w * 0.42f, h * 0.34f, w * 0.58f, h * 0.34f, w * 0.70f, h * 0.68f)
        cubicTo(w * 0.54f, h * 0.60f, w * 0.42f, h * 0.62f, w * 0.30f, h * 0.72f)
    }
    drawPath(curve, color = Color.White, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Suppress("MagicNumber")
private fun DrawScope.drawWordGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.06f).coerceAtLeast(1.1f)
    val wPath = Path().apply {
        moveTo(w * 0.28f, h * 0.38f)
        lineTo(w * 0.38f, h * 0.64f)
        lineTo(w * 0.50f, h * 0.45f)
        lineTo(w * 0.62f, h * 0.64f)
        lineTo(w * 0.72f, h * 0.38f)
    }
    drawPath(
        path = wPath,
        color = Color.White,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.85f),
        start = Offset(w * 0.28f, h * 0.76f),
        end = Offset(w * 0.72f, h * 0.76f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

@Suppress("MagicNumber")
private fun DrawScope.drawSpreadsheetGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.05f).coerceAtLeast(1f)
    val left = w * 0.28f
    val right = w * 0.72f
    val top = h * 0.40f
    val bottom = h * 0.76f
    drawRect(Color.White, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Stroke(stroke))
    drawLine(Color.White, Offset(w * 0.48f, top), Offset(w * 0.48f, bottom), stroke)
    drawLine(Color.White, Offset(left, h * 0.52f), Offset(right, h * 0.52f), stroke)
    drawLine(Color.White, Offset(left, h * 0.64f), Offset(right, h * 0.64f), stroke)
}

@Suppress("MagicNumber")
private fun DrawScope.drawPresentationGlyph() {
    val w = size.width
    val h = size.height
    drawRect(Color.White, topLeft = Offset(w * 0.30f, h * 0.58f), size = Size(w * 0.10f, h * 0.20f))
    drawRect(Color.White, topLeft = Offset(w * 0.45f, h * 0.46f), size = Size(w * 0.10f, h * 0.32f))
    drawRect(Color.White, topLeft = Offset(w * 0.60f, h * 0.36f), size = Size(w * 0.10f, h * 0.42f))
}

@Suppress("MagicNumber")
private fun DrawScope.drawMarkdownGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.06f).coerceAtLeast(1.1f)
    val mPath = Path().apply {
        moveTo(w * 0.26f, h * 0.68f)
        lineTo(w * 0.26f, h * 0.42f)
        lineTo(w * 0.39f, h * 0.56f)
        lineTo(w * 0.52f, h * 0.42f)
        lineTo(w * 0.52f, h * 0.68f)
    }
    drawPath(mPath, color = Color.White, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(Color.White, Offset(w * 0.67f, h * 0.42f), Offset(w * 0.67f, h * 0.68f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.59f, h * 0.60f), Offset(w * 0.67f, h * 0.70f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.75f, h * 0.60f), Offset(w * 0.67f, h * 0.70f), stroke, StrokeCap.Round)
}

@Suppress("MagicNumber")
private fun DrawScope.drawCodeGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.065f).coerceAtLeast(1.2f)
    val leftBracket = Path().apply {
        moveTo(w * 0.42f, h * 0.42f)
        lineTo(w * 0.27f, h * 0.56f)
        lineTo(w * 0.42f, h * 0.70f)
    }
    val rightBracket = Path().apply {
        moveTo(w * 0.58f, h * 0.42f)
        lineTo(w * 0.73f, h * 0.56f)
        lineTo(w * 0.58f, h * 0.70f)
    }
    drawPath(leftBracket, Color.White, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(rightBracket, Color.White, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Suppress("MagicNumber")
private fun DrawScope.drawConfigGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.055f).coerceAtLeast(1.1f)
    drawLine(Color.White, Offset(w * 0.28f, h * 0.44f), Offset(w * 0.72f, h * 0.44f), stroke, StrokeCap.Round)
    drawCircle(Color.White, radius = w * 0.065f, center = Offset(w * 0.42f, h * 0.44f))
    drawLine(Color.White, Offset(w * 0.28f, h * 0.64f), Offset(w * 0.72f, h * 0.64f), stroke, StrokeCap.Round)
    drawCircle(Color.White, radius = w * 0.065f, center = Offset(w * 0.58f, h * 0.64f))
}

@Suppress("MagicNumber")
private fun DrawScope.drawShellGlyph(accent: Color) {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.065f).coerceAtLeast(1.2f)
    val prompt = Path().apply {
        moveTo(w * 0.28f, h * 0.42f)
        lineTo(w * 0.46f, h * 0.55f)
        lineTo(w * 0.28f, h * 0.68f)
    }
    drawPath(prompt, color = accent, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(Color.White, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.72f, h * 0.68f), stroke, StrokeCap.Round)
}

@Suppress("MagicNumber")
private fun DrawScope.drawExecutableGlyph() {
    val w = size.width
    val h = size.height
    val s = w * 0.16f
    val gap = w * 0.05f
    val startX = w * 0.31f
    val startY = h * 0.40f
    drawRoundRect(Color.White, Offset(startX, startY), Size(s, s), CornerRadius(w * 0.03f))
    drawRoundRect(Color.White, Offset(startX + s + gap, startY), Size(s, s), CornerRadius(w * 0.03f))
    drawRoundRect(Color.White, Offset(startX, startY + s + gap), Size(s, s), CornerRadius(w * 0.03f))
    drawRoundRect(
        color = Color.White.copy(alpha = 0.75f),
        topLeft = Offset(startX + s + gap, startY + s + gap),
        size = Size(s, s),
        cornerRadius = CornerRadius(w * 0.03f),
    )
}

@Suppress("MagicNumber")
private fun DrawScope.drawAiModelGlyph(accent: Color) {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.05f).coerceAtLeast(1f)
    val topNode = Offset(w * 0.50f, h * 0.38f)
    val leftNode = Offset(w * 0.32f, h * 0.68f)
    val rightNode = Offset(w * 0.68f, h * 0.68f)
    drawLine(accent, topNode, leftNode, stroke)
    drawLine(accent, topNode, rightNode, stroke)
    drawLine(accent, leftNode, rightNode, stroke)
    drawCircle(Color.White, radius = w * 0.07f, center = topNode)
    drawCircle(Color.White, radius = w * 0.07f, center = leftNode)
    drawCircle(Color.White, radius = w * 0.07f, center = rightNode)
}

@Suppress("MagicNumber")
private fun DrawScope.drawDatabaseGlyph() {
    val w = size.width
    val h = size.height
    for (idx in 0 until 3) {
        val topY = h * (0.36f + idx * 0.14f)
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.28f, topY),
            size = Size(w * 0.44f, h * 0.10f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
        )
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawDiskImageGlyph() {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.50f, h * 0.56f)
    drawCircle(Color.White, radius = w * 0.22f, center = center, style = Stroke((w * 0.065f).coerceAtLeast(1.2f)))
    drawCircle(Color.White, radius = w * 0.06f, center = center)
}

@Suppress("MagicNumber")
private fun DrawScope.drawFontGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.065f).coerceAtLeast(1.2f)
    val aPath = Path().apply {
        moveTo(w * 0.30f, h * 0.74f)
        lineTo(w * 0.50f, h * 0.36f)
        lineTo(w * 0.70f, h * 0.74f)
    }
    drawPath(aPath, Color.White, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(Color.White, Offset(w * 0.37f, h * 0.61f), Offset(w * 0.63f, h * 0.61f), stroke, StrokeCap.Round)
}

@Suppress("MagicNumber")
private fun DrawScope.drawShortcutGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.065f).coerceAtLeast(1.2f)
    drawLine(Color.White, Offset(w * 0.32f, h * 0.68f), Offset(w * 0.66f, h * 0.42f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.46f, h * 0.42f), Offset(w * 0.66f, h * 0.42f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.66f, h * 0.42f), Offset(w * 0.66f, h * 0.62f), stroke, StrokeCap.Round)
}

@Suppress("MagicNumber")
private fun DrawScope.drawTextLinesGlyph() {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.055f).coerceAtLeast(1f)
    val lineColor = Color.White.copy(alpha = 0.9f)
    drawLine(lineColor, Offset(w * 0.28f, h * 0.44f), Offset(w * 0.72f, h * 0.44f), stroke, StrokeCap.Round)
    drawLine(lineColor, Offset(w * 0.28f, h * 0.58f), Offset(w * 0.72f, h * 0.58f), stroke, StrokeCap.Round)
    drawLine(lineColor, Offset(w * 0.28f, h * 0.72f), Offset(w * 0.56f, h * 0.72f), stroke, StrokeCap.Round)
}
