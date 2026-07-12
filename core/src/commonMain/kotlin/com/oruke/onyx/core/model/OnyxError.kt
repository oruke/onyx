package com.oruke.onyx.core.model

/** 文件管理核心层可返回的结构化错误。 */
sealed interface OnyxError {
    /** 可选错误详情。 */
    val message: String?

    /** 位置语法无效或无法解析。 */
    data class InvalidLocation(
        /** 无效位置详情。 */
        override val message: String,
    ) : OnyxError

    /** 当前用户没有目标访问权限。 */
    data class AccessDenied(
        /** 权限错误详情。 */
        override val message: String,
    ) : OnyxError

    /** 底层文件 IO 失败。 */
    data class IoFailure(
        /** IO 错误详情。 */
        override val message: String,
    ) : OnyxError
}
