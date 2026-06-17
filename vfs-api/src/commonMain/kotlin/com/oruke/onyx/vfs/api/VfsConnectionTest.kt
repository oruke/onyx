package com.oruke.onyx.vfs.api

data class VfsConnectionTestRequest(
    val protocol: VfsProtocol,
    val location: String,
    val authContext: VfsAuthContext = VfsAuthContext.None,
)

sealed interface VfsConnectionTestResult {
    val protocol: VfsProtocol
    val location: String

    data class Reachable(
        override val protocol: VfsProtocol,
        override val location: String,
        val capabilities: Set<VfsProviderCapability>,
    ) : VfsConnectionTestResult

    data class Failed(
        override val protocol: VfsProtocol,
        override val location: String,
        val error: VfsProviderError,
    ) : VfsConnectionTestResult
}

interface VfsConnectionTester {
    val protocol: VfsProtocol

    fun supports(location: String): Boolean

    suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult
}

interface VfsConnectionTestService {
    suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult
}

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
