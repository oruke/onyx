package com.oruke.onyx.app.component

/**
 * 打开指定目录，并在目录加载完成后选中目标条目。
 *
 * @param location 目标目录的 VFS 位置。
 * @param entryName 待聚焦条目的显示名称。
 */
internal fun PaneComponent.openDirectoryAndSelect(location: String, entryName: String) =
    dispatch(PaneIntent.OpenDirectoryAndSelect(location, entryName))
