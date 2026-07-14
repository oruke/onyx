package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.SystemQuickAccessLocation
import org.w3c.dom.Element
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 读取 Linux 桌面文件选择器共享的 XDG 用户目录、GTK 书签与 KDE Places。
 *
 * @param homeDirectory 当前用户主目录。
 * @param environment 当前进程环境变量，允许测试覆盖 XDG 路径。
 */
internal class LinuxPlacesQuickAccessSource(
    private val homeDirectory: Path,
    private val environment: Map<String, String> = System.getenv(),
) : SystemQuickAccessSource {
    /**
     * 按 Linux Places 常用顺序合并标准目录与用户书签。
     *
     * @return Linux 桌面快速访问位置。
     */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> = runCatching {
        val configHome = environment.pathOrDefault("XDG_CONFIG_HOME", homeDirectory.resolve(".config"))
        val dataHome = environment.pathOrDefault("XDG_DATA_HOME", homeDirectory.resolve(".local/share"))
        buildList {
            addAll(readXdgUserDirectories(configHome.resolve("user-dirs.dirs"), homeDirectory))
            gtkBookmarkFiles(configHome, homeDirectory).forEach { bookmarkFile ->
                addAll(readGtkBookmarks(bookmarkFile))
            }
            addAll(readKdePlaces(dataHome.resolve("user-places.xbel")))
        }.distinctBy { location -> location.location }
    }
}

/**
 * 从环境变量读取绝对路径，缺失时使用默认路径。
 *
 * @param key 环境变量名称。
 * @param defaultPath 缺失或空值时的默认路径。
 * @return 规范化后的路径。
 */
private fun Map<String, String>.pathOrDefault(key: String, defaultPath: Path): Path {
    return get(key)
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.normalize()
        ?: defaultPath.normalize()
}

/**
 * 构造 GTK 3、GTK 4 与旧版 GTK 书签文件候选路径。
 *
 * @param configHome XDG 配置目录。
 * @param homeDirectory 当前用户主目录。
 * @return 按桌面环境优先级排列的书签文件。
 */
private fun gtkBookmarkFiles(configHome: Path, homeDirectory: Path): List<Path> {
    return listOf(
        configHome.resolve("gtk-4.0/bookmarks"),
        configHome.resolve("gtk-3.0/bookmarks"),
        homeDirectory.resolve(".gtk-bookmarks"),
    )
}

/**
 * 读取 XDG 用户目录配置中的真实目录。
 *
 * @param configFile user-dirs.dirs 配置文件。
 * @param homeDirectory 当前用户主目录。
 * @return 当前存在的 XDG 用户目录。
 */
internal fun readXdgUserDirectories(
    configFile: Path,
    homeDirectory: Path,
): List<SystemQuickAccessLocation> {
    if (!Files.isRegularFile(configFile)) return emptyList()
    return Files.readAllLines(configFile, StandardCharsets.UTF_8)
        .mapNotNull { line -> parseXdgUserDirectoryLine(line, homeDirectory) }
        .filter { location -> Files.isDirectory(Path.of(location.location)) }
}

/**
 * 解析一行 XDG 用户目录定义，不执行其中的 Shell 内容。
 *
 * @param line 配置文件原始行。
 * @param homeDirectory 当前用户主目录，用于展开 `$HOME`。
 * @return 有效目录位置；注释或不支持的表达式返回 null。
 */
internal fun parseXdgUserDirectoryLine(
    line: String,
    homeDirectory: Path,
): SystemQuickAccessLocation? {
    val rawValue = XDG_USER_DIRECTORY_PATTERN.matchEntire(line.trim())?.groupValues?.get(1) ?: return null
    val expandedValue = rawValue
        .replace("\$HOME", homeDirectory.toString())
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
    val path = runCatching {
        val candidate = Path.of(expandedValue)
        (if (candidate.isAbsolute) candidate else homeDirectory.resolve(candidate)).normalize().toAbsolutePath()
    }.getOrNull() ?: return null
    return SystemQuickAccessLocation(displayName = null, location = path.toString())
}

/**
 * 读取 GTK 书签文件。
 *
 * @param bookmarkFile GTK bookmarks 文件。
 * @return 可由 Onyx 打开的 GTK 书签位置。
 */
internal fun readGtkBookmarks(bookmarkFile: Path): List<SystemQuickAccessLocation> {
    if (!Files.isRegularFile(bookmarkFile)) return emptyList()
    return Files.readAllLines(bookmarkFile, StandardCharsets.UTF_8)
        .mapNotNull(::parseGtkBookmarkLine)
}

/**
 * 解析一行 GTK 书签。
 *
 * @param line 由 URI 和可选显示名称组成的书签行。
 * @return 支持的本地、SMB 或 WebDAV 位置。
 */
internal fun parseGtkBookmarkLine(line: String): SystemQuickAccessLocation? {
    val trimmedLine = line.trim()
    if (trimmedLine.isEmpty() || trimmedLine.startsWith('#')) return null
    val separatorIndex = trimmedLine.indexOfFirst(Char::isWhitespace)
    val locationUri = if (separatorIndex < 0) trimmedLine else trimmedLine.substring(0, separatorIndex)
    val displayName = if (separatorIndex < 0) {
        null
    } else {
        trimmedLine.substring(separatorIndex).trim().takeIf(String::isNotEmpty)
    }
    return quickAccessLocationFromUri(locationUri, displayName)
}

/**
 * 读取 KDE user-places.xbel 书签。
 *
 * @param placesFile KDE Places XBEL 文件。
 * @return 可由 Onyx 打开的 KDE 位置。
 */
internal fun readKdePlaces(placesFile: Path): List<SystemQuickAccessLocation> {
    if (!Files.isRegularFile(placesFile)) return emptyList()
    val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(placesFile.toFile())
    val bookmarkNodes = document.getElementsByTagNameNS("*", "bookmark")
    return buildList {
        repeat(bookmarkNodes.length) { index ->
            val bookmark = bookmarkNodes.item(index) as? Element ?: return@repeat
            if (bookmark.isHiddenKdePlace()) return@repeat
            val locationUri = bookmark.getAttribute("href").takeIf(String::isNotBlank) ?: return@repeat
            val titleNodes = bookmark.getElementsByTagNameNS("*", "title")
            val displayName = titleNodes.item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)
            quickAccessLocationFromUri(locationUri, displayName)?.let(::add)
        }
    }
}

/**
 * 判断 KDE Places 书签是否被用户标记为隐藏。
 *
 * @return 元数据包含值为 true 的 isHidden 节点时返回 true。
 */
private fun Element.isHiddenKdePlace(): Boolean {
    val hiddenNodes = getElementsByTagNameNS("*", "isHidden")
    return (0 until hiddenNodes.length).any { index ->
        hiddenNodes.item(index)?.textContent?.trim()?.equals("true", ignoreCase = true) == true
    }
}

/**
 * 创建禁用外部实体与 DTD 的 XBEL 解析器工厂。
 *
 * @return 可安全解析本地 KDE Places 文件的工厂。
 */
private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
    return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}

/**
 * 将桌面书签 URI 转换为统一 VFS 位置。
 *
 * @param locationUri 书签 URI。
 * @param displayName 桌面环境提供的可选显示名称。
 * @return 支持的位置；未知协议或非法 URI 返回 null。
 */
internal fun quickAccessLocationFromUri(
    locationUri: String,
    displayName: String?,
): SystemQuickAccessLocation? {
    val uri = runCatching { URI(locationUri) }.getOrNull() ?: return null
    val location = when (uri.scheme?.lowercase()) {
        "file" -> runCatching { Path.of(uri).normalize().toAbsolutePath().toString() }.getOrNull()
        "smb" -> uri.normalize().toString()
        "dav" -> uri.withScheme("webdav")
        "davs" -> uri.withScheme("webdavs")
        else -> null
    } ?: return null
    return SystemQuickAccessLocation(
        displayName = displayName,
        location = location,
    )
}

/**
 * 替换 URI 协议并保留主机、端口、路径和查询信息。
 *
 * @param scheme 目标 VFS 协议。
 * @return 使用目标协议的 URI 文本。
 */
private fun URI.withScheme(scheme: String): String {
    return URI(scheme, userInfo, host, port, path, query, fragment).toASCIIString()
}

/** XDG 用户目录的受限赋值语法。 */
private val XDG_USER_DIRECTORY_PATTERN = Regex("^XDG_[A-Z0-9_]+_DIR=\"(.*)\"$")
