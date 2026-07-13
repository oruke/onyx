package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.S3ConnectionConfig

/**
 * VFS 连接测试请求。
 */
data class VfsConnectionTestRequest(
    /** 待测试的协议。 */
    val protocol: VfsProtocol,

    /** 待测试的连接位置。 */
    val location: String,

    /** 本次测试使用的认证上下文。 */
    val authContext: VfsAuthContext = VfsAuthContext.None,

    /** S3 测试专用 Endpoint 与寻址配置；其他协议保持为空。 */
    val s3ConnectionConfig: S3ConnectionConfig? = null,
)

/**
 * VFS 连接测试结果。
 */
sealed interface VfsConnectionTestResult {
    /** 测试使用的协议。 */
    val protocol: VfsProtocol

    /** 测试使用的连接位置。 */
    val location: String

    /**
     * 连接可达。
     */
    data class Reachable(
        /** 已验证的协议。 */
        override val protocol: VfsProtocol,

        /** 已验证的连接位置。 */
        override val location: String,

        /** 目标 Provider 支持的能力。 */
        val capabilities: Set<VfsProviderCapability>,
    ) : VfsConnectionTestResult

    /**
     * 连接测试失败。
     */
    data class Failed(
        /** 测试使用的协议。 */
        override val protocol: VfsProtocol,

        /** 测试使用的连接位置。 */
        override val location: String,

        /** 结构化失败原因。 */
        val error: VfsProviderError,
    ) : VfsConnectionTestResult
}

/**
 * 单一协议的连接测试器。
 */
interface VfsConnectionTester {
    /** 测试器负责的协议。 */
    val protocol: VfsProtocol

    /**
     * 判断测试器能否处理指定连接位置。
     *
     * @param location 待判断的连接位置。
     * @return 可以处理时返回 `true`。
     */
    fun supports(location: String): Boolean

    /**
     * 测试远程连接并返回结构化结果。
     *
     * @param request 连接测试请求。
     * @return 连接测试结果。
     */
    suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult
}

/**
 * 按协议和连接位置调度连接测试的统一服务。
 */
interface VfsConnectionTestService {
    /**
     * 执行连接测试。
     *
     * @param request 连接测试请求。
     * @return 连接测试结果。
     */
    suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult
}

/**
 * 基于已注册测试器实现的连接测试服务。
 *
 * @param testers 可用的协议测试器。
 */
class ProviderBackedVfsConnectionTestService(
    testers: List<VfsConnectionTester>,
) : VfsConnectionTestService {
    private val testers = testers.toList()

    override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        val tester = testers.firstOrNull { candidate ->
            candidate.protocol == request.protocol && candidate.supports(request.location)
        } ?: return VfsConnectionTestResult.Failed(
            protocol = request.protocol,
            location = request.location,
            error = VfsProviderError.UnsupportedOperation(
                protocol = request.protocol,
                location = request.location,
                capability = null,
            )
        )
        return tester.testConnection(request)
    }
}

/**
 * 将异常转换为统一的连接测试失败结果。
 *
 * @param protocol 测试使用的协议。
 * @param location 测试使用的连接位置。
 * @return 保留 VFS 语义的失败结果。
 */
fun Throwable.toVfsConnectionTestResult(
    protocol: VfsProtocol,
    location: String,
): VfsConnectionTestResult.Failed {
    val error = (this as? VfsProviderException)?.error
        ?: VfsProviderError.NetworkFailure(
            protocol = protocol,
            location = location,
            reason = message,
        )
    return VfsConnectionTestResult.Failed(
        protocol = protocol,
        location = location,
        error = error,
    )
}
