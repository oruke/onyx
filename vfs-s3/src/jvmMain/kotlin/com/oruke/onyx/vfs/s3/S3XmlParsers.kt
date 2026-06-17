package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S3 单页列表结果。
 *
 * @property entries 当前页条目。
 * @property nextContinuationToken 下一页 token。
 */
data class S3ListPage(
    val entries: List<VFile>,
    val nextContinuationToken: String?,
)

/**
 * S3 ListObjectsV2 XML 解析器。
 */
class S3ListBucketResultParser {
    /**
     * 解析 S3 ListObjectsV2 XML。
     *
     * @param xml 响应 XML。
     * @param location 当前 S3 目录位置。
     * @return 单页列表结果。
     */
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
                if (key == location.directoryPrefix || key.endsWith("/")) continue
                val name = key.substringAfterLast('/')
                if (name.isBlank()) continue
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
                            VFileCapability.DELETE,
                            VFileCapability.RENAME,
                        ),
                    )
                )
            }
        }
        val nextToken = document.documentElement.childText("NextContinuationToken")
        return S3ListPage(entries = entries, nextContinuationToken = nextToken)
    }
}

/**
 * S3 错误响应解析器。
 */
class S3ErrorParser {
    /**
     * 解析 S3 错误 code。
     *
     * @param xml 错误响应 XML。
     * @return 错误 code；解析失败时返回 null。
     */
    fun parseCode(xml: String): String? {
        return runCatching {
            documentBuilderFactory().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
                .documentElement
                .childText("Code")
        }.getOrNull()
    }
}

/**
 * 创建安全 XML 解析器工厂，禁用外部实体以避免读取远程 XML 时触发 XXE。
 *
 * @return XML 解析器工厂。
 */
private fun documentBuilderFactory(): DocumentBuilderFactory {
    return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
}

/**
 * 读取 XML 子节点文本。
 *
 * @param localName 子节点本地名。
 * @return 子节点文本。
 */
private fun Element.childText(localName: String): String? {
    val nodes = getElementsByTagNameNS("*", localName)
    return nodes.item(0)?.textContent?.trim()
}
