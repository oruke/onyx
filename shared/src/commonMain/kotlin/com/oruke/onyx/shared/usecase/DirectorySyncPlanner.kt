package com.oruke.onyx.shared.usecase

/**
 * 同步方向。
 */
enum class DirectorySyncDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    BIDIRECTIONAL,
}

/**
 * 同步操作类型。
 */
enum class DirectorySyncOperationKind {
    COPY_LEFT_TO_RIGHT,
    COPY_RIGHT_TO_LEFT,
    OVERWRITE_LEFT_TO_RIGHT,
    OVERWRITE_RIGHT_TO_LEFT,
    CONFLICT,
}

/**
 * 单个同步预览操作。
 *
 * @property relativePath 相对目录路径。
 * @property kind 同步操作类型。
 * @property difference 来源比较差异。
 */
data class DirectorySyncOperation(
    val relativePath: String,
    val kind: DirectorySyncOperationKind,
    val difference: DirectoryDifference,
)

/**
 * 目录同步预览计划。
 *
 * @property direction 同步方向。
 * @property operations 需要执行或需要用户确认的操作。
 */
data class DirectorySyncPlan(
    val direction: DirectorySyncDirection,
    val operations: List<DirectorySyncOperation>,
)

/**
 * 从目录比较结果生成同步预览计划。
 */
class DirectorySyncPlanner {
    /**
     * 生成同步预览，不直接执行文件写入。
     *
     * @param comparison 目录比较结果。
     * @param direction 同步方向。
     * @return 同步预览计划。
     */
    fun plan(
        comparison: DirectoryComparisonResult,
        direction: DirectorySyncDirection,
    ): DirectorySyncPlan {
        return DirectorySyncPlan(
            direction = direction,
            operations = comparison.differences.mapNotNull { difference ->
                difference.toSyncOperation(direction)
            },
        )
    }
}

/**
 * 将单条目录差异转换为同步操作。
 *
 * @param direction 同步方向。
 * @return 同步操作；无需处理时返回 `null`。
 */
private fun DirectoryDifference.toSyncOperation(direction: DirectorySyncDirection): DirectorySyncOperation? {
    val operationKind = when (kind) {
        DirectoryDifferenceKind.LEFT_ONLY -> when (direction) {
            DirectorySyncDirection.LEFT_TO_RIGHT,
            DirectorySyncDirection.BIDIRECTIONAL -> DirectorySyncOperationKind.COPY_LEFT_TO_RIGHT
            DirectorySyncDirection.RIGHT_TO_LEFT -> null
        }
        DirectoryDifferenceKind.RIGHT_ONLY -> when (direction) {
            DirectorySyncDirection.RIGHT_TO_LEFT,
            DirectorySyncDirection.BIDIRECTIONAL -> DirectorySyncOperationKind.COPY_RIGHT_TO_LEFT
            DirectorySyncDirection.LEFT_TO_RIGHT -> null
        }
        DirectoryDifferenceKind.LEFT_NEWER -> when (direction) {
            DirectorySyncDirection.LEFT_TO_RIGHT,
            DirectorySyncDirection.BIDIRECTIONAL -> DirectorySyncOperationKind.OVERWRITE_LEFT_TO_RIGHT
            DirectorySyncDirection.RIGHT_TO_LEFT -> null
        }
        DirectoryDifferenceKind.RIGHT_NEWER -> when (direction) {
            DirectorySyncDirection.RIGHT_TO_LEFT,
            DirectorySyncDirection.BIDIRECTIONAL -> DirectorySyncOperationKind.OVERWRITE_RIGHT_TO_LEFT
            DirectorySyncDirection.LEFT_TO_RIGHT -> null
        }
        DirectoryDifferenceKind.SIZE_DIFFERENT -> DirectorySyncOperationKind.CONFLICT
        DirectoryDifferenceKind.SAME -> null
    }
    return operationKind?.let { kind ->
        DirectorySyncOperation(
            relativePath = relativePath,
            kind = kind,
            difference = this,
        )
    }
}
