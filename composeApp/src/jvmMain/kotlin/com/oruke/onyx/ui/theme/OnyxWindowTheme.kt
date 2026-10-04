package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import com.oruke.onyx.core.model.OnyxSettings

/**
 * 在原生窗口自己的基础密度上重新应用统一外观，避免窗口漏缩放或重复缩放。
 *
 * @param settings 当前窗口使用的设置。
 * @param content 窗口内容。
 */
@Composable
internal fun OnyxWindowTheme(settings: OnyxSettings, content: @Composable () -> Unit) {
    val appearance = rememberOnyxAppearance(
        settings.listRowHeightDp, settings.listFontSizeSp, settings.zebraStripeEnabled, settings.fileListColors,
    )
    OnyxTheme(uiScale = settings.uiScale, appearance = appearance, content = content)
}
