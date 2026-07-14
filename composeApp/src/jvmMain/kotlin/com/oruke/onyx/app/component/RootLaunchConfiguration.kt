package com.oruke.onyx.app.component

/**
 * 单个文件管理器窗口的根组件启动配置。
 */
internal data class RootLaunchConfiguration(
    /** 启动后优先打开的位置；为空时使用会话或默认目录。 */
    val initialLocation: String? = null,
    /** 是否负责恢复并持久化应用主会话。 */
    val persistsMainSession: Boolean = true,
    /** 请求在独立文件管理器窗口中打开目录的应用级动作。 */
    val onOpenDirectoryInNewWindow: (String) -> Unit = {},
)
