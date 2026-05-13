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


data class S3Location(
    val bucket: String,
    val prefix: String,
) {
    val objectKey: String = prefix.trimStart('/')

    val directoryPrefix: String = prefix.trim('/').let { value ->
        if (value.isBlank()) "" else "$value/"
    }

    val directoryLocation: String
        get() = toLocation(directoryPrefix, directory = true)

    fun toLocation(
        key: String,
        directory: Boolean,
    ): String {
        val path = if (directory) key.withTrailingSlash() else key
        return URI("s3", bucket, "/$path", null).toASCIIString()
    }

    companion object {
        fun parse(location: String): S3Location {
            val uri = URI(location.encodeSpaces())
            val bucket = uri.host
            if (bucket.isNullOrBlank()) {
                throw VfsProviderNotFoundException(location)
            }
            val prefix = uri.path
                .removePrefix("/")
                .trimStart('/')
            return S3Location(bucket = bucket, prefix = prefix)
        }
    }
}
