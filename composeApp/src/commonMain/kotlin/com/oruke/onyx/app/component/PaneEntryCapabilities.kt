package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind

/**
 * 判断条目是否是当前 Provider 明确允许浏览的目录。
 *
 * @return 目录且具备子项枚举能力时返回 `true`。
 */
internal fun VFile.isBrowsableDirectory(): Boolean {
    return kind == VFileKind.DIRECTORY && VFileCapability.LIST_CHILDREN in capabilities
}
