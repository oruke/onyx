package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLException
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
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                val href = response.childText("href") ?: continue
                val hrefUri = requestUri.resolve(href.encodeVfsSpaces())
                val hrefPath = hrefUri.path.ifBlank { "/" }
                if (hrefPath.withVfsTrailingSlash() == requestPath) {
                    continue
                }
                val prop = response.successProp() ?: continue
                val directory = prop.hasCollectionType()
                val displayName = prop.childText("displayname")
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: hrefPath.trimEnd('/').substringAfterLast('/').urlDecode()
                val location = hrefUri.toWebDavLocation(requestLocationUri.scheme, directory)
                add(
                    VFile(
                        id = location,
                        name = displayName.trimEnd('/'),
                        location = location,
                        parentLocation = parentLocation,
                        kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
                        sizeBytes = if (directory) null else prop.childText("getcontentlength")?.toLongOrNull(),
                        modifiedAtEpochMillis = prop.childText("getlastmodified")?.toEpochMillisOrNull(),
                        hidden = displayName.startsWith("."),
                        capabilities = buildSet {
                            add(VFileCapability.READ_METADATA)
                            add(VFileCapability.RENAME)
                            add(VFileCapability.DELETE)
                            if (directory) {
                                add(VFileCapability.LIST_CHILDREN)
                            } else {
                                add(VFileCapability.READ_CONTENT)
                                add(VFileCapability.WRITE_CONTENT)
                            }
                        },
                    )
                )
            }
        }.sortedWith(
            compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                .thenBy { entry -> entry.name.lowercase() }
        )
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
