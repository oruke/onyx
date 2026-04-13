package com.oruke.onyx.core.model

data class VFile(
    val id: String,
    val name: String,
    val location: String,
    val parentLocation: String?,
    val kind: VFileKind,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
    val capabilities: Set<VFileCapability>,
)

enum class VFileKind {
    FILE,
    DIRECTORY,
}

enum class VFileCapability {
    LIST_CHILDREN,
    READ_METADATA,
    READ_CONTENT,
    WRITE_CONTENT,
    DELETE,
    RENAME,
}
