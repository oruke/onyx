package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory


data class S3ListPage(
    val entries: List<VFile>,
    val nextContinuationToken: String?,
)

class S3ListBucketResultParser {
    fun parse(
        xml: String,
        location: S3Location,
    ): S3ListPage {
        val document = documentBuilderFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val entries = buildList {
            val commonPrefixes = document.getElementsByTagNameNS("*", "CommonPrefixes")
            for (index in 0 until commonPrefixes.length) {
                val element = commonPrefixes.item(index) as? Element ?: continue
                val prefix = element.childText("Prefix") ?: continue
                val name = prefix.trimEnd('/').substringAfterLast('/')
                if (name.isBlank()) continue
                add(
                    VFile(
                        id = location.toLocation(prefix, directory = true),
                        name = name,
                        location = location.toLocation(prefix, directory = true),
                        parentLocation = location.directoryLocation,
                        kind = VFileKind.DIRECTORY,
                        sizeBytes = null,
                        modifiedAtEpochMillis = null,
                        hidden = name.startsWith("."),
                        capabilities = setOf(
                            VFileCapability.READ_METADATA,
                            VFileCapability.LIST_CHILDREN,
                            VFileCapability.DELETE,
                            VFileCapability.RENAME,
                        ),
                    )
                )
            }

            val contents = document.getElementsByTagNameNS("*", "Contents")
            for (index in 0 until contents.length) {
                val element = contents.item(index) as? Element ?: continue
                val key = element.childText("Key") ?: continue
                val relative = key.removePrefix(location.directoryPrefix)
                if (relative.isBlank() || relative.contains('/')) continue
                val name = relative.substringAfterLast('/')
                add(
                    VFile(
                        id = location.toLocation(key, directory = false),
                        name = name,
                        location = location.toLocation(key, directory = false),
                        parentLocation = location.directoryLocation,
                        kind = VFileKind.FILE,
                        sizeBytes = element.childText("Size")?.toLongOrNull(),
                        modifiedAtEpochMillis = element.childText("LastModified")?.toInstantMillisOrNull(),
                        hidden = name.startsWith("."),
                        capabilities = setOf(
                            VFileCapability.READ_METADATA,
                            VFileCapability.READ_CONTENT,
                            VFileCapability.WRITE_CONTENT,
                            VFileCapability.DELETE,
                            VFileCapability.RENAME,
                        ),
                    )
                )
            }
        }.sortedWith(
            compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                .thenBy { entry -> entry.name.lowercase() }
        )
        val isTruncated = document.documentElement.childText("IsTruncated") == "true"
        val nextToken = document.documentElement.childText("NextContinuationToken")
            ?.takeIf { token -> token.isNotBlank() && isTruncated }
        return S3ListPage(entries = entries, nextContinuationToken = nextToken)
    }
}

class S3ErrorParser {
    fun parseCode(xml: String): String? {
        return runCatching {
            documentBuilderFactory().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
                .documentElement
                .childText("Code")
        }.getOrNull()
    }
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

private fun Element.childText(localName: String): String? {
    val nodes = getElementsByTagNameNS("*", localName)
    return nodes.item(0)?.textContent?.trim()
}
