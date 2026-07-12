package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV `207 Multi-Status` 响应解析器。
 */
class WebDavMultiStatusParser {
    /**
     * 解析 `PROPFIND` 响应并转换为统一的 [VFile] 列表。
     *
     * @param xml WebDAV 服务端返回的 XML 响应。
     * @param requestLocation 发起请求时使用的 WebDAV VFS 地址。
     * @param requestHttpUrl 发起请求时使用的 HTTP 地址。
     * @return 当前目录下的直接子条目。
     */
    fun parse(
        xml: String,
        requestLocation: String,
        requestHttpUrl: String,
    ): List<VFile> {
        val requestUri = URI(requestHttpUrl)
        val requestPath = requestUri.path.ifBlank { "/" }.withVfsTrailingSlash()
        val requestLocationUri = URI(requestLocation.encodeVfsSpaces())
        val parentLocation = requestLocation.withVfsTrailingSlash()
        val document = documentBuilderFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val responses = document.getElementsByTagNameNS("*", "response")
        return responses.mapElements { response ->
            response.toWebDavVFile(
                requestUri = requestUri,
                requestPath = requestPath,
                sourceScheme = requestLocationUri.scheme,
                parentLocation = parentLocation,
            )
        }.sortedWith(
            compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                .thenBy { entry -> entry.name.lowercase() }
        )
    }

    /**
     * 将单个 WebDAV response 节点转换为 VFS 文件。
     *
     * @param requestUri 请求 HTTP 地址。
     * @param requestPath 请求目录路径。
     * @param sourceScheme 原始 WebDAV VFS scheme。
     * @param parentLocation 父目录 VFS 位置。
     * @return 当前目录直接子条目；无效或表示当前目录时返回 `null`。
     */
    private fun Element.toWebDavVFile(
        requestUri: URI,
        requestPath: String,
        sourceScheme: String,
        parentLocation: String,
    ): VFile? {
        val hrefUri = childText("href")?.let { href -> requestUri.resolve(href.encodeVfsSpaces()) }
        val hrefPath = hrefUri?.path?.ifBlank { "/" }
        val prop = successProp()
        val completeResponse = hrefUri != null && hrefPath != null && prop != null
        val representsRequestDirectory = hrefPath?.withVfsTrailingSlash() == requestPath
        return if (completeResponse && !representsRequestDirectory) {
            checkNotNull(hrefUri)
            checkNotNull(hrefPath)
            checkNotNull(prop)
            val directory = prop.hasCollectionType()
            val displayName = prop.childText("displayname")
                ?.takeIf { value -> value.isNotBlank() }
                ?: hrefPath.trimEnd('/').substringAfterLast('/').urlDecode()
            val location = hrefUri.toWebDavLocation(sourceScheme, directory)
            VFile(
                id = location,
                name = displayName.trimEnd('/'),
                location = location,
                parentLocation = parentLocation,
                kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
                sizeBytes = if (directory) null else prop.childText("getcontentlength")?.toLongOrNull(),
                modifiedAtEpochMillis = prop.childText("getlastmodified")?.toEpochMillisOrNull(),
                hidden = displayName.startsWith("."),
                capabilities = prop.toCapabilities(directory),
            )
        } else {
            null
        }
    }

    /**
     * 构建 WebDAV 条目能力集合。
     *
     * @param directory 是否为目录。
     * @return VFS 条目能力集合。
     */
    private fun Element.toCapabilities(directory: Boolean): Set<VFileCapability> {
        return buildSet {
            add(VFileCapability.READ_METADATA)
            add(VFileCapability.RENAME)
            add(VFileCapability.DELETE)
            if (directory) {
                add(VFileCapability.LIST_CHILDREN)
            } else {
                add(VFileCapability.READ_CONTENT)
                add(VFileCapability.WRITE_CONTENT)
            }
        }
    }

    /**
     * 映射 XML 元素节点并过滤无效结果。
     *
     * @param transform 元素转换函数。
     * @return 转换后的有效结果。
     */
    private inline fun <T> NodeList.mapElements(transform: (Element) -> T?): List<T> {
        return (0 until length)
            .mapNotNull { index -> item(index) as? Element }
            .mapNotNull(transform)
    }

    private fun documentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    }

    private fun Element.successProp(): Element? {
        val propStats = getElementsByTagNameNS("*", "propstat")
        for (index in 0 until propStats.length) {
            val propStat = propStats.item(index) as? Element ?: continue
            val status = propStat.childText("status")
            if (status == null || status.contains(" 200 ")) {
                val props = propStat.getElementsByTagNameNS("*", "prop")
                return props.item(0) as? Element
            }
        }
        return null
    }

    private fun Element.hasCollectionType(): Boolean {
        val resourceTypes = getElementsByTagNameNS("*", "resourcetype")
        val resourceType = resourceTypes.item(0) as? Element ?: return false
        return resourceType.getElementsByTagNameNS("*", "collection").length > 0
    }

    private fun Element.childText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return nodes.item(0)?.textContent?.trim()
    }

    private fun URI.toWebDavLocation(
        sourceScheme: String,
        directory: Boolean,
    ): String {
        val scheme = if (sourceScheme.equals("webdavs", ignoreCase = true)) "webdavs" else "webdav"
        val path = path.ifBlank { "/" }.let { value ->
            if (directory) value.withVfsTrailingSlash() else value
        }
        return URI(scheme, null, host, port, path, null, null).toASCIIString()
    }
}
