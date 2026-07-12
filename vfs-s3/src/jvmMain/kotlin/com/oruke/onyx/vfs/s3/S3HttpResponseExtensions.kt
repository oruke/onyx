package com.oruke.onyx.vfs.s3

import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.IOException

/**
 * 校验 S3 列表或读取响应并返回响应正文。
 *
 * @param location 请求位置。
 * @return 成功响应正文。
 */
internal suspend fun HttpResponse.requireSuccess(location: String): String {
    val body = bodyAsText()
    if (status == HttpStatusCode.OK) return body
    val error = when {
        status == HttpStatusCode.BadRequest -> VfsProviderError.UnsupportedOperation(
            protocol = VfsProtocol.S3,
            location = location,
            capability = null,
        )
        status == HttpStatusCode.Unauthorized -> VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location)
        status == HttpStatusCode.Forbidden -> body.toS3Error(location)
        status == HttpStatusCode.NotFound -> VfsProviderError.NotFound(VfsProtocol.S3, location)
        else -> VfsProviderError.NetworkFailure(
            protocol = VfsProtocol.S3,
            location = location,
            reason = status.description,
        )
    }
    throw VfsProviderException(error)
}

/**
 * 校验 S3 对象读取响应。
 *
 * @param location 对象位置。
 */
internal suspend fun HttpResponse.requireObjectSuccess(location: String) {
    if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent) {
        requireSuccess(location)
    }
}

/**
 * 校验 S3 写入类请求响应。
 *
 * @param location 写入对象位置。
 */
internal suspend fun HttpResponse.requireMutationSuccess(location: String) {
    val successful = status == HttpStatusCode.OK ||
        status == HttpStatusCode.Created ||
        status == HttpStatusCode.NoContent
    if (!successful) requireSuccess(location)
}

/**
 * 校验 S3 删除请求响应。
 *
 * @param location 删除对象位置。
 */
internal suspend fun HttpResponse.requireDeleteSuccess(location: String) {
    val successful = status == HttpStatusCode.OK ||
        status == HttpStatusCode.Accepted ||
        status == HttpStatusCode.NoContent
    if (!successful) requireSuccess(location)
}

/**
 * 将 S3 错误 XML 转换为统一错误模型。
 *
 * @param location 请求位置。
 * @return 统一错误模型。
 */
private fun String.toS3Error(location: String): VfsProviderError {
    val code = S3ErrorParser().parseCode(this)
    return when (code) {
        "InvalidAccessKeyId",
        "SignatureDoesNotMatch",
        "ExpiredToken",
        "InvalidToken",
        -> VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location, code)
        "AccessDenied" -> VfsProviderError.PermissionDenied(VfsProtocol.S3, location, code)
        "NoSuchBucket",
        "NoSuchKey",
        -> VfsProviderError.NotFound(VfsProtocol.S3, location)
        else -> VfsProviderError.NetworkFailure(VfsProtocol.S3, location, code)
    }
}

/**
 * 将底层网络 I/O 异常包装为保留 cause 的 S3 VFS 异常。
 *
 * @param location 请求位置。
 * @return 统一 VFS 异常。
 */
internal fun IOException.toS3NetworkException(location: String): VfsProviderException {
    return VfsProviderException(
        VfsProviderError.NetworkFailure(
            protocol = VfsProtocol.S3,
            location = location,
            reason = message,
        ),
        this,
    )
}
