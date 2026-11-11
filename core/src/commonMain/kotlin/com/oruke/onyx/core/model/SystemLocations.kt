package com.oruke.onyx.core.model

/** 应用内回收站浏览入口的稳定虚拟位置，可保存于标签、收藏和浏览历史。 */
const val TRASH_LOCATION = "system://trash"

/** 回收站内单个条目的稳定虚拟 URI 前缀。 */
const val TRASH_ITEM_URI_PREFIX = "system://trash/item/"

/** Windows 资源管理器回收站 Shell 文件夹别名。 */
private const val WINDOWS_SHELL_RECYCLE_BIN = "shell:RecycleBinFolder"

/** Windows 资源管理器回收站 CLSID 路径别名。 */
private const val WINDOWS_CLSID_RECYCLE_BIN = "shell:::{645FF040-5081-101B-9F08-00AA002F954E}"

/**
 * 识别统一回收站位置与 Windows 规范回收站 Shell 入口。
 *
 * @param location 原始位置文本。
 * @return 表示应用回收站容器时返回 `true`。
 */
fun isTrashLocation(location: String): Boolean =
    location.trim().trimEnd('/').let { value ->
        value.equals(TRASH_LOCATION, ignoreCase = true) ||
            value.equals(WINDOWS_SHELL_RECYCLE_BIN, ignoreCase = true) ||
            value.equals(WINDOWS_CLSID_RECYCLE_BIN, ignoreCase = true)
    }

/**
 * 判断位置是否为回收站内的单个虚拟条目 URI。
 *
 * @param location 待检查的 VFS 位置。
 * @return 属于回收站子条目 URI 时返回 `true`。
 */
fun isTrashItemLocation(location: String): Boolean {
    val trimmed = location.trim()
    return trimmed.length > TRASH_ITEM_URI_PREFIX.length &&
        trimmed.startsWith(TRASH_ITEM_URI_PREFIX, ignoreCase = true)
}

/**
 * 根据编码后的条目键构造回收站单条目虚拟位置。
 *
 * @param encodedKey 平台回收站条目的稳定编码键。
 * @return 规范化的回收站条目虚拟位置。
 */
fun buildTrashItemLocation(encodedKey: String): String = "$TRASH_ITEM_URI_PREFIX$encodedKey"
