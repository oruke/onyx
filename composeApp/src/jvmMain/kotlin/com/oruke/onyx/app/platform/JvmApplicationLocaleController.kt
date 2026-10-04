package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.OnyxSettings
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 启动时的系统语言；跟随系统不能变成上一次用户选择的语言。 */
private val STARTUP_SYSTEM_LOCALE: Locale = Locale.getDefault()

/**
 * 从应用共享设置统一更新 JVM 资源语言，避免不同窗口的旧组合相互覆盖。
 * @param settings 所有窗口共享的即时设置流。
 */
internal class JvmApplicationLocaleController(
    /** 唯一的语言偏好来源。 */
    private val settings: StateFlow<OnyxSettings>,
) {
    /** 已写入 JVM 的语言偏好，供各窗口在资源环境就绪后重建界面。 */
    private val appliedPreference = MutableStateFlow(settings.value.preferredLocale)

    /** 窗口共享的资源环境更新信号。 */
    val appliedLocale: StateFlow<AppLocale> = appliedPreference.asStateFlow()

    /**
     * 在应用主线程作用域中监听语言偏好，应用结束时随作用域取消。
     * @param scope 应用级协程作用域。
     * @return 语言监听任务。
     */
    fun bind(scope: CoroutineScope): Job = scope.launch {
        settings.map { it.preferredLocale }.distinctUntilChanged().collect { preference ->
            val locale = when (preference) {
                AppLocale.SYSTEM -> STARTUP_SYSTEM_LOCALE
                AppLocale.ENGLISH -> Locale.ENGLISH
                AppLocale.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
                AppLocale.JAPANESE -> Locale.JAPANESE
            }
            // 资源读取和非 Composable 错误消息共用 JVM 语言；窗口只能消费，不能再次写回。
            if (Locale.getDefault() != locale) Locale.setDefault(locale)
            // 必须先切换资源语言再通知界面，否则窗口会用旧 Locale 缓存新偏好的文案。
            appliedPreference.value = preference
        }
    }
}
