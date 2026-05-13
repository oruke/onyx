package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

data class VFile(
    val id: String,
    val name: String,
    val location: String,
    val parentLocation: String?,
    val kind: VFileKind,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
    val hidden: Boolean,
    val capabilities: Set<VFileCapability>,
)

@Serializable
enum class VFileKind {
    FILE,
    DIRECTORY,
}

@Serializable
enum class VFileCapability {
    LIST_CHILDREN,
    READ_METADATA,
    READ_CONTENT,
    WRITE_CONTENT,
    DELETE,
    RENAME,
}
