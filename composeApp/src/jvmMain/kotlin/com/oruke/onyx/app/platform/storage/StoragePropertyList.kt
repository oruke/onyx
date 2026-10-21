package com.oruke.onyx.app.platform.storage

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * 解析系统工具输出的 XML plist，只接受内联数据，禁止网络 DTD 和外部实体。
 *
 * @param xml 系统设备查询返回的 plist 文本。
 * @return plist 根字典。
 */
internal fun readStoragePropertyList(xml: String): Map<String, Any?> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    @Suppress("UNCHECKED_CAST")
    return readPlistElement(document.documentElement.childElements().first()) as Map<String, Any?>
}

/**
 * 按 plist 的结构化类型读取节点，不通过名称或显示文本推断设备状态。
 *
 * @param element 字典、数组或标量节点。
 * @return 对应 Kotlin 值。
 */
private fun readPlistElement(element: Element): Any? =
    when (element.tagName) {
        "dict" ->
            element.childElements().chunked(2).associate {
                it[0].textContent to readPlistElement(it[1])
            }
        "array" -> element.childElements().map(::readPlistElement)
        "integer" -> element.textContent.toLong()
        "true" -> true
        "false" -> false
        else -> element.textContent
    }

/** @return 当前节点的直接元素子节点，忽略格式化空白。 */
private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
