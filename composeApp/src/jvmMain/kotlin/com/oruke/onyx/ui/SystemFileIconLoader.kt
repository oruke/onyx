package com.oruke.onyx.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.oruke.onyx.app.platform.DesktopOperatingSystem
import com.oruke.onyx.app.platform.currentDesktopOperatingSystem
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.swing.filechooser.FileSystemView

/**
 * 操作系统文件与应用关联图标加载器。
 *
 * 采用双层提取策略保证高密度列表滚动零磁盘阻塞：
 * 1. 常规扩展名通过 `SHGetFileInfoW` + `SHGFI_USEFILEATTRIBUTES` 在内存中零磁盘 I/O 查询系统关联图标并按扩展名永久缓存；
 *    若系统仅返回未关联的默认空白页图标，则自动返回 `null` 以便上层回退到内置彩色矢量图标。
 * 2. `.exe`、`.lnk`、`.ico` 等自带实例图标的本地文件在后台 I/O 线程按路径异步提取并做有界缓存。
 */
@Suppress("MagicNumber")
internal object SystemFileIconLoader {
    private const val FILE_ATTRIBUTE_NORMAL = 0x00000080
    private const val SHGFI_ICON = 0x00000100
    private const val SHGFI_USEFILEATTRIBUTES = 0x00000010
    private const val SHGFI_LARGEICON = 0x00000000
    private const val SHGFI_SMALLICON = 0x00000001
    private const val MAX_INSTANCE_CACHE_SIZE = 512
    private const val CACHE_SCHEMA_VERSION = "v2"
    private const val UNASSOCIATED_PROBE_EXT = ".__onyx_unassociated_probe__"

    /** 串行化底层 Win32 Shell/GDI 图标提取，避免多线程并发访问系统图像列表产生竞争或重复提取。 */
    private val nativeIconLock = Any()

    /** 每个文件实例可能拥有独立内嵌图标的扩展名集合。 */
    private val INSTANCE_ICON_EXTENSIONS = setOf(
        "exe", "lnk", "ico", "cur", "ani", "scr", "appref-ms", "url",
    )

    /** 扩展名级图标缓存（键为 `v2:ext:large`），避免同类型文件重复调用原生接口。 */
    private val extensionCache = ConcurrentHashMap<String, Optional<ImageBitmap>>()

    /** 实例路径级有界 LRU 图标缓存（键为 `v2:location:large`）。 */
    private val instanceCache: MutableMap<String, Optional<ImageBitmap>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Optional<ImageBitmap>>(128, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Optional<ImageBitmap>>?,
                ): Boolean = size > MAX_INSTANCE_CACHE_SIZE
            },
        )

    /** Windows 系统图像列表中“未关联未知文件类型”的基准图标索引（smallIconIndex to largeIconIndex）。 */
    private val unknownIconIndices: Pair<Int, Int> by lazy {
        if (currentDesktopOperatingSystem() != DesktopOperatingSystem.WINDOWS) {
            -1 to -1
        } else {
            val smallIdx = queryVirtualExtensionIconIndex(UNASSOCIATED_PROBE_EXT, large = false)
            val largeIdx = queryVirtualExtensionIconIndex(UNASSOCIATED_PROBE_EXT, large = true)
            smallIdx to largeIdx
        }
    }

    /**
     * 判断指定文件名是否需要按具体文件路径提取实例专属图标。
     *
     * @param fileName 文件名称。
     * @param location 文件位置；远程协议位置不支持本地实例提取。
     * @return 需要按路径提取实例图标时返回 `true`。
     */
    fun requiresInstanceLookup(fileName: String, location: String?): Boolean {
        if (location.isNullOrBlank() || location.contains("://")) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in INSTANCE_ICON_EXTENSIONS
    }

    /**
     * 同步读取内存中已缓存的系统图标，供列表首帧无闪烁直出。
     *
     * @param fileName 文件名称。
     * @param location 文件位置。
     * @param large 是否使用大尺寸图标。
     * @return 若缓存已命中则返回对应结果（`Optional.empty()` 代表已确认为无关联图标），未缓存返回 `null`。
     */
    fun getCached(fileName: String, location: String?, large: Boolean): Optional<ImageBitmap>? {
        val cached = if (requiresInstanceLookup(fileName, location)) {
            instanceCache["$CACHE_SCHEMA_VERSION:$location:$large"]
        } else {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) Optional.empty() else extensionCache["$CACHE_SCHEMA_VERSION:$ext:$large"]
        }
        val bitmap = cached?.orElse(null)
        return if (bitmap != null && (bitmap.width <= 1 || bitmap.height <= 1)) {
            null
        } else {
            cached
        }
    }

    /**
     * 异步加载文件对应的系统关联应用图标或实例图标。
     *
     * @param fileName 文件名称。
     * @param location 文件位置。
     * @param large 是否请求大尺寸高清图标。
     * @return 提取到的系统图标；无专属关联图标或提取失败时返回 `null`。
     */
    suspend fun load(
        fileName: String,
        location: String?,
        large: Boolean,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (requiresInstanceLookup(fileName, location)) {
            val key = "$CACHE_SCHEMA_VERSION:$location:$large"
            val fastCached = instanceCache[key]
            if (fastCached != null) return@withContext fastCached.orElse(null)
            synchronized(nativeIconLock) {
                val rechecked = instanceCache[key]
                if (rechecked != null) return@synchronized rechecked.orElse(null)
                val loaded = loadFileInstanceIcon(location.orEmpty(), large)
                instanceCache[key] = Optional.ofNullable(loaded)
                loaded
            }
        } else {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return@withContext null
            val key = "$CACHE_SCHEMA_VERSION:$ext:$large"
            val fastCached = extensionCache[key]
            if (fastCached != null) return@withContext fastCached.orElse(null)
            synchronized(nativeIconLock) {
                val rechecked = extensionCache[key]
                if (rechecked != null) return@synchronized rechecked.orElse(null)
                val loaded = loadExtensionAssociatedIcon(ext, large)
                extensionCache[key] = Optional.ofNullable(loaded)
                loaded
            }
        }
    }

    /**
     * 从可执行文件、动态库或图标文件中按资源索引提取图标。
     *
     * @param filePath 本地文件路径。
     * @param iconIndex 图标索引（支持负数资源 ID 或非负序号）。
     * @param large 是否提取大尺寸图标。
     * @return 提取到的位图；失败时返回 `null`。
     */
    fun extractIndexedOrFileIcon(
        filePath: String,
        iconIndex: Int?,
        large: Boolean = true,
    ): ImageBitmap? {
        val windowsIcon = if (currentDesktopOperatingSystem() == DesktopOperatingSystem.WINDOWS) {
            val indexed = if (iconIndex != null) {
                extractWindowsIndexedIcon(filePath, iconIndex, large)
            } else {
                null
            }
            indexed ?: loadWindowsPathIcon(filePath, large)
        } else {
            null
        }
        return windowsIcon ?: loadSwingSystemIcon(filePath)
    }

    private fun loadExtensionAssociatedIcon(ext: String, large: Boolean): ImageBitmap? {
        if (currentDesktopOperatingSystem() != DesktopOperatingSystem.WINDOWS) return null
        return runCatching {
            ensureWindowsComInitialized()
            val info = ShFileInfo()
            val sizeFlag = if (large) SHGFI_LARGEICON else SHGFI_SMALLICON
            val flags = SHGFI_ICON or SHGFI_USEFILEATTRIBUTES or sizeFlag
            val result = WindowsShell32.INSTANCE.SHGetFileInfoW(
                WString(".$ext"),
                FILE_ATTRIBUTE_NORMAL,
                info,
                info.size(),
                flags,
            )
            val handle = info.hIcon
            if (handle == null) {
                null
            } else {
                try {
                    val unknownIdx = if (large) unknownIconIndices.second else unknownIconIndices.first
                    val isUnknownDefault = unknownIdx >= 0 && info.iIcon == unknownIdx
                    if (result == null || isUnknownDefault) {
                        null
                    } else {
                        hIconToImageBitmap(handle, requireChromatic = true)
                    }
                } finally {
                    User32.INSTANCE.DestroyIcon(handle)
                }
            }
        }.getOrNull()
    }

    private fun loadFileInstanceIcon(path: String, large: Boolean): ImageBitmap? {
        val winIcon = if (currentDesktopOperatingSystem() == DesktopOperatingSystem.WINDOWS) {
            loadWindowsPathIcon(path, large)
        } else {
            null
        }
        return winIcon ?: loadSwingSystemIcon(path)
    }

    private fun loadWindowsPathIcon(path: String, large: Boolean): ImageBitmap? {
        return runCatching {
            if (!File(path).exists()) {
                null
            } else {
                ensureWindowsComInitialized()
                val info = ShFileInfo()
                val sizeFlag = if (large) SHGFI_LARGEICON else SHGFI_SMALLICON
                val result = WindowsShell32.INSTANCE.SHGetFileInfoW(
                    WString(path),
                    0,
                    info,
                    info.size(),
                    SHGFI_ICON or sizeFlag,
                )
                val handle = info.hIcon
                if (handle == null) {
                    null
                } else {
                    try {
                        if (result == null) null else hIconToImageBitmap(handle, requireChromatic = false)
                    } finally {
                        User32.INSTANCE.DestroyIcon(handle)
                    }
                }
            }
        }.getOrNull()
    }

    private fun extractWindowsIndexedIcon(
        filePath: String,
        iconIndex: Int,
        large: Boolean,
    ): ImageBitmap? {
        return runCatching {
            ensureWindowsComInitialized()
            val largeArr = arrayOfNulls<WinDef.HICON>(1)
            val smallArr = arrayOfNulls<WinDef.HICON>(1)
            val extracted = WindowsShell32.INSTANCE.ExtractIconExW(
                WString(filePath),
                iconIndex,
                largeArr,
                smallArr,
                1,
            )
            val target = if (large) largeArr[0] ?: smallArr[0] else smallArr[0] ?: largeArr[0]
            try {
                if (extracted <= 0 || target == null) {
                    null
                } else {
                    hIconToImageBitmap(target, requireChromatic = false)
                }
            } finally {
                largeArr[0]?.let(User32.INSTANCE::DestroyIcon)
                smallArr[0]?.let(User32.INSTANCE::DestroyIcon)
            }
        }.getOrNull()
    }

    private fun queryVirtualExtensionIconIndex(virtualName: String, large: Boolean): Int {
        return runCatching {
            ensureWindowsComInitialized()
            val info = ShFileInfo()
            val sizeFlag = if (large) SHGFI_LARGEICON else SHGFI_SMALLICON
            val result = WindowsShell32.INSTANCE.SHGetFileInfoW(
                WString(virtualName),
                FILE_ATTRIBUTE_NORMAL,
                info,
                info.size(),
                SHGFI_ICON or SHGFI_USEFILEATTRIBUTES or sizeFlag,
            )
            try {
                if (result == null) -1 else info.iIcon
            } finally {
                info.hIcon?.let(User32.INSTANCE::DestroyIcon)
            }
        }.getOrDefault(-1)
    }
}

private fun ensureWindowsComInitialized() {
    runCatching {
        com.sun.jna.platform.win32.Ole32.INSTANCE.CoInitializeEx(
            Pointer.NULL,
            com.sun.jna.platform.win32.Ole32.COINIT_APARTMENTTHREADED,
        )
    }
}

/**
 * 将 Win32 `HICON` 安全转换为 Compose `ImageBitmap`，并严格释放内部 GDI 位图句柄。
 */
@Suppress("MagicNumber")
private fun hIconToImageBitmap(
    hIcon: WinDef.HICON,
    requireChromatic: Boolean = false,
): ImageBitmap? {
    val iconInfo = WinGDI.ICONINFO()
    if (!User32.INSTANCE.GetIconInfo(hIcon, iconInfo)) return null
    val colorBmp = iconInfo.hbmColor
    val maskBmp = iconInfo.hbmMask
    return try {
        decodeIconBitmaps(colorBmp, maskBmp, requireChromatic)
    } finally {
        colorBmp?.let(GDI32.INSTANCE::DeleteObject)
        maskBmp?.let(GDI32.INSTANCE::DeleteObject)
    }
}

@Suppress("MagicNumber")
private fun decodeIconBitmaps(
    colorBmp: WinDef.HBITMAP?,
    maskBmp: WinDef.HBITMAP?,
    requireChromatic: Boolean,
): ImageBitmap? {
    val targetBmp = colorBmp ?: maskBmp
    val bmp = WinGDI.BITMAP()
    if (targetBmp == null || GDI32.INSTANCE.GetObject(targetBmp, bmp.size(), bmp.pointer) == 0) {
        return null
    }
    bmp.read()
    val width = bmp.bmWidth.toInt()
    val rawHeight = bmp.bmHeight.toInt()
    val height = if (colorBmp == null) rawHeight / 2 else rawHeight
    val pixels = if (width > 1 && height > 1) {
        readBitmapPixels(targetBmp, width, height)
    } else {
        null
    }
    return pixels?.let { argbPixels ->
        normalizeAlphaIfOpaqueMask(argbPixels, maskBmp, width, height)
        if (!hasVisibleNonDegeneratePixels(argbPixels, requireChromatic)) {
            null
        } else {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, width, height, argbPixels, 0, width)
            image.toComposeImageBitmap()
        }
    }
}

@Suppress("MagicNumber")
private fun readBitmapPixels(hBitmap: WinDef.HBITMAP, width: Int, height: Int): IntArray? {
    val bmi = WinGDI.BITMAPINFO()
    bmi.bmiHeader.biSize = bmi.bmiHeader.size()
    bmi.bmiHeader.biWidth = width
    bmi.bmiHeader.biHeight = -height
    bmi.bmiHeader.biPlanes = 1
    bmi.bmiHeader.biBitCount = 32
    bmi.bmiHeader.biCompression = WinGDI.BI_RGB
    val buffer = Memory(width.toLong() * height.toLong() * 4L)
    val screenDc = User32.INSTANCE.GetDC(null)
    val copied = try {
        GDI32.INSTANCE.GetDIBits(
            screenDc,
            hBitmap,
            0,
            height,
            buffer,
            bmi,
            WinGDI.DIB_RGB_COLORS,
        )
    } finally {
        User32.INSTANCE.ReleaseDC(null, screenDc)
    }
    return if (copied == 0) null else buffer.getIntArray(0, width * height)
}

@Suppress("MagicNumber")
private fun normalizeAlphaIfOpaqueMask(
    pixels: IntArray,
    maskBmp: WinDef.HBITMAP?,
    width: Int,
    height: Int,
) {
    val hasAlpha = pixels.any { (it ushr 24) != 0 }
    if (hasAlpha) return
    val maskPixels = maskBmp?.let { readBitmapPixels(it, width, height) }
    for (i in pixels.indices) {
        val rgb = pixels[i] and 0x00FFFFFF
        val maskedOut = maskPixels != null && (maskPixels[i] and 0x00FFFFFF) != 0
        pixels[i] = if (maskedOut) 0 else (0xFF000000.toInt() or rgb)
    }
}

@Suppress("MagicNumber")
private fun hasVisibleNonDegeneratePixels(
    pixels: IntArray,
    requireChromatic: Boolean,
): Boolean {
    var visibleCount = 0
    var nonBlackVisibleCount = 0
    var chromaticCount = 0
    for (argb in pixels) {
        val alpha = argb ushr 24
        if (alpha > 16) {
            visibleCount++
            val rgb = argb and 0x00FFFFFF
            if (rgb != 0) {
                nonBlackVisibleCount++
            }
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            if (alpha > 48 && maxChannel - minChannel >= 24) {
                chromaticCount++
            }
        }
    }
    val basicValid = visibleCount > 16 && nonBlackVisibleCount > 8
    return if (requireChromatic) basicValid && chromaticCount >= 6 else basicValid
}

@Suppress("MagicNumber")
private fun loadSwingSystemIcon(path: String): ImageBitmap? {
    return runCatching {
        val file = File(path).takeIf { it.exists() }
        val icon = file?.let { FileSystemView.getFileSystemView().getSystemIcon(it) }
        if (icon == null) {
            null
        } else {
            val width = icon.iconWidth.coerceAtLeast(16)
            val height = icon.iconHeight.coerceAtLeast(16)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g2d = image.createGraphics()
            try {
                icon.paintIcon(null, g2d, 0, 0)
            } finally {
                g2d.dispose()
            }
            image.toComposeImageBitmap()
        }
    }.getOrNull()
}

/** Win32 `SHFILEINFOW` 结构体映射。 */
@Structure.FieldOrder("hIcon", "iIcon", "dwAttributes", "szDisplayName", "szTypeName")
internal class ShFileInfo : Structure() {
    @JvmField var hIcon: WinDef.HICON? = null
    @JvmField var iIcon: Int = 0
    @JvmField var dwAttributes: Int = 0
    @JvmField var szDisplayName: CharArray = CharArray(260)
    @JvmField var szTypeName: CharArray = CharArray(80)
}

/** Win32 `shell32.dll` 图标提取接口绑定。 */
internal interface WindowsShell32 : StdCallLibrary {
    fun SHGetFileInfoW(
        pszPath: WString,
        dwFileAttributes: Int,
        psfi: ShFileInfo,
        cbFileInfo: Int,
        uFlags: Int,
    ): Pointer?

    fun ExtractIconExW(
        lpszFile: WString,
        nIconIndex: Int,
        phiconLarge: Array<WinDef.HICON?>?,
        phiconSmall: Array<WinDef.HICON?>?,
        nIcons: Int,
    ): Int

    companion object {
        val INSTANCE: WindowsShell32 by lazy {
            Native.load("shell32", WindowsShell32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
