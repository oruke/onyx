package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.io.IOException

/** WebDAV 列表请求成功状态。 */
internal val ListSuccessStatuses = setOf(HttpStatusCode.OK, HttpStatusCode.MultiStatus)

/** WebDAV 写入请求成功状态。 */
internal val MutationSuccessStatuses = setOf(HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.NoContent)

/** WebDAV 下载请求成功状态。 */
internal val DownloadSuccessStatuses = setOf(HttpStatusCode.OK, HttpStatusCode.PartialContent)

/**
 * 校验 WebDAV HTTP 响应并转换为统一 VFS 错误。
 *
 * @param location 请求位置。
 * @param authContext 请求使用的认证上下文。
 * @param acceptedStatuses 当前操作允许的成功状态。
 */
internal fun HttpResponse.requireSuccess(
    location: String,
    authContext: VfsAuthContext,
    acceptedStatuses: Set<HttpStatusCode> = ListSuccessStatuses,
) {
    if (status in acceptedStatuses) return
    val error = when (status) {
        HttpStatusCode.Unauthorized -> if (authContext == VfsAuthContext.None) {
            VfsProviderError.AuthenticationRequired(VfsProtocol.WEBDAV, location)
        } else {
            VfsProviderError.AuthenticationRejected(VfsProtocol.WEBDAV, location)
        }
        HttpStatusCode.Forbidden -> VfsProviderError.PermissionDenied(VfsProtocol.WEBDAV, location)
        HttpStatusCode.NotFound -> VfsProviderError.NotFound(VfsProtocol.WEBDAV, location)
        HttpStatusCode.PreconditionFailed -> VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, location)
        else -> if (status.value / STATUS_FAMILY_DIVISOR == SERVER_ERROR_STATUS_FAMILY) {
            VfsProviderError.NetworkFailure(VfsProtocol.WEBDAV, location, status.description)
        } else {
            VfsProviderError.UnsupportedOperation(VfsProtocol.WEBDAV, location, null)
        }
    }
    throw VfsProviderException(error)
}

/**
 * 将底层网络 I/O 异常包装为保留 cause 的 WebDAV VFS 异常。
 *
 * @param location 请求位置。
 * @return 统一 VFS 异常。
 */
internal fun IOException.toNetworkFailure(location: String): VfsProviderException {
    return VfsProviderException(
        VfsProviderError.NetworkFailure(
            protocol = VfsProtocol.WEBDAV,
            location = location,
            reason = message,
        ),
        this,
    )
}

/** HTTP 状态族除数。 */
private const val STATUS_FAMILY_DIVISOR = 100

/** 服务器错误状态族。 */
private const val SERVER_ERROR_STATUS_FAMILY = 5
