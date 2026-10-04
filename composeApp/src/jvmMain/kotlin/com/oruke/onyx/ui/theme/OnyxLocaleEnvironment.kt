package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.oruke.onyx.core.model.AppLocale
import kotlinx.coroutines.flow.StateFlow

/**
 * 在应用级语言控制器更新资源语言后，重建当前窗口的资源读取组合。
 * 根组件、窗口状态和后台任务由调用方持有，不随资源组合重建。
 *
 * @param resourceLocale 应用控制器已完成环境切换的语言信号。
 * @param content 使用 Compose 官方资源的界面内容。
 */
@Composable
internal fun OnyxLocaleEnvironment(resourceLocale: StateFlow<AppLocale>, content: @Composable () -> Unit) {
    val appliedLocale by resourceLocale.collectAsState()
    // 窗口之间的重组顺序不确定，只读取共享偏好，不能在各窗口反复设置全局 Locale。
    key(appliedLocale) { content() }
}
