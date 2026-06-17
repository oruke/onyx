package com.oruke.onyx.vfs.s3

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


internal fun S3Location.childObject(name: String): S3Location {
    return copy(prefix = directoryPrefix + name)
}

/**
 * 将 S3 对象位置转换为文件条目。
 *
 * @param name 文件名。
 * @param parentLocation 父目录位置。
 * @param sizeBytes 已写入字节数。
 * @return S3 文件条目。
 */
internal fun S3Location.toObjectVFile(
    name: String,
    parentLocation: String,
    sizeBytes: Long?,
): VFile {
    val location = toLocation(objectKey, directory = false)
    return VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = parentLocation,
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = null,
        hidden = name.startsWith("."),
        capabilities = setOf(
            VFileCapability.READ_METADATA,
            VFileCapability.READ_CONTENT,
            VFileCapability.WRITE_CONTENT,
            VFileCapability.DELETE,
            VFileCapability.RENAME,
        ),
    )
}

/**
 * 将 S3 前缀位置转换为目录条目。
 *
 * @param name 目录名。
 * @param parentLocation 父目录位置。
 * @return S3 目录条目。
 */
internal fun S3Location.toDirectoryVFile(
    name: String,
    parentLocation: String,
): VFile {
    return VFile(
        id = directoryLocation,
        name = name,
        location = directoryLocation,
        parentLocation = parentLocation,
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
}

internal fun String.toInstantMillisOrNull(): Long? {
    return runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
}
